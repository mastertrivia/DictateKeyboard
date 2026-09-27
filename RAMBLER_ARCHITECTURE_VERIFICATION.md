# RAMBLER ARCHITECTURE PARITY — VERIFICATION PASS

Companion documents: `RAMBLER_FREE_VS_PROPRIETARY.md` (what is free / device-gated / proprietary, per
component), `RAMBLER_PARITY_IMPLEMENTATION.md` (implementation notes), and the forensic extraction at
`workspace/re/rambler-forensics/RAMBLER_FORENSIC_REPORT.md` (Parts 0.2–11, every claim with its
decompiled file/line).

**What this document is.** The answer to the exact question *"does Dictate now contain the maximum
technically reproducible Rambler architecture?"* — item by item, with the status of each, so that a
missing piece cannot be papered over by text that merely reads like Rambler's output.

Legend: **IMPLEMENTED** · **EQUIVALENT** (different mechanism, same guarantee) · **PARTIAL** ·
**NOT IMPLEMENTED YET** · **INACCESSIBLE** (needs Google-signed components) · **NEEDS DEVICE PROBE**.

---

## 1. The architecture, before and after

### 1.1 Rambler (from the decompiled APK)

```
mic press → AgenticDictationExtension (no init wait)
          → SpeechRecognitionFactory routing
          → S3AudioProviderImpl (per-frame flow, no client chunk window)
          → gRPC-over-Binder → com.google.android.tts GoogleAsrService
            (auth: Zwieback → auth_token; route: gboard_gemini_v3_streaming_voice_edit_mul)
          → the service is a VOICE EDITOR: recognition + cleanup in ONE stream
          → finalized span (+VoiceAction) → bounded delete + write in one edit transaction
stop      → emit default message = end-of-stream HALF-CLOSE → final span returns immediately
limits    → server quota; kis reasons; automatic fallback to the on-device model (238/294)
offline   → same gRPC service, pipe-FD audio, polish by AICore (Gemini Nano)
```

### 1.2 Dictate before this pass

```
mic press → startRecording → RealtimeClient.open → TLS + WS handshake + key auth
          → Gemini *transcription* stream (inputTranscription / interimInputTranscription)
stop      → audioStreamEnd (half-close ✓) → tail wait → finals committed
          → THEN a SECOND model call (Rambler cleanup pass) if the toggle is on   ← the structural gap
```

### 1.3 Dictate after this pass

```
keyboard view opens → warmUpRealtime()   ← DNS/TCP/TLS already done, pooled connection live
mic press → startRecording → WS handshake on the warm connection
          → GEMINI voice-edit session when the model can carry an instruction:
              setup.systemInstruction = Rambler's cleanup rules (buildVoiceEditInstruction)
              model's own text output = the transcript (partials = live polish)
              inputTranscription = coverage bookkeeping + fallback
stop      → realtimeInput.audioStreamEnd (Rambler's half-close equivalent) → tail wait
          → finals committed; the cleanup pass is SKIPPED (already done in-stream)   ← gap closed
```

---

## 2. What was implemented in this pass

| # | Change | File | Effect |
|---|---|---|---|
| 1 | **Voice-edit session** — `RealtimeRequest.editInstruction`; Gemini `setup.systemInstruction`; the session reads the model's own text output as the transcript (partial = live polished preview, final = settled turn), with the raw input transcription kept for coverage and as a no-polish fallback at close | `lib/dictate-core/.../provider/RealtimeTranscription.kt`, `RealtimeClient.kt` | Recognition + Rambler cleanup in **one** stream, exactly the shape of Google's `…_streaming_voice_edit_mul` route |
| 2 | **No second call** — `postProcessTranscript(alreadyCleanedUp)` + `finalizeAndCommit(polishedInStream)`; the realtime commit skips the Rambler cleanup pass when the stream did it | `app/.../dictate/DictateController.kt` | Removes the *"transcribe → wait → reword → wait"* chain on the live path |
| 3 | **Transport pre-warm** — `RealtimeClient.warmUp(api)` (cheap GET to the provider host, 5 s call timeout, derived client sharing the pool) fired from `onStartInputView` via `DictateController.warmUpRealtime()`, throttled to once a minute | `RealtimeClient.kt`, `DictateController.kt`, `FlorisImeService.kt` | DNS/TCP/TLS are done before the mic is pressed — the WebSocket reuses the pooled connection |
| 4 | **Model guard** — `isTranscriptionOnlyRealtimeModel()`: `…transcribe…`/`whisper` ids never get a voice-edit instruction | `DictateController.kt` | A transcription-only model cannot silently lose its transcript to an instruction it ignores |
| 5 | **Toggle** — `dictate__realtime_voice_edit` (default **on**) + settings row + strings | `AppPrefs.kt`, `DictateRewordingScreen.kt`, `strings.xml` | Testable from Settings → Dictate → Rewording |
| 6 | **Voice commands executed** (earlier in this session) | `RamblerDefaults.kt`, `DictationSink.kt` + 3 sinks, `DictateController.kt` | Rambler's three commands, with Gboard's own enum names and its exact SEND guard |
| 7 | **Restored two build blockers** (earlier in this session) | `DictationSink.kt` | The missing `interface DictationSink` and `releaseComposingOwnership()` — the tree did not compile without them |

**Verification performed here:** brace balance on every touched file, no stray non-ASCII in the edited
Kotlin, every referenced symbol checked to exist (`putJsonArray` import, `RealtimeClient.open` parameter
threading, `RealtimeApi`/`TranscriptionApi`/`DictateLanguages`/`RamblerDefaults` imports, `voiceEdited`
captured before the commit coroutine, the flag reset on all three teardown paths), XML strings present,
settings row wired. **No build was run**, per instruction — so "IMPLEMENTED" below means *the code is in
place and self-consistent*, not *observed on a device*. The one thing that must be tried on hardware is
the voice-edit stream against a conversational live model (§4.1).

---

## 3. The 21 audit points — status, item by item

| # | Point | Status now | Evidence / what remains |
|---|---|---|---|
| 1 | Startup | **EQUIVALENT**: no init wait in `onVoiceStart` (`fbl.java:1186-1210` is logging only) — nor in our start path; we now also pre-warm the transport | Rambler's bind into a warm process has no analogue on a socket; the pre-warm is the closest |
| 2 | Mic timing | **PARTIAL (best available)**: warm transport means the handshake is already done | Remaining gap = the WS `setup`/`setupComplete` round trip, ~1 RTT. A session *held open across dictations* would remove it (see §6.1) |
| 3 | Audio capture / framing | **EQUIVALENT**: per-frame PCM, no client-side accumulation window (Rambler: conflated Flow cap 4, `slw.java`) | Different transport, same policy |
| 4 | Audio transport | **INACCESSIBLE**: gRPC-over-Binder + pipe FD into `com.google.android.tts` | Not reproducible; our WebSocket is the substitute |
| 5 | Model / pathway | **IMPLEMENTED**: a *voice-edit* stream instead of transcribe-then-clean | Google's route is server-side; ours is `systemInstruction` on a conversational live model |
| 6 | API behaviour | **EQUIVALENT**: we read our own stream's text output as the transcript | Rambler reads a finalized span + VoiceAction from its service |
| 7 | Limits | **NOT IMPLEMENTED YET**: no quota detection, no retry policy | Client-side halves are copyable (§6.2); the server quota itself is Google's |
| 8 | Online→offline switch | **NOT IMPLEMENTED YET** (a user-configured second provider is the reproducible form) | §6.3 |
| 9 | Offline model | **INACCESSIBLE**: feature 238/294 inside `com.google.android.tts` | §4.2 (device probe) |
| 10 | Language handling | **EQUIVALENT**: `inputAudioTranscription.languageCodes` carries the pinned language or the user's dictation languages (#99) | Rambler sends the same field; its offline packs are Google's |
| 11 | Punctuation | **PARTIAL**: prompt-level rules in-stream (chat-vs-other rule, spoken punctuation, sentence casing by the model) + `mode: SMART` on the transcription channel | Gboard's local `dictation_jni` models are proprietary (§5) |
| 12 | Capitalization | **PARTIAL**, same reason | — |
| 13 | Corrections / self-corrections | **IMPLEMENTED**: rule 4 of the prompt, verbatim, now applied *in-stream* | — |
| 14 | Polishing | **IMPLEMENTED**: in-stream (no second call) on a voice-edit session; the batch path keeps the opt-in extra pass | — |
| 15 | Turn detection | **EQUIVALENT**: our tuned server-side AAD (HIGH/HIGH, 100 ms prefix, 300 ms silence) + `recoverRealtimeTail` | Rambler relies on the server's VAD; ours is configured for the same reflex speed |
| 16 | Finalization | **EQUIVALENT**: `audioStreamEnd` is a real half-close; the tail wait listens for the provider's closing words | Rambler half-closes by emitting a default message |
| 17 | Stopping | **EQUIVALENT**: stop flag + session teardown + late-callback block | — |
| 18 | Text insertion | **EQUIVALENT (kept)**: composing region + committed base | Rambler's bounded delete+write is simpler but cannot survive a mid-dictation keyboard touch the way ours does |
| 19 | Latency | **IMPROVED**: warm transport (−DNS/TCP/TLS), in-stream polish (−one model round trip), no client buffering | Remaining: session hold (§6.1) |
| 20 | Unnecessary API calls | **FIXED** on the live path: the cleanup pass no longer runs after a voice-edit session | The batch path still uses it, by design (it has no stream to polish in) |
| 21 | Extra Dictate processing | **KEPT, evaluated**: auto-formatting, auto-apply prompts, paragraph split, mappings, stats, history, tail recovery | Each is opt-in or off the critical path; none adds a call unless the user turned it on |

**Nothing on this list is skipped silently.** 7, 8 and 9 are the only entries that are not implemented;
9 is inaccessible, 7 and 8 are reproducible and are specified in §6.

---

## 4. The precise technical boundary

### 4.1 What the voice-edit session requires (testable today)

* A **conversational** live model on the Gemini account, not a transcription model. Dictate's provider
  presets default the realtime model to `gemini-3.5-transcribe-live`; with that id the voice-edit toggle
  is **skipped automatically** (`isTranscriptionOnlyRealtimeModel`) and behaviour is exactly as before.
  Set the realtime model to a conversational live model (e.g. a `gemini-live-*` id) to exercise the
  Rambler-shaped path.
* Expected effect, in order of certainty: (a) no second model call; (b) punctuation/capitalization/
  disfluency handling arrive *with* the text; (c) the first turn's text appears as the model answers,
  not only at the end.
* Risk, stated plainly: a conversational model can answer as a chatbot. The session instruction ends
  with an explicit `<stream_instruction>` that forbids exactly that, and the raw input transcription is
  kept as the fallback if no model text arrives at all — but this must be *observed* on a device before
  it is called equivalent. If a model ever answers conversationally anyway, switch the toggle off: the
  old path is untouched.

### 4.2 What needs a device probe (and how to settle it in minutes)

`GoogleAsrService` is declared in `com.google.android.tts`, which is **not** in our decompiled tree (the
APK on disk is Gboard; Speech Services is not installed as a file we have). Whether it accepts a
third-party caller is therefore a device question, and it is answerable without guesswork:

```bash
adb shell pm list packages | grep ttsservice        # exact package of Speech Services
adb shell dumpsys package com.google.android.tts | grep -A3 -i "GoogleAsrService\|grpc"
adb shell am startservice -a grpc.io.action.BIND -n com.google.android.tts/<service>   # will refuse if unexported
```
If `dumpsys` shows the service `android:exported="false"` (or a signature-level permission), the boundary
is *closed at the manifest* and no amount of client code changes it. If it is exported with no permission,
a five-line `bindService` probe in a personal build answers the rest. Until that is run, `GoogleAsrService`
stays **NEEDS DEVICE PROBE** — not "impossible", and not "available".

### 4.3 What is genuinely unreachable from Dictate

Zwieback → `auth_token` (platform-issued, caller-bound), the `gboard_gemini_v3_…` server route itself, the
on-device models 238/294 and their `DownloadModel` provisioning, AICore `ILLMService`, and the native
`libdictation_jni.so` formatter models. **No user-facing API exists for any of them**, and none of them can
be redistributed. This is the wall — and it is the same wall whether the caller is Dictate or any other
keyboard.

---

## 5. Where "Rambler-level punctuation" actually comes from — and what Dictate can do about it

Gboard's punctuation/capitalization is **two** systems, and only one is a prompt:

1. **Prompt rules** (what the model does with the text) — free, and now applied in-stream.
2. **Local models in `libdictation_jni.so`** (`formatter/NativeFormatterImpl`, `InteractiveFormatter`,
   `NativeFormatterCache`, `NativeEmojiNluHandler`, built at `ofe.java:195`) — the punctuation,
   capitalization, spoken-punctuation, spoken-emoji and suffix-command models. Proprietary.

Dictate's subset **already has the concept**: the on-device local provider (`LocalTranscriptionProvider`)
runs an installed model and does its own formatting, which is why the local path's output quality differs
from the cloud path's. Bringing Gboard's formatter in is impossible; the honest equivalent is the
in-stream prompt rules plus whichever local model the user installs. **Documented, not faked.**

---

## 6. Remaining work, in the order that buys the most

### 6.1 Hold a warm session across dictations (the last startup millisecond)
`RealtimeRequest`/`RealtimeSession` are already built around a swappable callback interface, so the shape
is: open a session when the keyboard view appears, send no audio, hold it for ~15 s with the existing
20 s WS ping, and hand it to the controller when recording starts (its `setupComplete` is already in).
What is missing is a "session lease" object and a hand-off API. Expected effect: removes the last
handshake/setup latency from the first word.

### 6.2 Retry policy (Rambler: `max_server_retries = -1`)
`RealtimeClient` sessions currently surface a failure to the controller, which falls back to batch. A
bounded retry inside the session (2 attempts, short backoff, same audio gate so nothing is lost) mirrors
Rambler's feel without unbounded loops.

### 6.3 Automatic fallback to a second configured provider
Rambler's `kfr.java:56` swaps engines inside the factory when `kit.l != kis.NONE`. The reproducible form
is: a per-account "fallback provider" pref; on a quota/auth/limit error from the realtime provider, re-run
the *same recording* through the fallback account (batch is enough) and tell the user which engine
answered. Everything needed exists — the WAV is already retained for exactly this path.

### 6.4 Session caps (Rambler: warn 20 s, cap 300 s, countdown 10 s)
No cap exists today. A Smartbar chip at 20 s and an automatic stop at 5 minutes, with the countdown in the
last 10 s, closes that gap.

### 6.5 Per-app custom rules, confidence gate, `compose` rules
Prompt-level wiring that needs a small store/flag each (already recorded in
`RAMBLER_FREE_VS_PROPRIETARY.md` §5.4–5.6).

---

## 7. The direct answer to the final verification question

> *Does Dictate now contain the maximum technically reproducible Rambler architecture and behaviour?*

**Yes for everything that is code, and no for everything that is Google's process** — and the split is now
documented rather than asserted:

* **Reproduced:** one-stream recognition + voice editing, Rambler's cleanup rules verbatim (temperature
  0.7, app-aware punctuation, personal dictionary with the Zero Speech Policy, custom rules with injection
  defence, Hinglish romanisation), half-close finalization, per-frame audio with no client buffering, tuned
  reflex VAD, tail recovery that re-sends only the uncovered region, voice commands (with Gboard's exact
  SEND guard), transport pre-warming, and the removal of the second model call from the live path.
* **Equivalent rather than identical:** the transport (WebSocket vs Binder/gRPC), the session lifetime, and
  the insertion bookkeeping (composing region + committed base vs bounded delete + write) — each with the
  same guarantee, and the insertion model deliberately kept because it survives a mid-dictation keyboard
  touch.
* **Not reproduced, with the reason:** the server route and its credential (unreachable), the on-device
  models and the native formatter (proprietary binaries), and the automatic offline tier (depends on both).
* **Not yet done but doable:** retry, second-provider fallback, session caps, session holding (§6).

**No claim of parity is made for the unbuilt items or for the proprietary ones.** The one new mechanism
that is implemented but not yet observed on hardware is the voice-edit stream (§4.1); it is gated behind a
toggle, it falls back to the previous behaviour automatically for transcription-only models, and it is the
piece to try first on a device.
