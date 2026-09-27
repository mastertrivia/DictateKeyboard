# RAMBLER PARITY — IMPLEMENTATION NOTES

What was changed in Dictate Keyboard to move it towards Google Keyboard's **Rambler** (Advanced Voice
Typing), what was deliberately left alone, and — importantly — what **cannot** be reproduced because it
lives inside Google's own signed apps.

Full forensic evidence for every claim here: `workspace/re/rambler-forensics/RAMBLER_FORENSIC_REPORT.md`
(1,750+ lines, Parts 0.2–10), derived from the decompiled `Gboard fork -v3.11.0.apk`.

---

## 1. What was implemented

### 1.1 The Rambler cleanup stack — `lib/dictate-core/.../data/prompts/RamblerDefaults.kt` (new)

A self-contained module carrying, **verbatim**, the pieces of Rambler's post-processing that ship as
plain strings inside Gboard's APK:

| Constant / function | Gboard origin |
|---|---|
| `CLEANUP_HEADER` | `JetsonLiteHandler.g(...)`, `<system_role>` + `<rules>` |
| `CLEANUP_INSTRUCTIONS` | the five numbered rules (disfluencies, spelled-out words, grammar, spoken self-corrections, emojis) |
| `CLEANUP_FOOTER` | `<absolute_constraints>` + the `<CURRENT_TEXT>` slot |
| `HINGLISH_OVERRIDE_RULE` | the `{HINGLISH_OVERRIDE_RULE}` substitution |
| `APP_CONTEXT_BLOCK` | `{APP_CONTEXT}` — includes the **chat-vs-other punctuation rule** |
| `PERSONAL_DICTIONARY_BLOCK` | `{PERSONAL_DICTIONARY_CONTEXT}` — includes the **Zero Speech Policy** |
| `CUSTOM_RULES_BLOCK` | `{CUSTOM_RULES_CONTEXT}` — privilege drop + prompt-injection defence |
| `surroundingWindow()` | Gboard's exact before/after trimming (last `\n\n` / first `\n\n`) + `\s+` → `" "` |
| `buildCleanupPrompt()` | the full assembly, context blocks placed immediately before `<CURRENT_TEXT>` |
| `appInfo()` | Gboard's `"Name: %s -- Package name: %s"` |
| `CLEANUP_TEMPERATURE = 0.7f` | `elh.j(0.7f)` |
| `CONFIDENCE_THRESHOLD = 0.5` | `agentic_dictation_confidence_threshold` (`mqh.aa`) |
| `classifyVoiceCommand()` | `VoiceCommandClassifier.classify` — the three **verbatim** regexes |

> **Documented fidelity deviation (deliberate):** Gboard's template *contains* a
> `{HINGLISH_OVERRIDE_RULE}` substitution, but the captured template literal has no such placeholder,
> so in this Gboard build the substitution is a **no-op and the Hinglish rule never reaches the model**.
> We append it as a final cleanup item instead. This is what Gboard clearly intended, and it is the
> behaviour Indic+English dictation needs (Roman script instead of Devanagari). Flagged in-code.

### 1.2 Wiring — `app/.../dictate/DictateController.kt`

`postProcessTranscript()` gained **step 1b**, between the existing auto-formatting step and the
auto-apply prompts:

```kotlin
if (prefs.dictate.ramblerCleanupEnabled.get()) {
    ...
    val cleanupPrompt = RamblerDefaults.buildCleanupPrompt(
        transcript = text,
        enabledLanguages = languages,          // active dictation language, "detect" excluded
        personalDictionary = personalDictionary, // from prefs.dictate.customWords
    )
    text = rewordOrKeep(text) { requestRewordRaw(cleanupPrompt) }
}
```

Why this seam: it is the **one place every transcript already flows through**, and it reuses the
existing, failure-tolerant `rewordOrKeep` machinery. Consequences:

* **No new dependencies, no new network path** — it uses the same rewording account/model the
  auto-formatting feature already uses.
* **Failure is non-fatal** — `rewordOrKeep` keeps the running text on error or a blank answer, so a
  transcript can never be lost (same guarantee as every other step in that chain).
* **Opt-in** — `dictate__rambler_cleanup_enabled` defaults to **false**, because it is an extra model
  round-trip and the brief says not to add unnecessary latency. Turning it on is one switch.

### 1.3 Prefs — `app/.../app/AppPrefs.kt`

```kotlin
val ramblerCleanupEnabled = boolean(key = "dictate__rambler_cleanup_enabled", default = false)
val voiceCommandsEnabled  = boolean(key = "dictate__voice_commands_enabled",  default = true)
```

### 1.4 Strings — `app/src/main/res/values/strings.xml`

`dictate__rambler_cleanup_title/_summary`, `dictate__voice_commands_title/_summary`.

### 1.5 Tests — `lib/dictate-core/.../RamblerDefaultsTest.kt` (new)

kotest `FunSpec` (the module's actual convention), covering the voice-command patterns including the
**false-positive cases** (`"please send the email"`, `"sender"`, `"clear the table later"`), the
surrounding-text windowing, placeholder substitution, optional-block omission and block ordering.

---

## 2. What was deliberately NOT done (and why)

### 2.1 Voice-command *execution* is not yet wired

`RamblerDefaults.classifyVoiceCommand()` is complete and tested, but the execution is **not** hooked
into `finalizeAndCommit()` yet. Reason: that function's tail (history capture, stats, UI-state reset)
must be read first; an early `return` after executing a command risks leaving the controller in a
transcribing state — i.e. it could **break** Dictate, which the brief forbids.

The sink API needed is already present and sufficient (`DictationSink`):
`performEnter()` → SEND, `fullText()` + `deleteLastText(...)` → CLEAR, `selectAll()` + delete → CLEAR_ALL.

### 2.2 The Google-internal engines cannot be reproduced here

These are real, verified parts of Rambler that are **not implementable** from Dictate without access to
Google-signed packages. They are recorded rather than faked:

| Missing capability | Where it actually lives | Why it cannot be copied |
|---|---|---|
| Online ASR | `GoogleAsrService` gRPC service in **`com.google.android.tts`**, reached over **gRPC-over-Binder** (`Intent("grpc.io.action.BIND")`) | The service is **not in Gboard's APK** (verified: Gboard's manifest only has a `<queries>` entry). Caller verification is unknown (Q1). |
| Keyless auth | **Zwieback pseudonymous ID from GMS Core → `auth_token`** (`ZwiebackFetcher`, `S3RequestMutator.setUserInfo`) | Platform-issued token, tied to the Google-signed caller. |
| Offline ASR | on-device models, **feature id 238 / 294**, inside `com.google.android.tts`, fed via a `ParcelFileDescriptor` pipe | Same service, plus the native payload `libdictation_jni.so`. |
| Offline polish | **AICore (Gemini Nano, "LEGION")** via `com.google.android.apps.aicore.aidl.ILLMService` | Separate privileged app; callability unverified (Q4). |
| Local punctuation / capitalization / spoken punctuation / emoji / suffix models | native `dictation_jni` via `formatter/NativeFormatterImpl`, `InteractiveFormatter`, `NativeFormatterCache` | Proprietary native library; `GboardDictationPayloadDetector` exists precisely to test for it. |

### 2.3 The offline/Live-Transcribe question, answered

* **Rambler's offline engine is NOT SODA.** It is the on-device recognizer inside `com.google.android.tts`,
  over the same gRPC service. SODA (`SodaRecognizer`) is the **older engine of the Standard/Traditional
  voice-typing path**.
* **Live Transcribe is only a language/model *management interface*.** Gboard's recognition code never
  binds to it; the only mentions are `SignboardExtension` telemetry and the fork's settings shortcut
  `openLiveTranscribeLanguageManager()`, which just launches
  `com.google.audio.hearing.visualization.accessibility.scribe` so the user can download the
  **`com.google.android.tts`** packs. Live Transcribe and Gboard use **different engines that share the
  same language packs**.
* Therefore **"replace SODA with the Live Transcribe pathway" is not available as an API.** The only
  Rambler offline pathway that is reproducible *in principle* is `GoogleAsrService` (open question Q1).

### 2.4 Quota → offline switch, recorded for future work

Rambler's limit is a **server quota**; the client observes it as one of four `kis` reasons
(`SERVER_QUOTA_EXCEEDED`, `SERVER_RETRY_LIMIT_REACHED`, `SERVER_UNAVAILABLE`) and the switch is automatic in
`SpeechRecognitionFactory` (`kfr.java:56`) → `JETSON_LITE`. Dictate has **no offline tier**, so this cannot
be mirrored yet; the quota refresh (1 h) and unlimited server retries (`-1`) are the client-side halves
that *could* be mirrored later.

---

## 3. Risk assessment (why this cannot break Dictate)

| Change | Risk | Why it is safe |
|---|---|---|
| New `RamblerDefaults.kt` | none | pure addition, no existing symbol touched |
| New pref `ramblerCleanupEnabled` (default **false**) | none | the new step is inside `if (...)`, so default behaviour is byte-identical |
| New pref `voiceCommandsEnabled` (default true) | none | **no reader yet** — declared only, so no behaviour change until execution is wired |
| Step 1b in `postProcessTranscript` | low | identical shape to the existing auto-formatting step and goes through the same `rewordOrKeep` failure guard |
| `strings.xml` additions | none | additive |
| `RamblerDefaultsTest.kt` | none | test-only |

Verified: brace/paren balance on all edited files, `AppPrefs.kt` balanced (76/76), the two new strings
present, and every symbol used by the new code confirmed present in the tree
(`requestRewordRaw`, `rewordOrKeep`, `UiState.Rewording`, `R.string.dictate__status_formatting`,
`dictate.customWords`, `dictate.activeInputLanguage`). No compilation was run here per instruction.

---

## 3.1 Second pass — build blocker restored, voice commands executed, app context + temperature wired

Companion document for the classification of every Rambler component (free / device-gated /
proprietary) and for the developer-facing suggestions: **`RAMBLER_FREE_VS_PROPRIETARY.md`**.

### (a) A previous edit had broken the tree — restored

`DictationSink.kt` was missing two things, which would have failed the build with *unresolved reference*:

* the **`interface DictationSink`** declaration (implemented by `ImeDictationSink`, `AccessibilitySink`,
  `RecognitionSink` and used by `DictateController` as its output seam), now restored with its full current
  method set;
* the private **`releaseComposingOwnership()`** helper called by every finalize/clear path (forwards to
  `EditorInstance.releaseComposingRegionOwnership()`).

### (b) App-aware punctuation — Rambler's `<app_context>` is now populated

`postProcessTranscript()` resolves the target app (package + label via `PackageManager`) on the main
thread and passes it into `buildCleanupPrompt(appLabel, packageName)`, which switches Rambler's
chat-vs-other punctuation rule on. Unresolvable → block omitted, never a failure.

### (c) Voice commands — classified *and* executed

New in `RamblerDefaults`: Gboard's own enum names (`SEND`, `DELETE_LAST_SENTENCE`, `DELETE_ALL`) and
`lastSentenceToDelete(textBeforeCursor)`, which returns the **exact suffix** the `clear` command deletes
(a real suffix, because the sink only deletes when the field still ends with it).

New sink contract members, implemented by all three sinks:

* `performSendCommand()` — `ImeDictationSink` reproduces Gboard exactly: act only when the field's IME
  action is `SEND`, then `performEditorAction(IME_ACTION_SEND)` (Gboard: `npc.a(editorInfo) != 4` guard,
  then `opnVar.y(4)`).
* `deleteCommandText(clearAll)` — `clear all` selects the field and replaces it with nothing; `clear`
  deletes the last sentence through `deleteLastText` (which re-checks the field, so a stale read cannot
  eat the user's words).

`DictateController.finalizeAndCommit()` classifies the **raw** transcript before any rewording, only for a
real output field, and on success takes the grey preview down first (`runVoiceCommand`), resets the
session and returns without committing text.

**Two documented deviations, both to avoid losing speech** (Gboard: `handleBasicAction` executes *only*
`kng.SEND` and drops the other two with "Unsupported basic action", and a refused `send` is discarded):

1. `clear` / `clear all` perform the delete Gboard's own log strings describe instead of doing nothing.
2. A refused command falls through to an ordinary commit instead of swallowing the utterance.

### (d) `temperature = 0.7` (Gboard's own value)

`ChatRequest.ofUser(..., temperature)` + `requestRewordRaw(..., temperature)` exist now; only the Rambler
cleanup pass pins `RamblerDefaults.CLEANUP_TEMPERATURE`. Every other caller passes nothing, so the
provider default is unchanged.

### (e) Tests

`RamblerDefaultsTest` gained `lastSentenceToDelete` coverage (single-sentence fallback, boundary after a
terminator, terminator runs, newline boundaries, null cases, and the "must be a real suffix" invariant).

### (f) Verification performed here

Brace balance on all six touched files, no stray non-ASCII in the edited Kotlin, every symbol used by the
new code confirmed to exist (`editorInstance()`, `ImeOptions.Action.SEND`, `activeInfo.imeOptions.action`,
`performEnterAction`, `activeEditorPackage`, `DictatePreviewState`, `discardRetainedAudio`, `outputTarget`,
`logLatency`'s non-null first parameter — the call is `latencyTrace?.let { … }`). **No build was run**,
per instruction; the two restored declarations are what the missing-symbol audit found.

---

## 3.2 Third pass — architecture: one-stream voice editing and a warm transport

Companion document for the per-item status of every Rambler mechanism (implemented / equivalent /
partial / not-yet / inaccessible, with the evidence): **`RAMBLER_ARCHITECTURE_VERIFICATION.md`**.

### (a) Voice-edit session — Rambler's shape, on our own credential

Gboard's Rambler does not transcribe and then clean up: its route (`gboard_gemini_v3_streaming_voice_edit_mul`)
returns an already-edited transcript **inside the recognition stream**. Dictate did the opposite — a live
transcription model, then a second model call after the stop.

Now `RealtimeRequest.editInstruction` carries the cleanup rules into the session:

* `GeminiRealtimeSession` sends them as `setup.systemInstruction` and reads the **model's own text output**
  as the transcript (accumulating or replacing per part, so both Gemini streaming shapes work): the partial
  is the live polish, a finished turn is a final segment, and at close the turn in flight is settled too.
* The raw `inputTranscription` is kept for coverage bookkeeping and as the fallback when the model never
  answers — a transcription-only model ignoring the instruction must still yield a transcript.
* `isTranscriptionOnlyRealtimeModel()` refuses to send an instruction to `…transcribe…`/`whisper` ids, so
  the existing default model keeps its exact previous behaviour.
* `DictateController` remembers whether the session was a voice editor and passes `polishedInStream` into
  `finalizeAndCommit`, which skips the Rambler cleanup pass — **that is the second API call removed**.
  The tail-recovery path passes `false` on purpose: a recovered tail is raw batch ASR and still needs it.

New pref: `dictate__realtime_voice_edit` (default on, auto-skipped for transcription-only models), with a
settings row and strings.

### (b) Transport pre-warm — Rambler's warm channel, adapted

Rambler's channel is a warm bind into a running process pinned with a 60 s keepalive (`aaqr.k`), so its
first word never pays for a handshake. `RealtimeClient.warmUp(api)` does the reachable equivalent: one cheap
GET to the provider host on a derived client that shares OkHttp's pool, so DNS/TCP/TLS are done and the
session's WebSocket handshake reuses the live connection. Fired from `FlorisImeService.onStartInputView`
through `DictateController.warmUpRealtime()` — fire-and-forget, throttled to once a minute, a no-op for
the on-device provider and for anyone not using realtime dictation.

### (c) What this pass deliberately did not do

Retry policy, second-provider fallback, session caps and cross-dictation session holding are specified in
`RAMBLER_ARCHITECTURE_VERIFICATION.md` §6 with their implementable shape; the offline tier stays behind the
device probe in §4.2. Nothing here claims parity for those.

---

## 4. Follow-ups, in the order they should be done

1. **Wire voice-command execution** in `finalizeAndCommit` after reading its tail (the largest
   remaining zero-latency parity win).
2. **Pass the app label/package** into `buildCleanupPrompt` (`EditorInstance.activeEditorPackage()` is
   available) so the app-aware punctuation rule can act.
3. **Expose the two toggles in Settings** (the strings exist; only the preference rows are missing).
4. **Answer Q1/Q4** (caller verification of `GoogleAsrService`; AICore `ILLMService` callability) before
   attempting any offline tier.
