/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.overlay

import dev.patrickgold.florisboard.dictate.DictationSink
import dev.patrickgold.florisboard.dictate.data.prompts.RamblerDefaults

/**
 * [DictationSink] backed by [DictateAccessibilityService]. Used by the floating dictation button
 * (issue #88) to write the transcription into whichever app's text field is focused, where no
 * InputConnection is available. Every call is a no-op (returns gracefully) when the accessibility
 * service is not running or no editable field is focused.
 */
class AccessibilitySink : DictationSink {
    override fun commitText(text: String, verify: Boolean): Boolean =
        DictateAccessibilityService.injectText(text, verify)

    override fun selectedText(): String = DictateAccessibilityService.selectedText()

    override fun fullText(): String = DictateAccessibilityService.fullText()

    override fun selectAll() {
        DictateAccessibilityService.selectAll()
    }

    override fun performEnter(): Boolean = DictateAccessibilityService.performEnter()

    override fun deleteLastText(text: String): Boolean =
        DictateAccessibilityService.deleteLastText(text)

    /**
     * The float button drives a field it does not own, so it cannot read the field's IME action the way
     * the keyboard can. Gboard's `handleBasicAction` guard (action must be SEND) therefore degrades to the
     * service's own Enter/action attempt — which is already refused by any field that implements no action
     * (issue #278), so a "send" in a plain text box leaves the text alone.
     */
    override fun performSendCommand(): Boolean = DictateAccessibilityService.performEnter()

    override fun deleteCommandText(clearAll: Boolean): Boolean {
        if (clearAll) {
            selectAll()
            return commitText("")
        }
        // Best-effort for a remote field: the whole field text stands in for "text before the cursor" and
        // the service's own suffix check (deleteLastText) refuses a guess that does not match, so this can
        // never eat words the user added in the meantime.
        val sentence = RamblerDefaults.lastSentenceToDelete(fullText()) ?: return false
        return deleteLastText(sentence)
    }

    // Real-time overlay preview (#128): the service tracks what it injected and applies a throttled minimal
    // diff (so live streaming into another app doesn't flood the accessibility channel). It keeps its own
    // shown-text state, so the sink's prevText is unused here.
    override fun setDictationPreview(newText: String, prevText: String) {
        DictateAccessibilityService.setPreview(newText)
    }

    override fun commitDictationFinal(finalText: String, prevText: String): Boolean =
        DictateAccessibilityService.commitPreviewFinal(finalText)

    override fun clearDictationPreview(prevText: String) {
        DictateAccessibilityService.clearPreview()
    }
}
