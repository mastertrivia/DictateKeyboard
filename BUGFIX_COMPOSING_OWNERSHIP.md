# Bugfix: duplicated text during streaming preview ("Hello Hello hello there hello there my …")

**Date:** 2026-09-24
**Affected build:** any build with Basic Voice Typing or AI realtime preview enabled **and** the
"Real-time transcription" setting set to **"Show the text while I speak"**.
**Unaffected:** "Show the text only when I stop" (the preview never touches the field while
speaking, so the buggy path never runs).

## 1. The symptom

With "Show the text while I speak" selected, speaking one short sentence produces:

```
Spoken :  Hello there, my name is Prateek Singh
Typed  :  Hello Hello hello there hello there my hello there my name
          hello there my name hello there my name is hello there my
          name is Pratik Singh.
```

Every intermediate partial result of the recognizer is **appended** to the field instead of
replacing the previous one. The bug reproduces with Basic Voice Typing *and* with Google Gemini
realtime — anything that streams a live preview.

## 2. Root cause

Both writers stream their growing transcript through the field's **composing region**
(`InputConnection.setComposingText`): each partial replaces the region wholesale, nothing is
permanent until the session ends. That is the same mechanism HeliBoard's voice typing uses, and
it is the correct design — the provider keeps correcting earlier words, so committed text would
have to be un-typed later.

The bug is that **the keyboard itself kept finishing that region**. FlorisBoard's editor
reconciles its internal state after every selection update
(`AbstractEditorInstance.handleSelectionUpdate`, invoked from `FlorisImeService.onUpdateSelection`
for *every* text change — including the preview's own writes). After reconciling it re-imposes its
own word-composing region:

```kotlin
if (content.composing != composing) {
    ic.setComposingRegion(content.composing)
}
```

Per Android's InputConnection contract, `setComposingRegion` (and `finishComposingText`)
**finalizes whatever composing region is currently live** before claiming a new one. So the cycle
was:

1. partial #1 arrives → `setComposingText("Hello")` — grey region, 5 chars
2. the system reports the selection update caused by step 1
3. `handleSelectionUpdate` → `setComposingRegion(...)` → **"Hello" becomes permanent text**
4. partial #2 arrives → `setComposingText("Hello there")` starts a *fresh* region **after** the
   committed "Hello" → field now shows "Hello Hello there"
5. repeat for every partial → quadratic accumulation of every revision ever shown

"Show the text only when I stop" never writes a composing region, so step 3 had nothing to
finalize — hence no bug on that setting.

## 3. The fix — composing-region ownership

This exact bug existed in the reference HeliBoard fork while its continuous voice engine was
being integrated, and was fixed there with an ownership guard in `LatinIME.onUpdateSelection`:

> *"While continuous Voice is listening, the dictation engine owns the editor's composing region.
> Skip HeliBoard's selection/suggestion machinery so it cannot finish or re-claim that region
> between partial results (which would bake the grey text as permanent and break the engine's
> replace-in-place correction)."*

The same concept, ported to FlorisBoard's architecture (4 files, all in `app/`):

| File | Change |
|---|---|
| `ime/editor/AbstractEditorInstance.kt` | New `composingRegionExternallyOwned` flag + `claimComposingRegionOwnership()` / `releaseComposingRegionOwnership()`. All three `setComposingRegion` sites inside the selection-update machinery are skipped while the flag is set. User-typing paths are untouched — they don't run between partials, and the flag is only set while a dictation session is live. |
| `dictate/BasicVoiceHost.kt` | Exposes `claimComposingOwnership()` / `releaseComposingOwnership()` for the engine host seam. |
| `dictate/DictateController.kt` | Claims ownership when a basic-voice session starts; releases it on natural stop (`onBasicVoiceStopped`), on cancel (`stopBasicVoice(cancel = true)`). |
| `dictate/DictationSink.kt` | Claims ownership on the first grey preview write (`applyDictationDiff`); releases it in `commitDictationFinal` and `clearDictationPreview` — every path that ends a preview lifecycle. The stale per-instance `previewComposingLength` was replaced by the editor's durable flag (sink instances are created per call, so instance state could not survive between updates anyway). |

Behavior after the fix: the grey region stays owned by the streaming writer for the whole
session, every partial replaces it in place, and the stop/finalize turns it permanent in one
call — visually identical to before, but nothing is ever baked in mid-stream. Both Basic Voice
Typing and every AI provider's realtime preview are covered by the same flag.

## 4. Why the preview is grey temporary text (semantics)

Questions answered for future readers:

1. **Does grey simply ensure earlier text can still change?** Yes — that is its entire purpose.
   The composing region is Android's standard "this text is still being edited by the IME" mark.
   While text lives there, the writer may replace it wholesale on every update at zero cost, and
   the app renders it with the composing (grey/underline) style. Speech engines revise earlier
   words as more context arrives; a committed copy of a revision would be wrong one update later.

2. **Is there a "temporary buffer vs commit" distinction to identify?** There is now, explicitly:
   `composingRegionExternallyOwned` is the flag that says "a temporary buffer is live and someone
   owns it". While it is set, exactly one writer (the dictation session) may touch the region and
   the editor's own region machinery stands down. The commit points are precisely the release
   points: `commitDictationFinal` (stop / finalize), `clearDictationPreview` (cancel / fallback),
   and the basic-voice session teardown.

3. **Does it continuously improve and become final on stop?** Yes — every partial replaces the
   region in one `setComposingText` call (no diff arithmetic, no drift), the provider keeps
   refining it until the user stops, and `commitDictationFinal` swaps the whole region for the
   final text in one `commitText`. One frame: grey → black, temporary → permanent. This is also
   what makes Stage 2's tail-only recovery clean: the recovered final replaces the same region
   atomically.

## 5. Verification: is Basic Voice Typing the same engine as Gboard's?

Short answer: **it binds to the same recognizer service Gboard uses; it is not a re-implementation
and not a worse one.** Evidence from the ported code (verbatim from the fork, which took it from
the Google app's behavior):

- `VoiceController.resolveRecognitionService()` explicitly resolves
  `com.google.android.googlequicksearchbox/com.google.android.voicesearch.serviceapi.GoogleRecognitionService`
  — the **Google app's own recognition service** — and binds `SpeechRecognizer` to it. This is
  the same service the Google keyboard's voice typing talks to.
- The recognition intent uses the same tuning Gboard's dictation uses: `LANGUAGE_MODEL=free_form`,
  `PARTIAL_RESULTS=true`, `MAX_RESULTS=1`, `DICTATION_MODE=true`
  (`VoiceController` lines 177–182).
- What Google calls **SODA** (the on-device spiral-recognizer that powers hotword/ASR reflexes)
  is the *server-side implementation detail of that same service* on modern devices. Our client
  does not (and should not) contain SODA itself — it consumes the service that SODA powers. So:
  - **Accuracy:** same engine, same server round-trip → same accuracy as Gboard voice typing by
    construction. Differences can only come from the intent extras above, which match.
  - **Speed:** same pipeline (on-device endpointing + streaming server ASR) → same first-word
    latency. The engine additionally streams partials into the field immediately, like Gboard.
  - **Continuity:** the engine's restart loop keeps the recognizer alive indefinitely (no
    fixed-time cutoff), which Gboard's voice typing does *not* do — this is a deliberate
    improvement inherited from the fork.
- The only places our build previously diverged from the fork were UI-host concerns — and the
  one real regression (this bug) was the missing ownership guard, now ported from the fork's own
  fix.

## 6. Test checklist

1. Provider = **Basic Voice Typing**, Real-time transcription = **Show the text while I speak**.
   Speak a sentence → single copy of the sentence in grey, turning black on stop. Speak for a
   minute → still exactly one copy.
2. Same with **Google Gemini** realtime → same behavior.
3. Slide-to-cancel mid-speech → all grey text removed, nothing left behind.
4. Stop with speech in flight → text commits once; tail recovery (Stage 2) still replaces the
   preview atomically.
5. Normal typing immediately after a session → composing/underline behavior identical to a
   keyboard that never dictated (ownership released).
