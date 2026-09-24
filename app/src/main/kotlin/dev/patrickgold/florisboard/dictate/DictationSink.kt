/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate

import android.content.Context
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.key.KeyType
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.keyboardManager

/**
 * Where a finished dictation/rewording is written, and how the focused field is read. Abstracts the
 * editor so the same dictation engine ([DictateController]) can output through the keyboard's own
 * InputConnection when Dictate is the active IME, or — for the floating overlay (issue #88) — through
 * an AccessibilityService-injected field, where no InputConnection is available.
 *
 * The IME-backed implementation ([ImeDictationSink]) mirrors the original direct EditorInstance usage
 * exactly, so routing every output through a sink is behavior-neutral for the keyboard path.
 */
interface DictationSink {
    /**
     * Inserts [text] at the cursor, replacing the active selection if any. Returns whether the write
     * actually landed: the keyboard path always succeeds, but the accessibility/overlay path can fail
     * silently on some app fields (Compose/WebView), so callers can avoid flashing a false success (#156).
     */
    fun commitText(text: String, verify: Boolean = true): Boolean

    /** The currently selected text, or empty when nothing is selected. */
    fun selectedText(): String

    /** The full text of the focused field (used by the "rework the whole field" path). */
    fun fullText(): String

    /** Selects the whole field so a subsequent [commitText] replaces its content. */
    fun selectAll()

    /**
     * Presses Enter / triggers the editor action (auto-enter, roadmap 10.1). Returns whether the field
     * accepted it — the keyboard dispatches a real key event and always does, while the overlay can only
     * *ask* the field, and an app that implements no editor action simply refuses (issue #278).
     */
    fun performEnter(): Boolean

    /**
     * Removes the last inserted [text] from the field again (undo, issue #133). Only deletes when the
     * text immediately before the cursor still matches [text], so the user's own edits are never eaten.
     * Returns true when the field accepted the removal.
     */
    fun deleteLastText(text: String): Boolean

    /**
     * Live real-time dictation preview (issue #128): reflect [newText] (the growing transcript) in the
     * field, applying only the minimal diff from the [prevText] already shown — so streaming words appear
     * in place, committed to the field. The overlay path has no cheap in-place update, so it skips the
     * preview and shows nothing until [commitDictationFinal].
     */
    fun setDictationPreview(newText: String, prevText: String)

    /**
     * Finalize: replace the [prevText] preview with the finished/reworded [finalText] (minimal diff).
     * Returns whether the field took it — the realtime path used to skip the insert-failure check
     * entirely, so a swallowed write ended in a green check (issue #277).
     */
    fun commitDictationFinal(finalText: String, prevText: String): Boolean

    /** Remove the [prevText] preview entirely (a realtime recording was cancelled / fell back to batch). */
    fun clearDictationPreview(prevText: String)
}

/**
 * [DictationSink] backed by the keyboard's own editor — the active-IME path. Resolves the app-wide
 * [dev.patrickgold.florisboard.ime.editor.EditorInstance] and [dev.patrickgold.florisboard.ime.keyboard.KeyboardManager]
 * singletons exactly as the original inline code did, so dictation output is unchanged.
 */
class ImeDictationSink(context: Context) : DictationSink {
    private val appContext = context.applicationContext
    private val editorInstance by appContext.editorInstance()

    override fun commitText(text: String, verify: Boolean): Boolean {
        editorInstance.commitText(text)
        return true // the keyboard writes through its own InputConnection; this never silently no-ops
    }

    override fun selectedText(): String = editorInstance.activeContent.selectedText

    override fun fullText(): String = editorInstance.activeContent.text

    override fun selectAll() {
        editorInstance.performClipboardSelectAll()
    }

    override fun performEnter(): Boolean {
        val keyboardManager by appContext.keyboardManager()
        // Dispatches a real Enter key event so it reuses the keyboard's full enter logic (editor action,
        // newline, …) rather than committing a literal "\n". A key event is delivered unconditionally,
        // so unlike the overlay there is nothing here that can refuse it.
        keyboardManager.inputEventDispatcher.sendDownUp(EnterKeyData)
        return true
    }

    override fun deleteLastText(text: String): Boolean {
        if (text.isEmpty()) return false
        // Only undo when the characters right before the cursor are exactly what we inserted.
        if (!editorInstance.activeContent.textBeforeSelection.endsWith(text)) return false
        val keyboardManager by appContext.keyboardManager()
        // Reuse the keyboard's own delete handling (one backspace per character).
        repeat(text.length) { keyboardManager.inputEventDispatcher.sendDownUp(TextKeyData.DELETE) }
        return true
    }

    override fun setDictationPreview(newText: String, prevText: String) = applyDictationDiff(prevText, newText)

    override fun commitDictationFinal(finalText: String, prevText: String): Boolean {
        // Finalize the grey temporary preview (see [applyDictationDiff]): with the transcript living in
        // the composing region, one commitText call both ends the region and writes the finished text —
        // commitText *replaces* the composing region by contract, so no diff arithmetic is needed here
        // and the text turns from grey to final in the same frame the provider's last word arrives.
        if (isPreviewRegionLive()) {
            val ic = currentInputConnectionOrNull() ?: run {
                releaseComposingRegionOwnership()
                return false
            }
            ic.commitText(finalText, 1)
            releaseComposingRegionOwnership()
            return true
        }
        // No preview was shown ("show the text only when I stop"): plain insert, as before.
        releaseComposingRegionOwnership()
        if (finalText == prevText) return true
        val cp = prevText.commonPrefixWith(finalText).length
        editorInstance.replaceTextBeforeCursor(prevText.length - cp, finalText.substring(cp))
        return true
    }

    override fun clearDictationPreview(prevText: String) {
        // Cancel the composing region wholesale: setComposingText("") removes the grey preview from
        // the field in one batch (per-character deletes ANR and can kill the keyboard on a long
        // dictation). When no region is live, fall back to the atomic batch delete as before.
        if (isPreviewRegionLive()) {
            currentInputConnectionOrNull()?.let { it.setComposingText("", 1) }
            releaseComposingRegionOwnership()
            return
        }
        releaseComposingRegionOwnership()
        if (prevText.isNotEmpty()) editorInstance.replaceTextBeforeCursor(prevText.length, "")
    }

    /**
     * Whether a grey composing region for this dictation is live in the field. The sink instances
     * are created per call (see [DictateController.sink]), so per-instance length tracking cannot
     * survive between preview updates — the durable state is the editor's ownership claim, which
     * is set by [applyDictationDiff] and is exactly what must also decide the finalize/clear paths.
     */
    private fun isPreviewRegionLive(): Boolean = editorInstance.composingRegionExternallyOwned

    private fun releaseComposingRegionOwnership() {
        editorInstance.releaseComposingRegionOwnership()
    }

    /** The live editor connection, or null when the window has already gone away. */
    private fun currentInputConnectionOrNull() = FlorisImeService.currentInputConnection()

    /**
     * Turns the currently-shown dictation text [old] into [new] — as **grey, temporary composing
     * text** rather than committed characters. This is the same mechanism the ported basic-voice
     * engine uses: the streaming preview sits in the field's composing region (rendered grey by the
     * span below, wherever the app honors composing styles), each update replaces the region
     * wholesale in one call, and nothing becomes permanent until the provider finalizes or the user
     * stops — at which point [commitDictationFinal] swaps it for the final text in one call.
     *
     * Streaming dictation *needs* this to be temporary: the provider keeps correcting earlier words
     * while you speak, so committing as you go would leave wrong text baked into the field that the
     * final pass then has to un-type. HeliBoard's voice typing behaves exactly this way, and so does
     * the preview now.
     *
     * The [prevText] parameter is kept for the no-preview path above and for callers that track the
     * shown text; with a live region it is irrelevant — setComposingText replaces the region as a
     * whole, so no diff arithmetic can drift out of sync.
     */
    private fun applyDictationDiff(old: String, new: String) {
        if (new.isEmpty()) {
            if (isPreviewRegionLive()) clearDictationPreview(old) else releaseComposingRegionOwnership()
            return
        }
        // From the first grey write until the final commit/clear, the dictation owns the composing
        // region: the editor's selection machinery must not finish/claim it between partials.
        editorInstance.claimComposingRegionOwnership()
        val ic = currentInputConnectionOrNull() ?: return
        val grey = SpannableString(new)
        grey.setSpan(ForegroundColorSpan(PREVIEW_GREY), 0, new.length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
        ic.setComposingText(grey, 1)
    }

    private companion object {
        /** Synthetic Enter key dispatched for auto-enter; reuses the keyboard's full enter logic. */
        private val EnterKeyData =
            TextKeyData(type = KeyType.ENTER_EDITING, code = KeyCode.ENTER, label = "enter")

        /** Grey used for the temporary streaming preview — the same shade the ported engine composes with. */
        private const val PREVIEW_GREY = 0xFF888888.toInt()
    }
}
