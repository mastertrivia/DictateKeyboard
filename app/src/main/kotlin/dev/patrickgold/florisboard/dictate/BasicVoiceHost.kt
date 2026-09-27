/*
 * Host adapter between Dictate's controller and the ported basic-voice-typing engine
 * (helium314.keyboard.voice, copied verbatim from the HeliBoard fork). It provides the four
 * operations the engine's host seam (VoiceEngineHost) needs: the live InputConnection,
 * engine-state callbacks (tones + controller teardown), the "stop other voice features" hook
 * (basic voice and AI dictation never run at the same time, so there is nothing to stop), and
 * the settings deep link.
 *
 * It also owns the session's *text* accounting: every write the engine makes to the field
 * (grey composing updates and committed segments alike) is mirrored into [sessionText], so a
 * cancelled dictation can be removed exactly — the guarantee the engine's original host had
 * through its editor model, reached here through a counting InputConnection wrapper. Android's
 * own InputConnection semantics are mirrored one-for-one: both setComposingText and commitText
 * *replace* the current composing region, so the net session text is a running tail-replace.
 */
package dev.patrickgold.florisboard.dictate

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.inputmethod.InputConnection
import dev.patrickgold.florisboard.FlorisImeService
import helium314.keyboard.voice.SpeechNotesVoiceEngine
import helium314.keyboard.voice.VoiceEngineHost
import helium314.keyboard.voice.VoiceSounds

class BasicVoiceHost(
    /** App context, kept so the controller can reach it from context-less paths (cancel button). */
    val appContext: Context,
    private val onEngineStopped: () -> Unit = {},
) : VoiceEngineHost {

    /**
     * Everything the engine currently has in the field for this session: committed segments AND
     * the live grey composing region, netted exactly as Android replaces the composing region on
     * each write. [deleteSessionText] removes precisely this text on a cancel.
     */
    private val sessionText = StringBuilder()

    /** Length of the composing region the engine last set (0 when nothing is being composed). */
    private var composingLen = 0

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
        val ic = FlorisImeService.currentInputConnection()
        if (ic != null) {
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
        discardMode = false
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
     * applies, so the user's own edits (or a cursor they moved) are never eaten.
     */
    fun deleteSessionText(): Boolean {
        val text = sessionText.toString()
        if (text.isEmpty()) return true
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
        if (state == SpeechNotesVoiceEngine.VoiceUiState.IDLE) onEngineStopped()
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

    /**
     * Mirroring wrapper over the live connection. Only the three writing methods the engine uses
     * are intercepted; every read (boundary processing) and every other call reaches the real
     * editor untouched.
     *
     * Accounting follows InputConnection's documented semantics exactly: both [commitText] and
     * [setComposingText] replace the current composing region, so the session text's tail of
     * [composingLen] characters is swapped for the new text on each write.
     */
    private inner class SessionInputConnection(
        private val inner: InputConnection,
    ) : InputConnection by inner {

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            if (discardMode) {
                clearComposingForDiscard()
                return true
            }
            val ok = inner.commitText(text, newCursorPosition)
            replaceComposingTailWith(text)
            composingLen = 0
            return ok
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            if (discardMode) {
                clearComposingForDiscard()
                return true
            }
            val ok = inner.setComposingText(text, newCursorPosition)
            replaceComposingTailWith(text)
            composingLen = text.length
            return ok
        }

        override fun finishComposingText(): Boolean {
            if (discardMode) {
                // A cancel must not let the grey text become permanent through a finish call.
                clearComposingForDiscard()
                return true
            }
            composingLen = 0
            return inner.finishComposingText()
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
            if (composingLen > 0) {
                inner.setComposingText("", 1)
                val keep = sessionText.length - composingLen
                if (keep >= 0) sessionText.delete(keep, sessionText.length)
            }
            composingLen = 0
        }
    }
}
