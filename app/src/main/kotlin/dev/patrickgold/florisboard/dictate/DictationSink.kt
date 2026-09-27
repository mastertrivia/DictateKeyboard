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
import dev.patrickgold.florisboard.dictate.data.prompts.RamblerDefaults
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.editor.ImeOptions
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
     * Gboard Rambler's **"send"** voice command (`AgenticDictationExtension.handleBasicAction`,
     * `fbl.java:1048`). Gboard guards it with `npc.a(editorInfo) != 4` — i.e. it acts **only** when the
     * field's IME action is `IME_ACTION_SEND` — and then dispatches exactly that action
     * (`opnVar.y(4)` → `performEditorAction(IME_ACTION_SEND)`). Returns whether the action was performed;
     * false means the field has no SEND action and the caller keeps the transcript instead of dropping it.
     */
    fun performSendCommand(): Boolean

    /**
     * Gboard Rambler's **"clear"** / **"clear all"** voice commands. Gboard's own log strings are the
     * specification — *"Clear command detected: Delete the last senetence in the input field."* and
     * *"Clear all command detected: Delete everything in the input field."* — but its `handleBasicAction`
     * only ever executes SEND and drops the other two with "Unsupported basic action", so the documented
     * intent is implemented here instead of the no-op. Returns whether the field accepted the delete.
     *
     * @param clearAll true for `DELETE_ALL` (the whole field), false for `DELETE_LAST_SENTENCE`.
     */
    fun deleteCommandText(clearAll: Boolean): Boolean

    /**
     * Live real-time dictation preview (issue #128): reflect [newText] (the growing transcript) in the
     * field as *temporary* text. The `ImeDictationSink` writes it as a grey composing region, so a
     * keyboard touch commits it and the dictation then only ever manages the uncommitted tail; the
     * overlay path has no cheap in-place update and treats it as nothing (see [commitDictationFinal]).
     */
    fun setDictationPreview(newText: String, prevText: String)

    /**
     * Finalize: replace the [prevText] preview with the finished/reworded [finalText]. With a live grey
     * region this is a single region-replacing commit; without one, only the uncommitted remainder of
     * [finalText] is inserted, so text a keyboard touch already made permanent is never re-typed.
     * Returns whether the field took it — the realtime path used to skip the insert-failure check
     * entirely, so a swallowed write ended in a green check (issue #277).
     */
    fun commitDictationFinal(finalText: String, prevText: String): Boolean

    /** Remove the [prevText] preview entirely (a realtime recording was cancelled / fell back to batch). */
    fun clearDictationPreview(prevText: String)
}

/**
 * Session-wide state of the streaming dictation preview (the grey composing region).
 *
 * A plain object on purpose: the controller creates a fresh [ImeDictationSink] for every write,
 * but one dictation is one logical session, so "what does the field already hold" has to live
 * somewhere that survives between those instances.
 *
 * The model is exactly the grey/normal distinction the preview is built on:
 *  - [committedBase] — grey text the KEYBOARD has already made permanent (the user touched a key
 *    mid-dictation, which finishes the composing region). It is now ordinary user text: never
 *    re-typed, never revised, never deleted by the dictation again.
 *  - [regionText] — what the live grey region currently holds (the uncommitted tail).
 *
 * Every preview write therefore shows only `transcript − committedBase`, and a finalize with no
 * live region inserts only `final − committedBase`. Nothing is ever scanned backwards through
 * the field; the two strings are all the bookkeeping there is.
 */
internal object DictationPreviewState {
    /** Grey text the keyboard interaction has already committed (empty = nothing consumed yet). */
    @Volatile var committedBase: String = ""

    /** Text the live grey region currently holds (empty = no region is up). */
    @Volatile var regionText: String = ""

    /** The editor's consume callback is registered for the current session. */
    @Volatile var listenerRegistered: Boolean = false

    /** Fresh session: nothing committed, nothing shown, listener will be (re-)registered on demand. */
    fun reset() {
        committedBase = ""
        regionText = ""
        listenerRegistered = false
    }

    /**
     * The keyboard finalized the composing region (a keypress/backspace/commit over the grey text):
     * whatever the region held is now permanent user text. Called by the editor through the
     * listener set in [ImeDictationSink]; idempotent within one event.
     */
    fun onConsumedByKeyboard() {
        if (regionText.isEmpty() && committedBase.isEmpty()) return
        committedBase += regionText
        regionText = ""
    }

    /**
     * The uncommitted remainder of the transcript [full] — what may still be written/shown.
     * If the user backspaced part of the committed text away, the base is re-based on what is
     * still common, so only the genuinely new part of the transcript is ever shown again.
     */
    fun tailOf(full: String): String {
        if (committedBase.isEmpty()) return full
        if (full.startsWith(committedBase)) return full.substring(committedBase.length)
        val common = committedBase.commonPrefixWith(full)
        if (common.length < committedBase.length) committedBase = common
        return full.substring(common.length)
    }
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

    override fun performSendCommand(): Boolean {
        // Gboard's exact guard and action: only a field that declares SEND gets the action, and the
        // action dispatched is IME_ACTION_SEND itself (Gboard: `opnVar.y(4)`).
        if (editorInstance.activeInfo.imeOptions.action != ImeOptions.Action.SEND) return false
        return editorInstance.performEnterAction(ImeOptions.Action.SEND)
    }

    override fun deleteCommandText(clearAll: Boolean): Boolean {
        if (clearAll) {
            // "Delete everything in the input field": select the whole field and replace the selection
            // with nothing, so text on both sides of the cursor goes.
            selectAll()
            return commitText("")
        }
        // "Delete the last sentence in the input field": backspace exactly that sentence. deleteLastText
        // re-checks the text before the cursor, so a stale read can never eat the user's own words.
        val sentence = RamblerDefaults.lastSentenceToDelete(editorInstance.activeContent.textBeforeSelection)
            ?: return false
        return deleteLastText(sentence)
    }

    override fun setDictationPreview(newText: String, prevText: String) = applyDictationDiff(prevText, newText)

    override fun commitDictationFinal(finalText: String, prevText: String): Boolean {
        // Finalize the grey temporary preview (see [applyDictationDiff]): with the transcript living in
        // the composing region, one commitText call both ends the region and writes the finished text —
        // commitText *replaces* the composing region by contract, so no diff arithmetic is needed here
        // and the text turns from grey to final in the same frame the provider's last word arrives.
        if (isPreviewRegionLive()) {
            val ic = currentInputConnectionOrNull() ?: run {
                releaseComposingOwnership()
                return false
            }
            // After a keyboard touch the region only holds the uncommitted TAIL; committing the full
            // transcript here would re-type everything the touch already made permanent. Replace the
            // region with exactly what it still owes.
            val tail = DictationPreviewState.tailOf(finalText)
            ic.commitText(tail, 1)
            DictationPreviewState.regionText = ""
            releaseComposingOwnership()
            return true
        }
        // No region is live ("show the text only when I stop", or — the duplication bug — the grey
        // text was already committed by a keyboard touch mid-dictation). Insert ONLY what the user
        // has not already seen: the finished transcript minus the consumed base. The consumed base
        // wins over [prevText] because prevText tracks the full transcript, most of which may
        // already be permanent field text the touch created.
        releaseComposingOwnership()
        if (DictationPreviewState.committedBase.isNotEmpty() && finalText.startsWith(DictationPreviewState.committedBase)) {
            val tail = finalText.substring(DictationPreviewState.committedBase.length)
            if (tail.isNotEmpty()) editorInstance.replaceTextBeforeCursor(0, tail)
            return true
        }
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
            DictationPreviewState.regionText = ""
            releaseComposingOwnership()
            return
        }
        releaseComposingOwnership()
        // Text a keyboard touch already committed is the user's own now — a cancel must not eat it.
        // Only a preview we still hold unseen (hidden-preview mode) is removed wholesale.
        if (DictationPreviewState.committedBase.isEmpty() && prevText.isNotEmpty()) {
            editorInstance.replaceTextBeforeCursor(prevText.length, "")
        }
    }

    /**
     * Whether a grey composing region for this dictation is live in the field. The sink instances
     * are created per call (see [DictateController.sink]), so per-instance length tracking cannot
     * survive between preview updates — the durable state is the editor's ownership claim, which
     * is set by [applyDictationDiff] and is exactly what must also decide the finalize/clear paths.
     */
    private fun isPreviewRegionLive(): Boolean = editorInstance.composingRegionExternallyOwned

    /** The live editor connection, or null when the window has already gone away. */
    private fun currentInputConnectionOrNull() = FlorisImeService.currentInputConnection()

    /**
     * Give the composing region back to the editor (see
     * [dev.patrickgold.florisboard.ime.editor.AbstractEditorInstance.claimComposingRegionOwnership]).
     * Called on every path that ends or abandons the preview, so the editor's own composing machinery
     * resumes control of the region the moment the dictation stops owning it.
     */
    private fun releaseComposingOwnership() {
        editorInstance.releaseComposingRegionOwnership()
    }

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
     * The region can be taken away mid-stream by the user touching the keyboard: every keyboard
     * write begins with finishComposingText, which commits the grey text permanently. The editor
     * signals that moment ([dev.patrickgold.florisboard.ime.editor.AbstractEditorInstance.composingConsumedListener])
     * and [DictationPreviewState] promotes the region's text into the committed base — from then on
     * every write shows only the tail of NEW speech (transcript minus base) as a fresh region after
     * the committed text. Already-committed text is never re-typed, revised, or deleted.
     *
     * The [prevText] parameter is kept for callers that track the shown text; with a live region it
     * is irrelevant — setComposingText replaces the region as a whole, so no diff arithmetic can
     * drift out of sync.
     */
    private fun applyDictationDiff(old: String, new: String) {
        if (new.isEmpty()) {
            if (isPreviewRegionLive()) clearDictationPreview(old) else releaseComposingOwnership()
            return
        }
        // Only the part of the transcript the field does not already hold goes on screen; after a
        // keyboard touch this is just the newly spoken words.
        val tail = DictationPreviewState.tailOf(new)
        if (tail.isEmpty()) {
            // The provider revised its text back to exactly what is already committed: nothing new
            // to show, and the committed text stays untouched.
            if (isPreviewRegionLive()) clearDictationPreview(old)
            return
        }
        ensureConsumeListener()
        editorInstance.claimComposingRegionOwnership()
        val ic = currentInputConnectionOrNull() ?: return
        ic.setComposingText(greySpanned(tail), 1)
        DictationPreviewState.regionText = tail
    }

    /**
     * Registers the editor's composing-consumed callback once per session. From then on ANY keyboard
     * write that finishes the grey region (typing a key, backspace, an explicit finalize) promotes
     * the region's text into [DictationPreviewState.committedBase] — the exact moment the grey
     * preview becomes permanent text the dictation must never touch again.
     */
    private fun ensureConsumeListener() {
        if (DictationPreviewState.listenerRegistered) return
        DictationPreviewState.listenerRegistered = true
        editorInstance.composingConsumedListener = { DictationPreviewState.onConsumedByKeyboard() }
    }

    private fun greySpanned(text: String): SpannableString {
        val grey = SpannableString(text)
        grey.setSpan(ForegroundColorSpan(PREVIEW_GREY), 0, text.length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
        return grey
    }

    private companion object {
        /** Synthetic Enter key dispatched for auto-enter; reuses the keyboard's full enter logic. */
        private val EnterKeyData =
            TextKeyData(type = KeyType.ENTER_EDITING, code = KeyCode.ENTER, label = "enter")

        /** Grey used for the temporary streaming preview — the same shade the ported engine composes with. */
        private const val PREVIEW_GREY = 0xFF888888.toInt()
    }
}
