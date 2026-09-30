# Custom features (this fork)

This repository is one application:

```
DevEmperor/DictateKeyboard (upstream)
        +
this fork's custom layer
        =
main  (the APK you build)
```

`origin` is `https://github.com/mastertrivia/DictateKeyboard`.
`upstream` is `https://github.com/DevEmperor/DictateKeyboard`.

Sync later with:

```
git fetch upstream
git merge upstream/main
```

Do not squash custom history. Conflicts on files listed below must be resolved by
choice (keep custom, take upstream, or combine) — never by silently dropping
custom work.

## Custom features retained on main

### 1. Basic Voice Typing

HeliBoard speech-recognizer engine ported as the first transcription provider.
Uses the phone's `SpeechRecognizer`, grey composing preview, no API key.

Isolated engine (do not rewrite unless the port must change):

- `app/src/main/java/helium314/keyboard/voice/` (12 Java files)
- `app/src/main/kotlin/dev/patrickgold/florisboard/dictate/BasicVoiceHost.kt`

Upstream files this feature touches:

- `lib/dictate-core/.../provider/ProviderConfig.kt` (`BASIC_RECOGNITION_SERVICE`)
- `lib/dictate-core/.../provider/ProviderRegistry.kt` (preset `basic`, first)
- `lib/dictate-core/.../provider/OpenAiCompatibleClient.kt` (defensive branches)
- `app/.../dictate/provider/ProviderListing.kt` (always listed and pickable)
- `app/.../dictate/DictateController.kt` (mic start/stop/cancel routing)
- `app/.../dictate/DictationSink.kt` (grey composing preview)
- `app/.../settings/dictate/DictateProvidersScreen.kt`
- `app/.../importer/ImportTranscriber.kt`
- `app/.../importer/TranscribeShareScreen.kt`
- `app/.../wear/PhoneTranscriber.kt`
- `app/src/main/AndroidManifest.xml` (`RecognitionService` queries, Bluetooth, wake lock)
- `app/src/main/res/values/strings.xml`

Docs: `BASIC_VOICE_TYPING.md`

### 2. Gemini realtime tail recovery

Tune Gemini Live VAD to lock phrases during speech. On stream failure, transcribe
only the uncovered tail instead of re-uploading the whole recording.

- `lib/dictate-core/.../provider/RealtimeTranscription.kt` (`coveredAudioSeconds()`)
- `lib/dictate-core/.../provider/RealtimeClient.kt` (VAD + coverage)
- `app/.../dictate/DictateController.kt` (`recoverRealtimeTail()`)

Docs: `STAGE2_REALTIME_PARITY.md`

### 3. Composing ownership + Rambler / voice-command extras

Grey composing preview ownership so keyboard keypresses cannot bake partials, plus
optional Rambler cleanup / voice-edit / voice-command prefs.

Docs: `BUGFIX_COMPOSING_OWNERSHIP.md`, `RAMBLER_PARITY_IMPLEMENTATION.md`

## Not custom (upstream product)

These are DevEmperor's keyboard/product features. They stay on `main` from
`upstream/main`. Older zip edits that duplicated them are **not** applied:

- Scan Text / ML Kit OCR (`dictate/scan`, `libs.mlkit.text.recognition`)
- Keyboard / IME / Smartbar / emoji / dictionary work after the zip snapshot
- Space-bar provider switch, follow-keyboard language, and related UI

The zip is older. Keyboard features in it that the developer already shipped are
ignored so they cannot conflict.

## Fork-only build constraint

- Native ABI: `arm64-v8a` only

## Git layout

| Ref | Meaning |
|---|---|
| `upstream/main` | DevEmperor/DictateKeyboard |
| `origin/main` | This application: upstream + custom layer |
| `260927-feat-user-zip3-full` | Parked older zip overlay (includes developer overlaps; not built) |
| `[CUSTOM] ...` commits | Identifiable custom work |

CI: `.github/workflows/build-apk.yml` ("Sync and Build") is manual only: it
merges `DevEmperor/DictateKeyboard` into fork `main`, then builds a signed
arm64-v8a-only APK. No schedule.
