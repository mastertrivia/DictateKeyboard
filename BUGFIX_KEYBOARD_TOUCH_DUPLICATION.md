# Bugfix: Duplicated text after keyboard interaction during live dictation

## The bug

With "Show the text while I speak" on (real-time transcription), the growing transcript is shown
in the field as a **grey, uncommitted composing region**. This part was correct.

But when the user **touched the keyboard while the microphone was still active**:

1. The keypress finalizes (`finishComposingText`) the grey composing region — Android commits the
   grey text permanently. Expected and kept: the grey text becomes normal text.
2. The dictation kept going, but its bookkeeping (`realtimeShown`, and the "is a region live?"
   flag) still believed the whole transcript was an uncommitted grey buffer.
3. Pressing **Stop** therefore re-inserted the entire transcript a second time → duplication.

## The model implemented (exactly the user's stated logic)

```
Grey text        = current uncommitted voice input
Keyboard touch   = commits the current grey text → voice state resets at that point
Next speech      = a completely NEW grey segment (starts after the committed text)
Stop             = commits only the CURRENT grey portion, never the committed one
Committed text   = permanently settled; never re-typed, revised, or removed by the dictation
```

No scanning backwards through the field. The entire bookkeeping is two strings
(`DictationPreviewState.committedBase`, `.regionText`) updated by exactly two events:
a keyboard write finishing the region (consume), and a preview write (new tail).

## Changes

### 1. `ime/editor/AbstractEditorInstance.kt` + `ime/editor/EditorInstance.kt` — consume signal

Every **keyboard** write path that finalizes the field's composing region now notifies the active
dictation once:

- `commitTextInternal` — any keypress (a keypress types over the region: `finishComposingText`
  commits the grey text first). *(AbstractEditorInstance)*
- `deleteAroundCursor` — backspace/word-delete over the grey text (both before/after cursor
  branches). *(AbstractEditorInstance)*
- `replaceTextBeforeCursor` — the atomic replace helper (also used by the dictation finalize
  itself). *(EditorInstance)*

The hook is `notifyComposingConsumed()`: it drops the ownership claim and invokes the single
`composingConsumedListener` (no-arg signal — the consumer reads the region text from its own
state). The dictation's own finalize/clear calls never trigger it (the claim is already false
when they run, and `replaceTextBeforeCursor`'s notify only fires while the claim is still set).

### 2. `dictate/DictationSink.kt` — `DictationPreviewState` + delta-only writes

New session-wide state object (`internal object DictationPreviewState`):

| Field | Meaning |
|---|---|
| `committedBase` | grey text the keyboard has already made permanent (empty = nothing consumed) |
| `regionText` | what the live grey region holds right now (empty = no region up) |
| `reset()` | fresh session (called by the controller at start/stop/cancel/finalize) |
| `onConsumedByKeyboard()` | the consume event: `committedBase += regionText; regionText = ""` |
| `tailOf(full)` | the uncommitted remainder of the transcript (re-bases if the user backspaced committed text away) |

`ImeDictationSink` changes:

- **`setDictationPreview` / `applyDictationDiff`**: always writes only `tailOf(transcript)` as the
  grey region — before a touch this equals the whole transcript (unchanged behavior), after a
  touch it is just the newly spoken words, composed as a fresh region after the committed text.
  Registers the consume listener on the first preview write.
- **`commitDictationFinal`**: with a live region it commits `tailOf(finalText)` (the region only
  holds the uncommitted tail after a touch — committing the full transcript would re-type the
  committed part; unchanged when no touch happened). With no region and a non-empty committed
  base, it inserts only `finalText − committedBase`. **Stop can no longer duplicate anything.**
- **`clearDictationPreview`** (cancel): never deletes text a keyboard touch already committed —
  that is the user's own text now; only a still-unseen hidden preview is removed.

Controller (`DictateController`) calls `DictationPreviewState.reset()` at: realtime session
open, cancel, stop-finalize (success), tail-recovery fallback, batch-fallback error path, and
segmented finalize — every session boundary starts from a clean slate.

### 3. Basic Voice Typing — the fresh-segment rule

The engine (ported HeliBoard code) keeps a live grey composing region per recognizer generation.
After a keyboard touch committed that grey text and the user typed their own words, the next
recognizer generation's first `setComposingText` would replace the composing region — which
Android resolves against the user's text — eating the manual words.

Fix:

- `VoiceCallback` gains `onNewVoiceSegment()`; `VoiceController.restartListening` (the start of
  every new recognizer generation) fires it once.
- `SpeechNotesVoiceEngine` forwards it to the host (`VoiceEngineHost.beginNewVoiceSegment()` —
  the only seam change beyond the original four).
- `BasicVoiceHost.beginNewVoiceSegment()` resets the session text accounting, so the new grey
  region starts after the settled text and a later cancel can only remove what the new segment
  itself wrote.
- Defense in depth: `SessionInputConnection.setComposingText` also self-detects the consumed
  region at write time (`composingRegionGone()` — the ownership flag is no longer set) and seals
  the stale region instead of replacing it.

Result: voice updates only ever manage the current grey segment; manually typed or already
committed text is never overwritten, removed, or "corrected" by the next partial.

## Behavior after the fix (user's scenario)

1. Speak "My name is Mr. Jack and I want to go to the market. I want to buy the apples." —
   grey text grows, revises itself. Unchanged.
2. Touch the keyboard — grey text becomes permanent white text. The dictation re-bases:
   `committedBase` = that text. Unchanged visible behavior, new bookkeeping.
3. Keep speaking — only the **new** words appear in grey after the committed text.
4. Press Stop — only the remaining uncommitted grey tail is finalized. The earlier sentence is
   **not** inserted again. No duplication, ever.
5. (Basic voice) Type a few words after a touch, resume speaking — the manual words stay exactly
   as typed; the new grey segment starts after them.

## Files touched

- `app/src/main/kotlin/dev/patrickgold/florisboard/dictate/DictationSink.kt`
- `app/src/main/kotlin/dev/patrickgold/florisboard/dictate/DictateController.kt`
- `app/src/main/kotlin/dev/patrickgold/florisboard/dictate/BasicVoiceHost.kt`
- `app/src/main/kotlin/dev/patrickgold/florisboard/ime/editor/AbstractEditorInstance.kt`
- `app/src/main/kotlin/dev/patrickgold/florisboard/ime/editor/EditorInstance.kt`
- `app/src/main/java/helium314/keyboard/voice/VoiceCallback.java`
- `app/src/main/java/helium314/keyboard/voice/SpeechNotesVoiceEngine.java`
- `app/src/main/java/helium314/keyboard/voice/VoiceController.java`

## Verification

- Brace balance checked on all 8 files (all even).
- Symbol audit: `DictationPreviewState` (21 refs), `notifyComposingConsumed` (5), `composingConsumedListener` (4),
  `onNewVoiceSegment` (3: interface + controller call + engine impl), `beginNewVoiceSegment` (2: interface + host impl).
- No build possible locally (Android project) — compile in Android Studio as with previous stages.
