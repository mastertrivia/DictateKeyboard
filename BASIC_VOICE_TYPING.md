# Basic Voice Typing — HeliBoard engine ported 1:1 into Dictate

**Source of truth:** `mastertrivia/HeliBoard-AI-Voice-Typing` (your own fork), `app/src/main/java/helium314/keyboard/voice/`
**Target:** latest `DictateKeyboard` main (`f6cda452` lineage)
**Result:** Dictate now has the exact normal voice typing your HeliBoard fork has — same engine code, same restart loop, same grey temporary text, same never-stops-until-you-tap-stop behavior — as the **first** entry in the Transcription provider list, named **“Basic Voice Typing”**.

This is the reflex layer you asked to have in place first, so the later SODA/server stage can lean on it. Nothing in the engine logic was “fixed” or redesigned — the files were copied verbatim and only *wired* into Dictate’s host.

---

## 1. What was copied — verbatim, byte-for-byte

All twelve engine files now live at `app/src/main/java/helium314/keyboard/voice/`:

| File | Role (identical to HeliBoard) |
|---|---|
| `SpeechNotesVoiceEngine.java` | The voice engine proper: session state machine, restart loop |
| `VoiceController.java` | System `SpeechRecognizer` wrapper: partials, finals, errors, restarts |
| `VoiceCallback.java` | Callback interface between controller and engine |
| `TextBoundary.java` | Word/whitespace boundary helpers for trimming |
| `TextTrim.java` | Spoken-punctuation trimming and capitalization fixes |
| `AudioStreamHelper.java` | Audio stream muting while the recognizer listens |
| `BluetoothScoManager.java` | SCO headset routing for Bluetooth mics |
| `VoiceSounds.java` | Start/stop/error tones |
| `LanguageFlag.java` | Locale → flag for the language indicator |
| `LanguageTable.java` | Recognizer language listing |
| `SmartLog.java` | The fork’s logging |
| `TextBoundary.java`/`TextTrim.java` | (listed above) |

**The only edits anywhere in these files** are in `SpeechNotesVoiceEngine.java`:
1. The package/`LatinIME` references were re-pointed at a new host interface (below).
2. `SettingsActivity` intent → replaced by a host callback (`openVoiceSetup()`).

That is it. Every algorithm, constant, restart threshold, timeout, mute behavior and commit path is untouched — you can diff the two files against your fork and see only those seams.

## 2. The one seam the port needed: `VoiceEngineHost.java`

HeliBoard’s engine talked to `LatinIME` directly. Dictate is a different app, so the fork now depends on a three-method interface instead — **`VoiceEngineHost.java`**, copied into the same package:

```java
public interface VoiceEngineHost {
    /** The live InputConnection the engine writes into (wrapped for session accounting). */
    InputConnection currentInputConnection();
    /** The engine reached a terminal state (stopped/errored/crashed): host tears down. */
    void onVoiceEngineStateChanged(SpeechNotesVoiceEngine.VoiceUiState state);
    /** The fork’s “switch to AI voice” escape hatch: stop any AI dictation first. */
    void stopAiVoiceForNormalVoiceStart();
    /** Opens the keyboard’s own settings (HeliBoard opened SettingsActivity). */
    void openVoiceSetup();
}
```

Everything else the engine touches — `Context`, `SpeechRecognizer`, `AudioManager`, tones — needs nothing from HeliBoard.

## 3. The Kotlin glue: `BasicVoiceHost.kt`

`app/src/main/kotlin/dev/patrickgold/florisboard/dictate/BasicVoiceHost.kt` implements `VoiceEngineHost` for Dictate and does exactly three jobs:

1. **InputConnection plumbing.** Returns `FlorisImeService.currentInputConnection()` wrapped in `SessionInputConnection`, a thin mirroring layer that keeps a running count of what the engine wrote (`sessionText` + `composingLen`). This exists so **cancel can remove exactly what the engine put in the field** — HeliBoard got this for free from its editor; Dictate’s field writes go through a different path, so the host accounts for them explicitly.
2. **State callbacks.** `onVoiceEngineStateChanged(IDLE)` → `onEngineStopped()` → the controller tears the bar down, exactly like a normal recording end.
3. **Tones + SCO.** `VoiceSounds`/`BluetoothScoManager` are constructed with the app context and just work — no HeliBoard resources are referenced anywhere (verified: zero `R.*` references in the ported files).

## 4. How Dictate reaches it (the wiring)

**`TranscriptionApi.BASIC_RECOGNITION_SERVICE`** — new enum value in `ProviderConfig.kt`, documented as “routed by the dictation flow, never sent through the HTTP client”. `OpenAiCompatibleClient` throws if it is ever asked to build a client for it (defensive), and its connectivity check short-circuits to “connected”.

**`ProviderRegistry.BASIC`** — new preset, `id = "basic"`, `apiKeyUrl = null` (so `requiresCredential` is false — no key is ever demanded), **first in `presets`**. The picker and the provider list both sort it to position 0, before “On-device (offline)”, as you specified.

**`DictateController`** — the mic entry point branches first:

- `onMicClick` / hold-to-record / auto-start: `if (isBasicVoiceProvider()) startBasicVoice(context) else startRecording(context)`
- `startBasicVoice` refuses politely (neutral notice) when the floating overlay is the target — the engine writes through the live keyboard InputConnection, which the overlay doesn’t have. Files: `ImportTranscriber`, `PhoneTranscriber` (watch), `TranscribeShareScreen` each refuse with a clear message for the same reason.
- `stopAndTranscribe` → `stopBasicVoice(cancel = false)` → `engine.stopIfListening()` → the engine flushes, reports IDLE, the bar goes away. Cancel/slide-away → `discardSessionNow()` first (removes every character the engine wrote, then blocks late callbacks) and the existing cancel animation state machine stays untouched.
- Screen-off / capture-lost / interrupt routes all funnel into the same `stopBasicVoice`.

**Manifest** — the one invisible thing that silently breaks this feature on Android 11+:

```xml
<queries>
    <intent><action android:name="android.speech.RecognitionService"/></intent>
</queries>
```

plus `BLUETOOTH_CONNECT` (SCO manager, Android 12+) and `WAKE_LOCK` (long sessions). Without the `<queries>` entry the service lookup returns null and voice typing “does nothing” — it is in there.

## 5. The temporary-commit change you asked for (streaming preview)

You said: *“On Dictate the real-time text commits permanently as you go; on HeliBoard it sits in the box greyed-out and only darkens when you stop. Change it.”* Done — in `DictationSink.kt`:

- `setDictationPreview` now writes the streaming text as a **composing region** (`InputConnection.setComposingText`) with a grey span (`0xFF888888`) — the same mechanism, and the same grey, as the ported engine. The provider can keep correcting earlier words while you speak; nothing is permanent.
- `commitDictationFinal` calls `commitText(finalText)` once — which by InputConnection contract *replaces* the composing region — so the whole grey preview turns into the final text in one frame when you stop.
- `clearDictationPreview` (cancel) ends the region with `setComposingText("")` — one call, no per-character deletes, no ANR on long dictations.

The old committed-preview behavior remains for the “Show the text only when I stop” mode (nothing is shown until stop, then it’s inserted plainly), so that setting keeps its meaning.

## 6. How to use it

1. Build & install this tree (Android Studio → `app` → Run, or `./gradlew assembleDebug`).
2. Settings → AI providers → **Transcription** → choose **“Basic Voice Typing”** (top of the list). No key, no model, no setup — it uses the phone’s own Google speech service.
3. Tap the mic. Text appears **grey** in the field while you speak and turns **final** when you tap stop — exactly like your HeliBoard fork. It never times out on its own; it runs until you tap stop.
4. Bluetooth headset: the SCO manager handles routing, as on HeliBoard.

## 7. Files changed (complete list)

**New — engine (verbatim copy):** `app/src/main/java/helium314/keyboard/voice/` — 12 files listed in §1.
**New — port seam:** `VoiceEngineHost.java`, `app/src/main/kotlin/dev/patrickgold/florisboard/dictate/BasicVoiceHost.kt`
**Modified — provider layer:** `ProviderConfig.kt` (+enum), `ProviderRegistry.kt` (+preset, first), `OpenAiCompatibleClient.kt` (2 defensive branches)
**Modified — controller:** `DictateController.kt` (mic entry, start/stop/cancel routes, provider resolution)
**Modified — output:** `DictationSink.kt` (grey temporary composing preview)
**Modified — settings UI:** `DictateProvidersScreen.kt` (BASIC first, summary, note dialog)
**Modified — guards:** `ImportTranscriber.kt`, `PhoneTranscriber.kt`, `TranscribeShareScreen.kt`
**Modified — manifest/strings:** `AndroidManifest.xml` (queries + 2 permissions), `values/strings.xml` (4 strings)

Nothing else was touched: the recording pipeline, the AI providers, the realtime Gemini path and the long-form splitter are all untouched.
