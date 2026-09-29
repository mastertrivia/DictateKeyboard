/*
 * Host adapter between Dictate's controller and the ported system-voice engines
 * (helium314.keyboard.voice, copied verbatim from the HeliBoard fork). It provides the
 * operations the engines' host seam (VoiceEngineHost) needs: the live InputConnection,
 * engine-state callbacks (tones + controller teardown), the "stop other voice features" hook
 * (system voice and AI dictation never run at the same time, so there is nothing to stop), and
 * the settings deep link.
 *
 * Two of those carry the live indicator. The engine's state is translated into the controller's
 * vocabulary ([onEnginePhase]) so the caption and the audible tones can never disagree about whether
 * the microphone is hearing anything, and the recognizer's RMS — a value the engine has always been
 * handed and HeliBoard threw away — is passed on ([onEngineLevel]) so a basic-voice dictation's bars
 * move with the voice instead of on a timer. Neither touches recognition: both are read-only taps on
 * values that already existed.
 *
 * Both system engines — Basic voice typing (SpeechNotesVoiceEngine) and Google Live Transcribe
 * (LiveTranscribeEngine) — are hosted here unchanged; what they differ in is the recognizer request
 * they build, not how their text reaches the field. That is why this host is engine-agnostic and why
 * the preview rule below covers both.
 *
 * It also owns the session's *text* accounting: every write an engine makes to the field (grey
 * composing updates and committed segments alike) is mirrored into [sessionText], so a cancelled
 * dictation can be removed exactly — the guarantee the engines' original host had through its editor
 * model, reached here through a counting InputConnection wrapper.
 *
 * **Finalizing does not ask the field to replace the composing region.** Android documents
 * `commitText` as "replaces the current composing region", and the engine relies on that; but a field is
 * free to have finished that region on its own (many do, the moment anything else touches the editor),
 * and then `commitText` *appends* instead. The grey copy stays where it was and the finished sentence
 * lands after it — the reported "the same text gets pasted again when I stop". So the host takes the
 * region away explicitly, with the keyboard editor's own atomic swap
 * ([dev.patrickgold.florisboard.ime.editor.EditorInstance.replaceTextBeforeCursor]: finish the region,
 * delete exactly the characters written, commit the final text, all in one batch edit). The field is
 * then told what to do, instead of being trusted to do it.
 *
 * The engine is not modified by any of this: its own `composingText` bookkeeping, boundary processing and
 * restart loop stay byte-identical to the HeliBoard reference. Everything here is Dictate's host side.
 *
 * Two display modes, one per setting the user already has:
 *
 *  * **Live preview (default).** The engine's provisional text is written as a grey composing region as
 *    it arrives, and the final text replaces it — the HeliBoard behaviour, unchanged.
 *  * **"Show the text only when I stop"** (`dictate__realtime_hide_preview`, the third answer of the
 *    real-time transcription row). Nothing reaches the field while speaking. The same writes are kept in
 *    [sessionText] and the *reads* the engines do for their boundary logic are answered from that
 *    virtual field, so punctuation, spacing and capitalisation across segments stay exactly as they
 *    would have been on screen; at stop the whole transcript is committed in one piece. The user's own
 *    text is never touched, and an explicit delete while listening eats the virtual tail first.
 */
package dev.patrickgold.florisboard.dictate

import android.content.Context
import android.content.Intent
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.inputmethod.InputConnection
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.editorInstance
import helium314.keyboard.voice.SpeechNotesVoiceEngine
import helium314.keyboard.voice.VoiceEngineHost
import helium314.keyboard.voice.VoiceSounds

class BasicVoiceHost(
    /** App context, kept so the controller can reach it from context-less paths (cancel button). */
    val appContext: Context,
    private val onEngineStopped: () -> Unit = {},
    /**
     * The engine's state in the live indicator's vocabulary, or null when its session has ended.
     *
     * The engine's own state is the only honest answer to "will the next word be heard": it reports
     * CONNECTING before the recognizer is ready and RESTARTING while it swaps generations between
     * them, and both mean the same thing to the user — it is not listening yet. Translating here
     * rather than in the controller keeps [VoiceEngineHost] the only place that knows the engine's
     * vocabulary at all.
     */
    private val onEnginePhase: (LiveVoicePhase?) -> Unit = {},
    /**
     * Microphone RMS from the recognizer, in dB — see [VoiceEngineHost.onVoiceRmsChanged].
     *
     * A system engine owns the microphone itself, so this is the only level a basic-voice dictation
     * can offer its bars; without it they would have nothing to react to and would only breathe.
     */
    private val onEngineLevel: (Float) -> Unit = {},
) : VoiceEngineHost {

    private val prefs by FlorisPreferenceStore

    private val editorInstance by appContext.editorInstance()

    /**
     * The temporary-text colour. The same shade the ported engine composes with
     * (`SpeechNotesVoiceEngine.setComposingText`, `0xFF888888`), so a region this host writes on its own
     * — a shortened tail after a keyboard touch — is indistinguishable from one the engine wrote.
     */
    private fun greySpanned(text: String): CharSequence {
        val grey = SpannableString(text)
        grey.setSpan(ForegroundColorSpan(GREY), 0, text.length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
        return grey
    }

    /**
     * Whether this session shows its words as they arrive or only once, at the end.
     *
     * Read once, at construction, from the same preference the real-time transcription row's third
     * option writes: streaming is the engine's nature either way, so the choice is purely about whether
     * its provisional words are allowed into the field. Fixing it for the session keeps one dictation
     * from changing its mind halfway through.
     */
    private val hidePreview: Boolean = prefs.dictate.realtimeHidePreview.get()

    /**
     * Marks the dictation session as the owner of the field's composing region (see
     * [AbstractEditorInstance.composingRegionExternallyOwned]): while the engine streams its grey
     * text, the editor's selection-update machinery must not finish or re-claim that region between
     * partial results — finishing it bakes the grey text in permanently and the next partial then
     * re-types the whole transcript after it (the "Hello Hello hello there …" accumulation bug).
     * This is the same guard the engine's original host applied in its onUpdateSelection.
     */
    fun claimComposingOwnership() {
        editorInstance.claimComposingRegionOwnership()
    }

    /** Returns the composing region to the keyboard's normal handling (session over). */
    fun releaseComposingOwnership() {
        editorInstance.releaseComposingRegionOwnership()
    }

    /**
     * The engine starts a NEW dictation segment (a fresh recognizer generation after a commit, or a
     * resumed listening): whatever the user made permanent in the field since — a keyboard touch
     * that committed the grey text, plus any manual typing on top of it — is their own text now and
     * is NOT part of the new segment. Reset the accounting so the new grey region starts after it
     * and the next cancel/stop can only ever remove what this new segment itself wrote.
     */
    override fun beginNewVoiceSegment() {
        sessionText.setLength(0)
        composingLen = 0
        regionText = ""
        consumedText.setLength(0)
    }

    /**
     * Everything the engine currently has in the field for this session: committed segments AND
     * the live grey composing region, netted exactly as Android replaces the composing region on
     * each write. [deleteSessionText] removes precisely this text on a cancel.
     *
     * In [hidePreview] mode this is also the *virtual field*: nothing was written for real, so it is
     * what the engines are shown when they read backwards for their boundary logic (see
     * [readBackBeforeCursor]).
     */
    private val sessionText = StringBuilder()

    /** Length of the composing region the engine last set (0 when nothing is being composed). */
    private var composingLen = 0

    /**
     * What the live grey region currently holds, in the engine's own words — the text this host last
     * wrote with `setComposingText`. It is what a keyboard touch makes permanent, so it is the string
     * that must never be written again (see [consumedText]).
     */
    private var regionText = ""

    /**
     * Grey text of the current segment the user's own keyboard interaction has already made permanent —
     * a keypress or backspace over the preview runs `finishComposingText` first, so the region's text
     * becomes ordinary field text.
     *
     * The recognizer keeps streaming the same utterance afterwards, and its next hypothesis still
     * *contains* that text as a prefix. Writing the engine's text verbatim from then on is what put the
     * sentence in the field twice; the host writes only what is new, exactly as the AI dictation preview
     * does with its committed base. Cleared per segment ([beginNewVoiceSegment]), because each recognizer
     * generation carries its own utterance rather than a growing transcript.
     */
    private val consumedText = StringBuilder()

    /**
     * Set only while a cancel is in flight. In this mode the engine's stop-flush is swallowed:
     * its final commit/compose is not written, and the grey composing region is cleared instead
     * of updated — so cancelling leaves nothing new behind, and [deleteSessionText] then removes
     * only what the session had already committed earlier.
     */
    @Volatile
    var discardMode = false

    /**
     * Cancels the session for real: from now on every engine write is swallowed (discard mode),
     * the grey composing region is wiped off the field immediately, and everything the engine had
     * already committed is removed too — only when it is still sitting right before the cursor,
     * so the user's own edits are never eaten. Used by the controller's cancel path.
     */
    fun discardSessionNow() {
        discardMode = true
        sealConsumedRegion()
        val ic = FlorisImeService.currentInputConnection()
        if (ic != null && !hidePreview) {
            if (composingLen > 0) runCatching { ic.setComposingText("", 1) }
            val committed = sessionText.substring(0, (sessionText.length - composingLen).coerceAtLeast(0))
            if (committed.isNotEmpty()) {
                val before = runCatching { ic.getTextBeforeCursor(committed.length, 0)?.toString() }.getOrNull()
                if (before != null && before.endsWith(committed)) {
                    runCatching { ic.deleteSurroundingText(committed.length, 0) }
                }
            }
        }
        sessionText.setLength(0)
        composingLen = 0
    }

    /** Resets the accounting for a fresh session. */
    fun resetSession() {
        sessionText.setLength(0)
        composingLen = 0
        regionText = ""
        consumedText.setLength(0)
        discardMode = false
    }

    /**
     * The text of the current segment that is not already in the field — the engine's `full` minus
     * whatever a keyboard touch already made permanent.
     *
     * A prefix test rather than a diff: the engine's text for one segment is a growing hypothesis, so
     * the consumed part is either exactly its prefix (the ordinary case, including a mid-word revision
     * after it) or the two have diverged, and then there is nothing safe to subtract and the caller
     * writes the engine's text whole — which is the pre-existing behaviour, no worse than before.
     */
    private fun remainingForField(full: String): String {
        val consumed = consumedText.toString()
        if (consumed.isEmpty()) return full
        return if (full.startsWith(consumed)) full.substring(consumed.length) else full
    }

    /**
     * A keyboard write finished the region the engine believes it owns: its text is permanent field text
     * now (see [consumedText]), and the region itself is gone ([composingLen] becomes 0 so nothing tries
     * to delete or replace characters that are no longer ours to touch).
     */
    private fun sealConsumedRegion() {
        if (composingLen <= 0 || !composingRegionGone()) return
        consumedText.append(regionText)
        regionText = ""
        composingLen = 0
    }

    /** Whether the composing region the engine believes it owns is no longer marked as ours. */
    private fun composingRegionGone(): Boolean {
        val owned = runCatching { editorInstance.composingRegionExternallyOwned }.getOrDefault(true)
        return !owned
    }

    /**
     * Writes the hidden transcript into the field, in one piece, and clears it. Called when a
     * [hidePreview] session ends (stop, screen-off, keyboard hide). A no-op in live-preview mode, where
     * the text is already in the field, and after a cancel, where the buffer was emptied on purpose.
     */
    fun flushHiddenText() {
        if (!hidePreview) return
        val text = sessionText.toString()
        sessionText.setLength(0)
        composingLen = 0
        if (text.isEmpty()) return
        val ic = FlorisImeService.currentInputConnection() ?: return
        runCatching { ic.commitText(text, 1) }
    }

    /** Returns — and clears — the session text still owed to the field (after a delete). */
    fun takeSessionText(): String {
        val text = sessionText.toString()
        sessionText.setLength(0)
        composingLen = 0
        return text
    }

    /**
     * Removes everything the engine committed this session, but only when that text is still
     * sitting right before the cursor — the same safety rule [DictationSink.deleteLastText]
     * applies, so the user's own edits (or a cursor they moved) are never eaten. In [hidePreview] mode
     * the text never reached the field, so there is nothing to remove there.
     */
    fun deleteSessionText(): Boolean {
        val text = sessionText.toString()
        if (text.isEmpty()) return true
        if (hidePreview) {
            sessionText.setLength(0)
            composingLen = 0
            return true
        }
        val ic = FlorisImeService.currentInputConnection() ?: return false
        val before = ic.getTextBeforeCursor(text.length, 0)?.toString() ?: return false
        if (!before.endsWith(text)) return false
        return ic.deleteSurroundingText(text.length, 0)
    }

    override fun currentInputConnection(): InputConnection? {
        val ic = FlorisImeService.currentInputConnection() ?: return null
        // A fresh wrapper per call is safe: the accounting lives here on the host, and the
        // wrapper is a thin mirroring layer over the live connection.
        return SessionInputConnection(ic)
    }

    override fun onVoiceEngineStateChanged(
        state: SpeechNotesVoiceEngine.VoiceUiState,
        playStartTone: Boolean,
        playStopTone: Boolean,
    ) {
        // The two distinct, reliable indicator sounds, strictly tied to the engine's real
        // listening state — the same wiring the engine's original host used.
        if (playStartTone) VoiceSounds.playListeningStart()
        if (playStopTone) VoiceSounds.playListeningStop()
        // The controller's live indicator is driven by this same state, so the caption and the tones can
        // never disagree about whether the microphone is hearing anything.
        onEnginePhase(
            when (state) {
                SpeechNotesVoiceEngine.VoiceUiState.IDLE -> null
                SpeechNotesVoiceEngine.VoiceUiState.CONNECTING,
                SpeechNotesVoiceEngine.VoiceUiState.RESTARTING -> LiveVoicePhase.PLEASE_WAIT
                SpeechNotesVoiceEngine.VoiceUiState.READY -> LiveVoicePhase.SPEAK_NOW
            },
        )
        if (state == SpeechNotesVoiceEngine.VoiceUiState.IDLE) {
            // A hidden-preview session owes the field everything it kept back; write it before the
            // controller drops the session, and before composing ownership is released.
            flushHiddenText()
            onEngineStopped()
        }
    }

    /** Microphone level, straight through to the live indicator's bars. */
    override fun onVoiceRmsChanged(rmsDb: Float) {
        onEngineLevel(rmsDb)
    }

    /**
     * The microphone is closed while the session finishes writing its last words — see
     * [VoiceEngineHost.onVoiceEngineClosing].
     *
     * Without this the bar would keep showing a listening state after the microphone had already closed,
     * because an engine that waits for its final text has no other moment to say so: its IDLE arrives only
     * once that text is in. The stage is named with the word the rest of the app already uses for it.
     */
    override fun onVoiceEngineClosing() {
        onEnginePhase(LiveVoicePhase.TRANSCRIBING)
    }

    override fun stopAiVoiceForNormalVoiceStart() {
        // Basic voice starts only from the idle state, where no AI dictation can be running —
        // there is nothing to stop, and the engine expects this call to be cheap and safe.
    }

    override fun openVoiceSetup() {
        // Dictate's settings live in the app activity; progressively plainer launches as fallback.
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        runCatching {
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private val context: Context get() = appContext

    private companion object {
        /** The ported engine's grey for provisional text (`SpeechNotesVoiceEngine`). */
        const val GREY = 0xFF888888.toInt()
    }

    /**
     * The text the engine should see before the cursor: the field's own text, with this session's virtual
     * text appended when the preview is hidden. In live-preview mode nothing is appended, because the
     * engine's text really is in the field.
     *
     * This is what keeps a hidden dictation's punctuation and spacing identical to a visible one: the
     * engines decide where to put a space or a capital by reading the text they have already produced, so
     * answering those reads is the whole trick — no separate formatting pass and no second model.
     */
    private fun readBackBeforeCursor(inner: InputConnection, n: Int, flags: Int): CharSequence? {
        if (!hidePreview) return inner.getTextBeforeCursor(n, flags)
        val real = runCatching { inner.getTextBeforeCursor(n, flags)?.toString() }.getOrNull().orEmpty()
        return (real + sessionText).takeLast(n)
    }

    /**
     * Mirroring wrapper over the live connection. Only the writing methods the engine uses
     * are intercepted; every read (boundary processing) and every other call reaches the real
     * editor untouched — except in hidden-preview mode, where the session's own text is added to the
     * read so the engine sees the field it believes it is writing into.
     *
     * Accounting follows InputConnection's documented semantics exactly: both [commitText] and
     * [setComposingText] replace the current composing region, so the session text's tail of
     * [composingLen] characters is swapped for the new text on each write.
     *
     * Consumed-region rule (the grey-becomes-permanent bug): when the user touches the keyboard,
     * the engine's grey region is finished by that keypress and becomes permanent text. This is
     * detected HERE, at the engine's next write — the region it believes it owns is no longer
     * marked as owned ([AbstractEditorInstance.composingRegionExternallyOwned] is false). The
     * stale region text is then treated as sealed (never replaced) and the engine's new text
     * composes fresh after it — anything the user typed in between is never touched.
     */
    private inner class SessionInputConnection(
        private val inner: InputConnection,
    ) : InputConnection by inner {

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
            readBackBeforeCursor(inner, n, flags)

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            if (discardMode) {
                clearComposingForDiscard()
                return true
            }
            // Hidden preview: the field keeps nothing, the buffer keeps everything, and the write is
            // reported as successful because from the engine's side it happened.
            if (hidePreview) {
                replaceComposingTailWith(text)
                composingLen = 0
                return true
            }
            sealConsumedRegion()
            // Only what the field does not already hold. The engine hands over the whole segment, and
            // after a keyboard touch part of it is already permanent.
            val finalText = remainingForField(text.toString())
            val ok = if (composingLen > 0) {
                // The region is still ours: take it away and put the finished text in its place in one
                // batch. This is the fix for the duplicate — the swap no longer depends on the field
                // honouring "commitText replaces the composing region".
                editorInstance.replaceTextBeforeCursor(composingLen, finalText)
            } else {
                inner.commitText(finalText, newCursorPosition)
            }
            replaceComposingTailWith(text)
            regionText = ""
            composingLen = 0
            return ok
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            if (discardMode) {
                clearComposingForDiscard()
                return true
            }
            if (hidePreview) {
                replaceComposingTailWith(text)
                composingLen = text.length
                return true
            }
            sealConsumedRegion()
            // Only the part of the segment the field does not already hold is shown as the grey region —
            // after a keyboard touch that is just the newly spoken words, so nothing the user has already
            // made permanent is ever put in twice.
            val shown = remainingForField(text.toString())
            if (shown.isEmpty()) {
                composingLen = 0
                regionText = ""
                return true
            }
            if (!editorInstance.composingRegionExternallyOwned) {
                // Ownership lapsed for some other reason (the editor may hold a word-composing region of
                // its own). Make that permanent first: setComposingText REPLACES the live region, and a
                // region we did not write is not ours to replace.
                runCatching { inner.finishComposingText() }
                claimComposingOwnership()
            }
            // Keep the engine's own object when nothing was subtracted from it — it carries the grey
            // span; a shortened tail needs the span applied to its own text.
            val payload: CharSequence = if (shown.length == text.length) text else greySpanned(shown)
            val ok = inner.setComposingText(payload, newCursorPosition)
            replaceComposingTailWith(shown)
            composingLen = shown.length
            regionText = shown
            return ok
        }

        override fun finishComposingText(): Boolean {
            if (discardMode) {
                // A cancel must not let the grey text become permanent through a finish call.
                clearComposingForDiscard()
                return true
            }
            if (hidePreview) {
                composingLen = 0
                return true
            }
            composingLen = 0
            return inner.finishComposingText()
        }

        /**
         * Backspace while listening. In hidden-preview mode there is no composing region in the field to
         * delete, so the session's own tail is removed first and only a genuinely larger request (the
         * user's own text) is passed on to the field.
         */
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (!hidePreview || sessionText.isEmpty()) {
                return inner.deleteSurroundingText(beforeLength, afterLength)
            }
            val fromBuffer = beforeLength.coerceAtMost(sessionText.length)
            sessionText.delete(sessionText.length - fromBuffer, sessionText.length)
            composingLen = 0
            val remaining = beforeLength - fromBuffer
            return if (remaining > 0 || afterLength > 0) {
                inner.deleteSurroundingText(remaining, afterLength)
            } else {
                true
            }
        }

        /** Swaps the session text's composing tail for [text] (append when nothing is composed). */
        private fun replaceComposingTailWith(text: CharSequence) {
            val keep = sessionText.length - composingLen
            if (keep >= 0) sessionText.replace(keep, sessionText.length, text.toString())
            else sessionText.append(text)
        }

        /**
         * Discard mode: clear the engine's grey region on the real connection (removing its
         * characters from the field) and drop it from the accounting too, so [deleteSessionText]
         * afterwards sees only the committed segments.
         */
        private fun clearComposingForDiscard() {
            sealConsumedRegion()
            if (composingLen > 0) {
                // Nothing was written to the field in hidden-preview mode, so there is nothing there to
                // clear — the buffer is the only place the text ever lived.
                if (!hidePreview) {
                    runCatching { inner.setComposingText("", 1) }
                }
                val keep = sessionText.length - composingLen
                if (keep >= 0) sessionText.delete(keep, sessionText.length)
            }
            composingLen = 0
        }
    }
}
