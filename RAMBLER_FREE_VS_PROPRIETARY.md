# RAMBLER — WHAT IS FREE, WHAT IS PROPRIETARY, WHAT DICTATE NOW DOES

**Purpose of this document.** Every part of Google Keyboard's **Rambler** (Advanced Voice Typing) that we
have been able to read in the decompiled APK, classified as:

* **FREE** — the logic is plain text/code inside Gboard's own APK or is a standard Android API, so it can
  be re-created in Dictate exactly, with no Google binary involved.
* **FREE-BY-REIMPLEMENTATION** — the *behaviour* is reproducible, but the code has to be written by us
  (no shared source): e.g. sentence deletion, client-side retry policy.
* **DEVICE-GATED** — reproducible only by calling a service that lives in another Google-installed app.
  It may work on a device that has Speech Services (`com.google.android.tts`) and fail elsewhere; it is
  never a build-time dependency of Dictate.
* **PROPRIETARY** — needs Google-signed binaries, platform-issued credentials, private services or
  private model files. Cannot be reproduced; cannot be redistributed.
* **DEAD / DO NOT COPY** — present in the APK but with no consumer in this build. Copying it would be
  cargo-culting.

This document is the "writer's guide" that goes with `RAMBLER_PARITY_IMPLEMENTATION.md` (what changed in
the code) and with the forensic extraction at
`workspace/re/rambler-forensics/RAMBLER_FORENSIC_REPORT.md` (every claim with the decompiled file/line it
came from, Parts 0.2–11).

**Evidence base.** Decompiled `Gboard fork -v3.11.0.apk`
(`heliboard-fixed/Decompiled Versions/gboard-fork-v3.11.0-decompiled/`). Class names are quoted both as
the obfuscated `defpackage` filename *and* the real class name recovered from the `wef.i(...)` log tags
that jadx preserves verbatim.

---

## 1. The whole Rambler pipeline (with locations) — one page

```
USER PRESSES THE MIC
  ↓  fbl.java — AgenticDictationExtension (an IME extension)
     • flag gate: mqk.c() requires ad_activation_type == 2 (the fork pins it)
     • onActivate (orig. 308/322) → onActivateInternal (429)
     • onVoiceStart (955) is LOGGING ONLY — no init wait, no language negotiation
  ↓  kfr.java — SpeechRecognitionFactory (the routing decision)
     • NEW_S3 (server) → JETSON_LITE (on-device) → Voice IME (traditional)
     • the offline switch is automatic: kfr.java:56  `if (kit.l != kis.NONE) → JETSON_LITE`
  ↓  S3 path: slw.java — S3AudioProviderImpl
     • audio frames pushed into a Kotlin Flow, buffer −2/CONFLATED, size 4 (no ms-window)
     • stop() emits the DEFAULT `ujd` message = end-of-stream half-close  ← the tail trigger
  ↓  sle.java / smc.java / smx.java / una.java — the gRPC client
     • rle.java:70 builds the channel to a DIFFERENT APP:
       ComponentName("com.google.android.tts",
                     "com.google.libraries.speech.transcription.recognition.grpc.GoogleAsrService")
     • aauh.java → AndroidComponentAddress extends SocketAddress, Intent("grpc.io.action.BIND")
       = gRPC-over-Binder
     • una.java = GoogleAsrServiceGrpc: bidi `RecognitionSession` + unary `DownloadModel`
  ↓  AUTH (the reason there is no API key): S3RequestMutator (orig. 278)
     • `auth_token` built from a ZWIEBACK pseudonymous ID fetched from GMS Core (ZwiebackFetcher)
     • routing key (orig. 138): routing_key = "gboard_gemini_v3_streaming_voice_edit_mul"
  ↓  SERVER (Gemini voice-edit route)
     • returns spans; only a span that is FINALIZED **and** carries a VoiceAction is accepted
       (fbl.java onVoiceText step 2)
  ↓  BACK IN THE EXTENSION — fbl.java onVoiceText (orig. 1181-1246; decompiled 455-576)
     • safety gate → confidence gate (0.5) → voice-command classify (fcb.a) → else insert
     • insertion: delete the previous preview length, then write the new text, in ONE edit
       transaction (fao.f = AgenticDictationTextEditor, the analogue of our DictationSink)
  ↓  OFFLINE (JETSON_LITE), same gRPC service:
     • fcz.java  — JetsonLiteStreamingAsrAdapter: ParcelFileDescriptor pipe handed to the service
       as a call option; 16 kHz / 48 kHz; from-mic session timeout 3 s, otherwise 120 s
     • fcm.java  — JetsonLiteHandler: feature id 238 (v1) / 294 (v2), DownloadModel over the
       same channel, and the FULL cleanup prompt (see §3)
     • polish offline = AICore / Gemini Nano via com.google.android.apps.aicore.aidl.ILLMService
  ↓  PUNCTUATION / CAPITALIZATION (client side, always): dictation_jni
     • pch.java:50 NativeLibHelper.c("dictation_jni"), formatter/NativeFormatterImpl, InteractiveFormatter,
       NativeFormatterCache/NativeFormatterLoader, NativeEmojiNluHandler
```

---

## 2. THE MASTER MATRIX — component by component

### A. Settings, flags, selection

| # | Rambler component | Exact location | Class | Notes |
|---|---|---|---|---|
| A1 | The Rambler radio row + the two-mode choice | `advancedvoice/GboardVoiceInputMode.java`, `GboardAdvancedVoice*` fork classes | **FREE** | Pure UI + a pref write. Dictate's equivalent is its provider list; no new UI was needed. |
| A2 | The boolean that means "Rambler is selected" | `mqk.a()` reads pref `string_0x7f140a0d` | **FREE** | A pref read. |
| A3 | The whole flag registry (`enable_agentic_dictation`, `enable_rambler_al_toolbar`, `agentic_dictation_*`, `jetson_*`) | `mqh.java` (values read via `mqh.X.g()`) | **FREE** | These are *values*, not code. We read them to learn the intended behaviour (that is where the 0.7 temperature, 0.5 confidence, 1 h quota refresh, −1 retries, 20 s warn, 300 s cap come from). |
| A4 | Forced-on stock flags | `dev/jason/gboardpatches/extension/rambler/GboardRambler1803StockPolicy.java` | FREE (fork code) | The fork unlocks an existing feature; it builds nothing new. Explains why Romber appeared without an API key. |

### B. Routing / engine choice

| # | Rambler component | Exact location | Class | Notes |
|---|---|---|---|---|
| B1 | Three-tier routing NEW_S3 → JETSON_LITE → Voice IME | `kfr.java` (`SpeechRecognitionFactory.a`) | **FREE-BY-REIMPLEMENTATION** (the *policy*) / **PROPRIETARY** (the targets) | The decision logic is copyable; the engines it decides between are not. Dictate has one engine per provider + its own tail recovery. |
| B2 | Automatic offline switch on quota/retry/unavailable | `kfr.java:56`, reasons in `kis.java` (`NONE/SERVER_QUOTA_EXCEEDED/SERVER_RETRY_LIMIT_REACHED/SERVER_UNAVAILABLE`) | **FREE-BY-REIMPLEMENTATION** (detection) / **PROPRIETARY** (offline engine) | Dictate can mirror "detect + switch", but has nothing to switch *to* unless the user configures a second provider. Suggested in §5. |
| B3 | `SpeechRecognitionFactory` logging strings | `kfr.java` `"[Jetson] Using JETSON_LITE for offline fallback."` | FREE | Evidence only. |

### C. Transport / authentication

| # | Rambler component | Exact location | Class | Notes |
|---|---|---|---|---|
| C1 | gRPC-over-Binder channel into `com.google.android.tts` | `rle.java:70`, `aauh.java`, `una.java` | **PROPRIETARY / DEVICE-GATED** | The service is **not in Gboard's APK** (Gboard's manifest only has a `<queries>` entry for the package). Whether it accepts a third-party caller is open question Q1 — answerable only by binding from a test app. |
| C2 | Keyless auth (`Zwieback` → `auth_token`) | `sze.java` (ZwiebackFetcher), `S3RequestMutator` orig. 278 | **PROPRIETARY** | A platform-issued pseudonymous ID tied to the Google-signed caller. This is *the* reason Rambler needs no key. |
| C3 | Routing key | `S3RequestMutator` orig. 138 (`gboard_gemini_v3_streaming_voice_edit_mul`) | **PROPRIETARY** | Server-side route name; useless without C1+C2. |
| C4 | Request fields: language, **client package name**, feature id, `FALLBACK_ALWAYS` | `smc.java`, `fcz.java`, `umu` | MIXED | Plain fields (free) sent to a proprietary service. |
| C5 | Keepalive pinned to 60 s | `aaqr.k(TimeUnit) → a.d(60L, …)` | **FREE (principle)** | Adoptable for our WebSocket: a warm socket is a latency win. See §5.2. |

### D. Audio path

| # | Rambler component | Exact location | Class | Notes |
|---|---|---|---|---|
| D1 | Audio handed over as a `ParcelFileDescriptor` pipe (no per-frame serialisation) | `fcz.java` `ParcelFileDescriptor.createPipe()` + `xtm.e(stub, unc.a, pfd)` | **PROPRIETARY** | Binder-specific. The *principle* (do not accumulate client-side) is free — and Dictate already streams frame-by-frame. |
| D2 | No client-side chunk window: per-frame Flow, conflated, cap 4 | `slw.java` `abol.J(-2, 1, null, 4)` | **FREE (principle)** | Verifies our "stream every frame, let the server VAD decide" design. |
| D3 | 16 kHz / 48 kHz, `encoding == 20 → 48000` | `fcz.java:91` | FREE | Matches our sample-rate handling. |
| D4 | Stop = emit the default message → half-close | `slw.java` `stop()` | **FREE (principle)** | Semantically identical to our "stop → finalize with an explicit end-of-stream"; Rambler does it by half-closing the stream instead of sending a "final" request. |

### E. Recognizer behaviour

| # | Rambler component | Exact location | Class | Notes |
|---|---|---|---|---|
| E1 | `onVoiceStart` performs **no** initialisation wait | `fbl.java:1186-1210` (logging only) | **FREE (principle)** | The single place Rambler demonstrably "does less". Dictate's start path was audited against it; the only remaining extras are audio-permission/route setup, which are Android requirements, not our invention. |
| E2 | Request carries the field's text (`jetson_send_input_box_text_at_end`) | `idk.java`, `icz.java:70,193`, `gm.java:305`, `idc.java:88` | **FREE** | Dictate already has `surroundingWindow()` for exactly this (last/first `\n\n` trimming). |
| E3 | 3 s from-mic / 120 s otherwise session timeout | `fcz.java` (`ofSeconds(3)`, `ofMinutes(2)`) | FREE | A policy we can mirror per provider; not a fidelity requirement. |
| E4 | Server-side VAD finds the turn end; client only half-closes | (server behaviour, observed from the client) | **PROPRIETARY (server)** | Dictate substitutes its own VAD/tail recovery (`recoverRealtimeTail`) — see §4/F. |

### F. The polish / cleanup stack (the largest FREE area)

All of the following are **plain string literals and small functions inside Gboard's APK** — reproducible
1:1, and they are what "Rambler-level punctuation, corrections and self-corrections" actually means.

| # | Rambler component | Exact location | Class | Dictate status |
|---|---|---|---|---|
| F1 | Cleanup prompt: `<system_role>`, `<rules>`, five numbered rules, `<absolute_constraints>` | `fcm.java` (`JetsonLiteHandler.g`), `elh.j(0.7f)` | **FREE** | **IMPLEMENTED** verbatim — `lib/dictate-core/.../prompts/RamblerDefaults.kt` (`CLEANUP_HEADER`, `CLEANUP_INSTRUCTIONS`, `CLEANUP_FOOTER`). |
| F2 | App-context block incl. chat-vs-other punctuation rule | `fcm.java` `{APP_CONTEXT}` | **FREE** | **IMPLEMENTED + WIRED** (this pass): the target app's label/package is now read and substituted. |
| F3 | Personal-dictionary block incl. **Zero Speech Policy** | `fcm.java` `{PERSONAL_DICTIONARY_CONTEXT}` | **FREE** | **IMPLEMENTED** (fed from `prefs.dictate.customWords`). |
| F4 | Custom-rules block incl. privilege drop + prompt-injection defence | `fcm.java` `{CUSTOM_RULES_CONTEXT}` | **IMPLEMENTED** (substituted when the caller supplies rules; Dictate has no per-app rule store yet — see §5.4). |
| F5 | Hinglish override (Roman script for Indic+English) | `fcm.java` `{HINGLISH_OVERRIDE_RULE}` | **FREE** | **IMPLEMENTED**, with a documented deviation: Gboard's own template has no placeholder for it, so in this build the rule never reaches the model. We append it as a cleanup item instead — Gboard's intent, and the only way Indic+English dictation behaves. |
| F6 | Surrounding-text stitching (last `\n\n` before / first `\n\n` after, whitespace collapsed) | `fcm.java` | **FREE** | **IMPLEMENTED** — `RamblerDefaults.surroundingWindow()`. |
| F7 | `temperature = 0.7` | `fcm.java` (`elh.j(0.7f)`) | **FREE** | **IMPLEMENTED** (this pass): `ChatRequest.ofUser(..., temperature = …)`, pinned only for the cleanup pass. |
| F8 | Confidence gate 0.5 + user-visible low-confidence warning | `mqh.aa`, `fbl` onVoiceText step 6 | **FREE (policy) / N/A (source)** | **PARTIAL**: the constant is recorded (`RamblerDefaults.CONFIDENCE_THRESHOLD`). Gemini Live does not expose a per-result confidence, so there is nothing to gate on for the realtime path; it can be applied to batch providers that return one (§5.5). |
| F9 | "Polished text" delivered *inside* the recognition stream | server route + `fcm` | **PROPRIETARY** | Dictate's cleanup is a second model call. That is the honest structural difference: Rambler's polish costs **zero** extra round trips online, ours costs one. |
| F10 | Voice commands — the three regexes and their order | `fcb.java` (`VoiceCommandClassifier`), patterns in `mqh.S/T/U` | **FREE** | **IMPLEMENTED** verbatim (anchored, case-insensitive, "empty pattern never matches" guard) and now **EXECUTED** — see §3.3. |
| F11 | `kng` action enum | `kng.java` — `UNKNOWN(0) … SEND(6), DELETE_CURRENT_ORATION(7), DELETE_LAST_SENTENCE(8), DELETE_LAST_WORD(9), DELETE_ALL(10) …` | **FREE** | Our `VoiceCommand` enum now uses Gboard's own constant names. |
| F12 | `handleBasicAction` — what is *actually executed* | `fbl.java:1048` (`z(kng, opn)`) | **FREE** | Verified this session: the method returns immediately unless `kngVar.ordinal() == 6`, i.e. **only `SEND` is ever executed**; `DELETE_LAST_SENTENCE` and `DELETE_ALL` are classified and then dropped with `"Unsupported basic action"` — the utterance is swallowed with no effect. See §3.3 for our deliberate deviation. |
| F13 | SEND's guard and action | `fbl.java:1052-1060` — `npc.a(editorInfo) != 4` then `opnVar.y(4)` | **FREE** | **IMPLEMENTED exactly**: only a field whose IME action is SEND gets `performEditorAction(IME_ACTION_SEND)` (`ImeDictationSink.performSendCommand`). |
| F14 | The offline path classifies commands too | `fcw.java:195-205` (`JetsonLiteHandler$3.onComplete`, orig. 414) | **FREE** | Confirms the classifier is not an online-only feature; our wiring is provider-independent. |
| F15 | Spoken-punctuation / capitalization / emoji / suffix models | `ofe.java:195`, `formatter/NativeFormatterImpl`, `InteractiveFormatter`, `rwz` (`StreamingTextSpanBuilder`) | **PROPRIETARY** | Native `libdictation_jni.so` models. This is the one part of "Rambler punctuation" that is a *model*, not a prompt. |
| F16 | `agentic_dictation_rewrite_compose_rules` (PII/formatting constraints) | `mqh.F` | FREE (value) | Recorded; not wired (needs a `compose`-mode detection we do not have). |

### G. Text insertion / finalisation

| # | Rambler component | Exact location | Class | Notes |
|---|---|---|---|---|
| G1 | Delete the previous preview length, write the new text, close the edit in a `finally`-equivalent | `fbl.java` insertion branch (`opnVarU.s(len, 0)` → `fao.f(...)` → `opnVarU.t()`) | **FREE (principle)** | Structurally different from Dictate's composing-region + committed-base bookkeeping, but equivalent in effect. Our model additionally survives a keyboard touch mid-dictation (`DictationPreviewState.committedBase`), which Rambler's bounded-region model does not need because Rambler never leaves its own region behind. **KEEP ours.** |
| G2 | `fao` — AgenticDictationTextEditor (insertion implementation) | `fao.java` (jadx-merged; locate by signature `f(InputConnection, nvq, gvs, oqw, int, String, boolean)`) | FREE (code) | Open question Q6 in the forensic report; only interesting as a cross-check of our sink. |
| G3 | Only FINALIZED spans **with** a VoiceAction are ever inserted | `fbl.java` onVoiceText step 2 | **FREE (policy)** | Dictate additionally shows a live grey preview by design (a Dictate feature the user asked for). |
| G4 | Safety blockage → abort | `fbz` (SafetyHelper `isSafetyBlocked`), `rrf.SAFETY_BLOCKAGE` | **PROPRIETARY (server verdict)** | The *server* decides; the client flag is free but meaningless without it. |
| G5 | Session caps: warn at 20 s, hard cap 300 s, countdown 10 s | `mqh` (`jetson_timeout_warning_seconds`, `max_session_duration_seconds`), countdown constant | **FREE** | Not implemented in Dictate (no cap at all). Suggested in §5.3. |

### H. Failure policy

| # | Rambler component | Exact location | Class | Notes |
|---|---|---|---|---|
| H1 | `max_server_retries = -1` (unlimited) | `mqh.f` | **FREE** | Dictate surfaces provider errors instead. Suggested in §5.2 (transient-failure retry, bounded by the session). |
| H2 | Quota refresh window 1 h + `hasQuotaRefreshedSinceLastDrain` | `mqh.v`, `fdl` (`AgenticDictationQuotaProtoStore`, orig. 129) | **FREE (policy)** | Only meaningful with a server quota; skip unless we add one. |
| H3 | User-visible message on quota drain ("…you can still use the offline version…") | string `0x7f1405f9`, `fcp.java:54` | FREE | Depends on B2. |
| H4 | `deactivate_on_voice_unavailable_field = true` | `mqh` | **FREE** | Dictate has partial equivalents (`isRawInputEditor` guards). |
| H5 | `agentic_dictation_enable_proactive_parallel_asr` | `mqh.z` — **zero consumers in this build** | **DEAD / DO NOT COPY** | Recorded precisely so nobody copies it as "Rambler's parallel-ASR trick". |
| H6 | `agentic_dictation_enable_seamless_fallback` | `mqh.x` — `false`, no consumer found | **DEAD** | Same. |

### I. Offline engine & the Live Transcribe question

| # | Item | Exact location | Class | Answer |
|---|---|---|---|---|
| I1 | Rambler's offline ASR | on-device model inside `com.google.android.tts`, feature id **238/294**, `DownloadModel` over gRPC, audio through a pipe FD | **PROPRIETARY / DEVICE-GATED** | It is **not SODA**. SODA (`SodaRecognizer`, `kid.java`) belongs to the *Traditional/Standard* voice path. |
| I2 | Live Transcribe & Sound Notifications | package `com.google.audio.hearing.visualization.accessibility.scribe`; the only Gboard references are `SignboardExtension` telemetry (`ajk.java:145,149`, `que.java:97`) and the fork's `openLiveTranscribeLanguageManager()` settings shortcut | **NOT AN ENGINE FOR GBARD** | It is a **language/model management UI**. Live Transcribe and Gboard have **different engines** that **share the `com.google.android.tts` language packs** — which is exactly what the fork's own settings text says. |
| I3 | Can Gboard download the offline models *without* Live Transcribe? | `LanguageDownloadManager` / `SpeechPackManager` inside Gboard | **YES** | The packs belong to `com.google.android.tts`; Live Transcribe is just a convenient front-end for them. |
| I4 | Can Dictate use that offline engine? | — | **Only via I1 (Q1)** | There is no public API. `SpeechRecognizer` (used by Dictate's Basic provider) resolves to Google's recognition service — the same *family* of on-device tech, but it is the traditional path, not Rambler's. |
| I5 | `libdictation_jni.so` payload check | `GboardDictationPayloadDetector` (`^lib/[^/]+/libdictation_jni\.so$`) | **PROPRIETARY** | Also the reason the fork can tell you *why* Advanced Voice Typing is unavailable on a device. |

### J. On-device LLM polish

| # | Item | Exact location | Class | Answer |
|---|---|---|---|---|
| J1 | AICore / Gemini Nano ("LEGION") cleanup, temp 0.7 | `elm.java`, `ekt.java` (`com.google.android.apps.aicore.aidl.ILLMService`); version gates ≥7 / V8 / variant 12 | **PROPRIETARY** | A separate privileged app. Callability by a non-Google caller is open question Q4. |
| J2 | The same prompt is used by the lite path | `fcm.java` | **FREE** | Already implemented (F1–F7). Only the *executor* is proprietary, not the text. |

---

## 3. What Dictate now implements (and how to test it)

Work from the previous passes (still in the tree): the prompt stack (F1–F6), prefs, strings, settings rows,
unit tests.

### 3.1 Restored the missing sink contract — **this was a build blocker**

`app/.../dictate/DictationSink.kt` had lost two things (a previous edit replaced the file and dropped
them):

* the **`interface DictationSink`** declaration itself (`AccessibilitySink`, `RecognitionSink` and
  `ImeDictationSink` all implement it, and `DictateController` uses it as the output seam) → the tree
  would not compile;
* the private **`releaseComposingOwnership()`** helper that the preview code calls on every
  finalize/clear path (it forwards to `EditorInstance.releaseComposingRegionOwnership()`).

Both are restored, with the interface carrying its full current method set.

### 3.2 App-aware punctuation (F2)

`postProcessTranscript()` now resolves the target app once per cleanup pass:

```kotlin
val (targetPackage, targetLabel) = withContext(Dispatchers.Main) {
    val editor = context.editorInstance().value
    val pkg = runCatching { editor.activeEditorPackage() }.getOrNull()
    ...pm.getApplicationLabel(pm.getApplicationInfo(p, 0))...
}
```

and passes both into `RamblerDefaults.buildCleanupPrompt(appLabel = …, packageName = …)`, which fills
Rambler's `ACTIVE APP: Name: … -- Package name: …` line — the switch that turns on *"Chat/Messaging/Social
Apps: casual punctuation, omit the final punctuation mark"* vs *"Other Apps: standard punctuation"*.
Read on the main thread (the editor's info belongs to the IME thread) and failure-tolerant: an
unresolvable package simply omits the block.

### 3.3 Voice commands — classified *and* executed (F10–F13)

* `RamblerDefaults.classifyVoiceCommand()` — the three verbatim patterns, Gboard's order, Gboard's flags.
  The enum now uses **Gboard's own names**: `SEND`, `DELETE_LAST_SENTENCE`, `DELETE_ALL`.
* `RamblerDefaults.lastSentenceToDelete(textBeforeCursor)` — the target of `clear`: the exact **suffix**
  of the text before the cursor starting after the previous sentence terminator (`.`/`!`/`?`/newline).
  It returns a real suffix, never a trimmed sentence, because the sink deletes only when the field still
  *ends with* that string. Deviations from Gboard are documented in-code (Gboard uses
  `BreakIterator.getSentenceInstance(locale)`; a terminator scan is locale-independent and testable).
* New sink methods `performSendCommand()` / `deleteCommandText(clearAll)` implemented by all three sinks:

| Sink | `performSendCommand()` | `deleteCommandText()` |
|---|---|---|
| `ImeDictationSink` | **Exact Rambler**: only when `activeInfo.imeOptions.action == SEND`, then `performEnterAction(SEND)` — the same guard and the same action as `fbl.handleBasicAction` | `clear all` → select-all + replace with empty; `clear` → delete the last sentence through `deleteLastText`, which re-checks the field |
| `AccessibilitySink` (floating button) | the service's own Enter/action attempt (a remote field's IME action is not readable) | same, with the whole field text as the "before cursor" stand-in; the service's own suffix check protects the user's edits |
| `RecognitionSink` (system voice input) | `false` | `false` — a calling app asked for text and gets text |

* `DictateController.finalizeAndCommit()` — the command check runs **before** any rewording, on the raw
  transcript, and only for a real field (`outputTarget != RECOGNITION_SERVICE`). On success the grey
  preview is taken down first (`runVoiceCommand`), the session state is reset, retained audio is
  discarded and the function returns without committing text.

**Two deliberate, documented deviations** (both in favour of not losing speech):

1. **`clear` / `clear all` actually act.** In this Gboard build they are classified and then swallowed
   (`handleBasicAction` → `"Unsupported basic action"`), i.e. saying "clear" does nothing at all. We
   implement the intent Gboard's own log strings state — *"Delete the last senetence in the input
   field."* / *"Delete everything in the input field."*
2. **A refused command falls through to a normal commit.** If the field has no SEND action, Gboard drops
   the utterance silently. We commit the words instead, because silently discarding a spoken sentence is
   exactly the failure mode both keyboards exist to avoid.

### 3.4 Cleanup temperature = 0.7 (F7)

`ChatRequest.ofUser(...)` gained an optional `temperature`, `requestRewordRaw(...)` forwards it, and only
the Rambler cleanup pass pins `RamblerDefaults.CLEANUP_TEMPERATURE` (0.7). Every other call site keeps the
provider default (`null` → field omitted), so nothing else's behaviour changes.

### 3.5 Tests

`lib/dictate-core/src/test/.../RamblerDefaultsTest.kt` (kotest `FunSpec`, the module's convention) now
also covers `lastSentenceToDelete`: single-sentence fallback, boundary-after-terminator, terminator runs
(`"Wait... then go"` → `"then go"`), newline boundaries, null cases, and the invariant that the returned
value is always a real suffix (the sink's delete contract).

Run in Android Studio / Gradle: `./gradlew :lib:dictate-core:testDebugUnitTest --tests '*RamblerDefaultsTest*'`

---

## 4. The 21 audit points — exactly why each one differs

Short form, all evidence in §2. "Same" means the behaviour is equivalent even where the code is not.

| # | Area | Rambler | Dictate | Why different | Verdict |
|---|---|---|---|---|---|
| 1 | Startup | extension activation, no init wait (`fbl.java:955` logging only) | provider session setup (key check, socket, keys) | Rambler binds a warm signed process; we open an authenticated socket | **Irreducible** (transport), but see §5.1 pre-warm |
| 2 | Mic timing | warm process, no DNS/TLS/auth | per-session TLS + key auth | same reason | Irreducible; pre-warm hides most of it |
| 3 | Audio path | pipe FD into the service | PCM frames over WebSocket | different transport | Irreducible |
| 4 | Model / pathway | one server route pinned by routing key; 238/294 offline | user-selected model + key | Rambler has **no model choice** — that is by design on Google's side | Irreducible; our flexibility is a feature, not a bug |
| 5 | API behaviour | gRPC bidi, spans with VoiceAction + confidence | provider REST/WebSocket; partials | different API surfaces | Irreducible |
| 6 | Limits | server quota, hourly refresh, unlimited retries | provider credits/quotas | different billing model | Client half is copyable (§5.2) |
| 7 | Online→offline switch | automatic in the factory | none (no offline engine) | the offline engine is Google's | Blocked on Q1 (personal-use probe) |
| 8 | Offline model | 238/294 in `com.google.android.tts` | none | proprietary | Blocked |
| 9 | Language selection | requested per session; packs from the TTS app | provider + `activeInputLanguage`, auto-switch to keyboard language (Stage 3) | different engine | Same else |
| 10 | Hinglish | override rule (broken placeholder in this build) | rule appended and active | we fixed Google's no-op | **Better than Rambler** here |
| 11 | Punctuation | prompt **+ local `dictation_jni` models** | prompt only | the local models are proprietary binaries | Partial; prompt-level parity achieved |
| 12 | Capitalization | same local models | provider's own | " | Partial |
| 13 | Correction | prompt rules (verbatim) | prompt rules (verbatim) | — | **Same** |
| 14 | Polishing | in-stream (server) / AICore (offline) | one extra model pass, opt-in, temp 0.7 | structural | **Different and documented** — F9 |
| 15 | Turn detection | server VAD; client half-closes | our VAD + `recoverRealtimeTail` | we must detect it ourselves | **KEEP ours** (it exists to prevent tail loss) |
| 16 | Finalisation | half-close triggers the final span | stop → finalize + tail reconciliation | equivalent effect | Same |
| 17 | Stopping | default message, closed flag blocks late frames | stop flag + session teardown | same idea | Same |
| 18 | Text insertion | bounded delete + write in one edit transaction | composing region + committed base | different but equivalent | **KEEP ours** (survives mid-session keyboard touches) |
| 19 | Latency | see 1–3 | see 1–3 | transport | Irreducible |
| 20 | Extra API calls | none (polish is in-stream, offline polish is local) | **one extra call when the cleanup toggle is on** | we have no in-stream polish | Opt-in, off by default; documented |
| 21 | Extra Dictate processing | — | auto-formatting, prompts, paragraph split, mappings, stats, history, tail recovery | all user-facing features that Rambler has no equivalent of | **KEEP**, all opt-in or off the critical path |

---

## 5. Free wins not implemented yet (prioritised backlog)

### 5.1 Pre-warm the realtime session (B4, C5)
Rambler's debounced model-availability check (`smx.java` + `snn`) exists so nothing stalls when the user
actually starts talking, and the channel keepalive is 60 s (`aaqr.k`). Dictate equivalents:
open/keep the Gemini Live socket while the keyboard is up (or in the first seconds after the mic view
opens), and send a WebSocket ping on an interval so the TLS path stays warm. **Pure latency win, no
protocol change.** Suggested as the next code change.

### 5.2 Transient-failure retry (D2, H1)
`max_server_retries = -1`. Dictate currently surfaces a provider error. A bounded retry (e.g. 2 attempts
with a short backoff, inside the same session, before the error reaches the UI) mirrors Rambler's
unlimited-retry feel without risking an infinite loop.

### 5.3 Session caps + countdown (G5)
`jetson_timeout_warning_seconds = 20`, `max_session_duration_seconds = 300`, countdown 10 s. Dictate has
no cap: a forgotten session records until the user notices. A warning chip + auto-stop is a UX-level
copy with no engine dependency.

### 5.4 Per-app custom rules (F4)
The `<custom_rules>` block is substituted only when rules are supplied; Dictate has no per-app rule store
yet. A small per-package rule table would light this up (and is the natural home for "always capitalize
X in Slack").

### 5.5 Confidence gate for batch providers (F8)
`0.5` is recorded. Realtime providers do not report confidence, but batch transcription responses often
do — gate there and surface the same low-confidence warning Gboard shows.

### 5.6 `agentic_dictation_rewrite_compose_rules` (F16)
Read the value and decide whether the PII/formatting constraints are worth wiring to the prompt (needs
`compose`-mode detection).

### 5.7 Offline tier (B2, I1, I4) — **personal-use probe only**
The only honest path: a tiny test build that binds
`com.google.android.tts`/`GoogleAsrService` and answers Q1 (does the service verify the caller?). If it
answers at all on a device with Speech Services, the offline tier becomes a real option for *personal*
builds. There is no public API, so nothing here can ship in a distributed app.

---

## 6. Proprietary register — what cannot be reproduced, and how to talk about it

**Category 1 — not usable at all (server-side, credential-bound):**
Rambler's online ASR + in-stream polish (Gemini voice-edit route + routing key), the Zwieback→`auth_token`
credential from GMS Core, the server quota and the SafetyHelper verdict.

**Category 2 — usable only when Google's own app is installed (device-gated, personal builds only):**
`GoogleAsrService` in `com.google.android.tts` (online *and* the 238/294 offline models), the
`libdictation_jni.so` formatter models, AICore `ILLMService` (Q4). Nothing here can be redistributed with
Dictate: it is neither our code nor our permission to grant. Binding it from a *distributed* app is also
a Play-policy risk.

**Category 3 — free but Google-authored text (the prompt):** functionally reproducible, but the wording
is Google's. Fine for private use; for a public repo the recommendation stands: keep the structure,
re-word, and expose the prompt as a user-editable field (`RAMBLER_PARITY_IMPLEMENTATION.md` §4 already
flags this).

**What a public Dictate release may legitimately contain:** everything in §2 marked FREE / FREE-BY-
REIMPLEMENTATION — i.e. the entire *behavioural* specification of Rambler: prompt structure and rules,
temperature, app-aware punctuation, Zero-Speech behaviour, Hinglish rule, surrounding-text stitching,
the three voice-command patterns and their execution, session/retry policy, pre-warm/keepalive strategy,
and the audio/turn handling principles (stream every frame, half-close to finalize, never accumulate).

---

## 7. One-paragraph answer to "can Dictate become Rambler?"

Dictate can now produce **Rambler-style polished text** and **Rambler's voice commands** with Rambler's
own rules, wording, temperature and app-awareness — the parts of Rambler that are plain code. It cannot
become Rambler's *engine*, because that engine is a signed Google app reached over gRPC-over-Binder with a
platform-issued pseudonymous token, with the offline model and the punctuation/capitalization models
shipped inside packages Dictate does not own. Everything in §5 is a genuine, available improvement; §6 is
the wall, and it is a wall of ownership, not of skill.
