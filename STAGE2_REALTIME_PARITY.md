# Stage 2 — Realtime Parity with the Google App (Gemini Live path)

**Companion to:** `BASIC_VOICE_TYPING.md` (stage 1: the ported basic voice typing engine)
**Evidence base:** the decompiled Google app (`Robin` = the in-app Gemini conversation code) and the official Live API reference (`ai.google.dev/api/live`)
**Goal:** the stop moment feels like Google's — text is already done when you tap stop, the tail is never lost, and a rescue never re-uploads a whole recording.

---

## 1. Why Dictate was slow — the one architectural difference

Decompiling the Google app settled what the Gemini voice path actually does. Its event log is explicit:

```
CONVERSATION_S3_FIRST_PARTIAL_RECOGNITION_RECEIVED   ← server text arriving DURING speech
CONVERSATION_S3_FINAL_RECOGNITION_RECEIVED           ← server finals arriving DURING speech
CONVERSATION_SODA_START_OF_SPEECH_RECEIVED           ← on-phone reflexes (SODA)
CONVERSATION_SODA_END_OF_SPEECH_RECEIVED
ROBIN_CONVERSATION_MODE_TURN_STOPPED_ASR_ERROR_SODA_SESSION_FAILED
```

The division of labor: **SODA is the reflexes, the server is the transcript, and both run from the first millisecond.** The server locks each phrase the moment a pause is detected — while the user is still talking. At stop, almost nothing is pending; the final phrase locks within 1–2 seconds. That is the whole mechanism. There is no second transcript, no merging, and the text you see at stop is the server's already-finished work.

Dictate already streams audio live to the same kind of endpoint — the pipe was never the problem. What it did *not* do:

1. **Tune the server's phrase-locking to Google's speed.** Gemini Live's automatic activity detection has conservative defaults: long prefix padding (the first interim arrives late — your "text only starts after 3–5 seconds") and a slow silence threshold (turns lock late — everything still in draft at stop).
2. **Recover only the uncovered tail.** When a stream failed or the tail wait gave up, Dictate re-uploaded the *entire* recording to the batch model — the "stuck on Transcribing" case, and the reason a rescue could still lose or duplicate words.

Stage 2 fixes both, using the same knobs and the same division of labor the Google app uses.

## 2. Change 1 — the server's VAD tuned to Google's reflex speeds

In `GeminiRealtimeSession.setup()` (RealtimeClient.kt), the setup message now configures `realtimeInputConfig.automaticActivityDetection`:

```json
"realtimeInputConfig": {
  "automaticActivityDetection": {
    "disabled": false,
    "startOfSpeechSensitivity": "START_SENSITIVITY_HIGH",
    "endOfSpeechSensitivity": "END_SENSITIVITY_HIGH",
    "prefixPaddingMs": 100,
    "silenceDurationMs": 300
  }
}
```

Why these exact choices:

- **`startOfSpeechSensitivity: HIGH` + `prefixPaddingMs: 100`** — speech start commits almost immediately, so the first interim reaches the field in well under a second instead of after seconds of padding. This is the "text appears immediately" feel.
- **`endOfSpeechSensitivity: HIGH` + `silenceDurationMs: 300`** — a 300 ms pause ends the turn, and `inputTranscription` (the settled text) for that phrase arrives **while you keep talking**. Phrase-by-phrase locking, exactly like `CONVERSATION_S3_FINAL_RECOGNITION_RECEIVED` during speech. At stop only the last phrase is pending → the same 1–2 s finish as Google.
- **Auto-detection stays ENABLED, deliberately.** The Live API documents that `activityStart`/`activityEnd` client signals are only legal when server-side detection is *disabled* — and disabling it would put the burden of ending turns on our own VAD. If that ever misfired, the server would lock *no* turns and no finals would ever arrive: strictly worse than today. Using the server's own detector, tuned fast, cannot have that failure mode. (A later stage can revisit client-signaled mode; it is out of scope here on purpose.)
- `audioStreamEnd` in `finish()` remains legal — it is only tied to auto-detection being enabled.

The `handle()` path also logs `activityStart`/`activityEnd` events (one line, INFO) so the tuning can be observed in the field through `adb logcat -s DictateRT`.

## 3. Change 2 — coverage tracking + tail-only recovery

### 3a. The session tracks what its settled text covers

`RealtimeSession` gained one method with a safe default:

```kotlin
fun coveredAudioSeconds(): Double? = null
```

`GeminiRealtimeSession` implements it:

- `fedAudioBytes` — a running count of every PCM byte handed to `sendAudio` (the recording on disk contains exactly these bytes, so coverage is measured against all of them, gated or not).
- On every **settled final** (`emitFinal`): `coveredBytes = fedAudioBytes − coverageLagBytes`, where the lag is a conservative 2 s (the server settles a phrase while its last ~2 s of audio may still be arriving; those seconds stay uncovered).
- `coveredAudioSeconds()` = `coveredBytes / 32_000` (16 kHz mono PCM16), clamped to what was actually fed.

Google's rule, expressed in one line: *covered audio never re-travels.*

### 3b. The controller rescues with the tail, never the whole recording

`DictateController.recoverRealtimeTail()` — called from the failure branch of `stopRealtimeAndFinalize`:

1. Read `session.coveredAudioSeconds()`. No tracking / nothing settled → return null (old behavior).
2. Decode the recording once (`AudioDecode.decodeToMono16k`), compute the uncovered length.
   - **Uncovered region < 0.4 s** → the settled transcript *is* the complete dictation: return it as-is. This is Google's stop moment — the server had finished the words seconds ago; the result is revealed as-is. No second request at all.
3. Slice `recording[coveredSeconds..end]` with `AudioWav.write` (the recorder's own format) into a cache file.
4. Transcribe **only the tail** through `transcribeSegmentRaw` — the same helper the long-form path uses (silence gate, packing, style prompt), with the settled transcript as continuity.
5. Join settled + tail with `TranscriptJoin.join` (the same piece-joiner every other multi-part transcript uses, so the seam handles punctuation binding).

Failure semantics are deliberately conservative:

- Tail transcription returned empty **and the tail is pure silence** (checked with `SpeechGate.hasSpeech` *before* the file is deleted) → the silence owes nothing; commit the settled transcript.
- Tail transcription returned empty **and the tail has speech** → a real failure; return null so the caller keeps the proven full-recording fallback. The recovery is a latency/data win, **never a correctness bet**.
- The recovered result commits through `finalizeAndCommit(..., finalizeViaComposing = true)` — the grey preview stays up and is swapped for the finished text in one commit, the same finalization a healthy stream gets. No clear-then-retype flicker.

The `rtCapture` history metadata was hoisted so both commit paths (healthy stop and recovery) write identical history rows.

## 4. What was NOT changed (on purpose)

- The other six realtime sessions (OpenAI, Deepgram, Soniox, AssemblyAI, ElevenLabs, Mistral) — untouched. Their `coveredAudioSeconds()` defaults to null, so their rescue path is exactly as before. Wiring their per-provider coverage is trivial later (each needs only its own bytes-per-second constant).
- The batch pipeline, segmented long-form, live prompts, auto-format, history, the sink's preview behavior — untouched.
- No client-side VAD on the realtime path: `RealtimeAudioGate` already replays mic-startup audio correctly, and the tuned server detector makes a second detector redundant.

## 5. Result, measured against the symptoms

| Symptom | Cause | After stage 2 |
|---|---|---|
| Text starts 3–5 s after speaking | default `prefixPaddingMs` padding before first interim | `prefixPaddingMs: 100` + `START_SENSITIVITY_HIGH` → first interim in well under a second |
| "Stuck on Transcribing" | whole recording re-uploaded from zero on any failure/sliver case | tail-only recovery; sliver case commits instantly with no second request |
| Last words cut off | everything still draft at stop; commit/wait race at the tail | phrases lock during speech (`silenceDurationMs: 300`); at stop only the last phrase is pending; the sliver case reveals the settled text as-is |
| Rescue duplicates/loses words | fallback ignored what the server already settled | settled transcript is reused as the head; only the uncovered region is re-transcribed, joined with continuity |

## 6. Testing checklist (on device, Gemini Live + realtime mode on)

1. **Speed:** start dictating — first grey text should appear in under ~1 s.
2. **Mid-speech locking:** speak two sentences with a clear 0.5 s pause — the first sentence's words should already be grey in the field before you finish the second (visible as steady text growth instead of one late blob).
3. **Stop moment:** tap stop right after speaking — final text within ~1–2 s.
4. **Airplane-rescue:** dictate ~20 s, turn Wi-Fi off mid-dictation, stop — the transcript should still arrive (via tail/full fallback) and never duplicate the first sentence.
5. **Sliver case:** dictate a long passage, then only a word or two more after the network dies, stop — commit should be immediate from the settled text.
6. **Regression:** normal dictation, hold-to-send-local, long-form segmented mode, and the floating overlay should behave exactly as before.

Latency traces (`logLatency`) and the new `gemini vad event` line in `DictateRT` give the field evidence for 1–3.

## 7. Files changed in stage 2

- `lib/dictate-core/.../provider/RealtimeTranscription.kt` — `RealtimeSession.coveredAudioSeconds()` (default null, fully documented).
- `lib/dictate-core/.../provider/RealtimeClient.kt` — Gemini `setup()` VAD tuning; coverage fields + `coveredAudioSeconds()` + activity-event log line.
- `app/.../dictate/DictateController.kt` — `recoverRealtimeTail()`; the failure branch of `stopRealtimeAndFinalize` now tries tail-only recovery before the full-recording fallback; hoisted history capture; one `AudioWav` import.
