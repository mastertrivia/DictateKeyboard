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

### 3. Cloud admin list (ZIP)

Slow-request marking on the cloud admin request list, as shipped in the ZIP.

- `cloud/src/admin/index.ts`
- `cloud/src/admin/page.ts`
- `cloud/src/admin/tax.ts`
- `cloud/src/notify/rules.ts`

## Not custom (upstream v6.3.0 product)

The ZIP is based on upstream **v6.3.0**. These are upstream, not fork exclusions:

- Scan Text / ML Kit OCR (`dictate/scan`, `libs.mlkit.text.recognition`)
- Native ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`

Older fork commits that stripped OCR / restricted to arm64-only are historical.
`main` now follows the ZIP, which includes those upstream features.

## Git layout

| Ref | Meaning |
|---|---|
| `upstream/main` | DevEmperor/DictateKeyboard |
| `origin/main` | This application: upstream + custom layer |
| `[CUSTOM] ...` commits | Identifiable custom work |

CI: `.github/workflows/build-apk.yml` can build `main` via `workflow_dispatch`
with `source_ref=main` (unreleased APK of this application).
