/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.data.prompts

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Locks the Rambler-extracted behaviour in place.
 *
 * The voice-command patterns and the surrounding-text windowing are the two pieces of Rambler's
 * pipeline whose exact semantics can be checked without a device, so they are the ones asserted here.
 * The prompt fragments themselves are asserted only for the invariants that matter (placeholders
 * substituted, context blocks in the right order, nothing left dangling).
 */
class RamblerDefaultsTest : FunSpec({

    // -- voice commands (patterns taken verbatim from VoiceCommandClassifier.classify) ---------------

    test("send command matches the Gboard send pattern variants") {
        // send_command_regex = "(?i)^[^a-z0-9]*sen[dt][^a-z0-9]*$" — "send"/"sent" with any
        // surrounding non-alphanumerics, which is what ASR produces for "Send." / "send!".
        RamblerDefaults.classifyVoiceCommand("send") shouldBe RamblerDefaults.VoiceCommand.SEND
        RamblerDefaults.classifyVoiceCommand("Send") shouldBe RamblerDefaults.VoiceCommand.SEND
        RamblerDefaults.classifyVoiceCommand("send.") shouldBe RamblerDefaults.VoiceCommand.SEND
        RamblerDefaults.classifyVoiceCommand(" sent! ") shouldBe RamblerDefaults.VoiceCommand.SEND
        RamblerDefaults.classifyVoiceCommand("\"sent\"") shouldBe RamblerDefaults.VoiceCommand.SEND
    }

    test("clear and clear-all commands match their Gboard patterns") {
        // Gboard's enum constants for the same two patterns: kng.DELETE_LAST_SENTENCE (8), kng.DELETE_ALL (10).
        RamblerDefaults.classifyVoiceCommand("clear") shouldBe RamblerDefaults.VoiceCommand.DELETE_LAST_SENTENCE
        RamblerDefaults.classifyVoiceCommand("Clear.") shouldBe RamblerDefaults.VoiceCommand.DELETE_LAST_SENTENCE
        RamblerDefaults.classifyVoiceCommand("clear all") shouldBe RamblerDefaults.VoiceCommand.DELETE_ALL
        RamblerDefaults.classifyVoiceCommand("Clear all!") shouldBe RamblerDefaults.VoiceCommand.DELETE_ALL
    }

    test("ordinary dictation is not a command") {
        // The patterns are anchored, so a sentence containing a command word must never match.
        RamblerDefaults.classifyVoiceCommand("please send the email") shouldBe null
        RamblerDefaults.classifyVoiceCommand("clear the table later") shouldBe null
        RamblerDefaults.classifyVoiceCommand("I will clear all of it tomorrow") shouldBe null
        RamblerDefaults.classifyVoiceCommand("sender") shouldBe null
        RamblerDefaults.classifyVoiceCommand("") shouldBe null
        RamblerDefaults.classifyVoiceCommand("   ") shouldBe null
    }

    // -- the "clear" command target (Gboard: "Delete the last senetence in the input field.") --------

    test("last sentence to delete falls back to the whole text when there is one sentence") {
        RamblerDefaults.lastSentenceToDelete("Hello world.") shouldBe "Hello world."
        RamblerDefaults.lastSentenceToDelete("Hello world. ") shouldBe "Hello world. "
        RamblerDefaults.lastSentenceToDelete("Hi there") shouldBe "Hi there"
    }

    test("last sentence to delete starts after the previous terminator") {
        RamblerDefaults.lastSentenceToDelete("Hello. Bye") shouldBe "Bye"
        RamblerDefaults.lastSentenceToDelete("Hello. Bye!") shouldBe "Bye!"
        RamblerDefaults.lastSentenceToDelete("First line\nSecond") shouldBe "Second"
        // A terminator run ("...") still ends the sentence being deleted.
        RamblerDefaults.lastSentenceToDelete("Wait... then go") shouldBe "then go"
    }

    test("last sentence to delete returns null when there is nothing to delete") {
        RamblerDefaults.lastSentenceToDelete("") shouldBe null
        RamblerDefaults.lastSentenceToDelete("   ") shouldBe null
    }

    test("last sentence to delete always returns a real suffix (the sink's delete contract)") {
        // The sink deletes only when the text before the cursor still ENDS WITH the returned string, so a
        // trimmed sentence would silently fail whenever a space follows it.
        val before = "Hello world. "
        val target = RamblerDefaults.lastSentenceToDelete(before)!!
        before.endsWith(target) shouldBe true
    }

    // -- surrounding-text windowing (verbatim from JetsonLiteHandler.g) ------------------------------

    test("surrounding window keeps only the trailing paragraph before the cursor") {
        val before = "first paragraph\n\nsecond paragraph"
        RamblerDefaults.surroundingWindow(before, "hello", null) shouldBe "second paragraph hello"
    }

    test("surrounding window keeps only the leading paragraph after the cursor") {
        val after = "tail of the sentence\n\nlater paragraph"
        RamblerDefaults.surroundingWindow(null, "hello", after) shouldBe "hello tail of the sentence"
    }

    test("surrounding window collapses whitespace runs like Gboard") {
        val before = "a  b\n\nc\t\t d"
        RamblerDefaults.surroundingWindow(before, "hello", "e   f") shouldBe "c d hello e f"
    }

    test("surrounding window with no context is just the transcript") {
        RamblerDefaults.surroundingWindow(null, "hello", null) shouldBe "hello"
        RamblerDefaults.surroundingWindow("", "hello", "") shouldBe "hello"
    }

    // -- prompt assembly ----------------------------------------------------------------------------

    test("cleanup prompt substitutes every placeholder") {
        val prompt = RamblerDefaults.buildCleanupPrompt(
            transcript = "hello world",
            enabledLanguages = listOf("en-GB", "hi-IN"),
            appLabel = "Messages",
            packageName = "com.example.messages",
            personalDictionary = listOf("FlorisBoard", "Dictate"),
            customRules = "always capitalise Google",
        )
        prompt shouldContain "hello world"
        prompt shouldContain "en-GB, hi-IN"
        prompt shouldContain "Name: Messages -- Package name: com.example.messages"
        prompt shouldContain "FlorisBoard, Dictate"
        prompt shouldContain "always capitalise Google"
        // No unsubstituted placeholders may survive.
        listOf(
            "{CURRENT_TEXT}", "{ENABLED_LANGUAGES}", "{APP_INFO}", "{PERSONAL_DICTIONARY}",
            "{CUSTOM_RULES}", "{CONVERSATION_HISTORY}", "{HINGLISH_OVERRIDE_RULE}",
        ).forEach { placeholder ->
            prompt shouldNotContain placeholder
        }
    }

    test("cleanup prompt always carries the Hinglish override and the emoji constraint") {
        val prompt = RamblerDefaults.buildCleanupPrompt(transcript = "hello")
        prompt shouldContain "Hinglish Override"
        prompt shouldContain "Romanized script"
        prompt shouldContain "Do NOT add emojis"
        prompt shouldContain "Output ONLY the final processed text"
    }

    test("optional context blocks are omitted when their inputs are empty") {
        val prompt = RamblerDefaults.buildCleanupPrompt(transcript = "hello")
        prompt shouldNotContain "<app_context>"
        prompt shouldNotContain "<personal_dictionary_context>"
        prompt shouldNotContain "<custom_rules>"
    }

    test("context blocks precede the current text like Gboard") {
        val prompt = RamblerDefaults.buildCleanupPrompt(
            transcript = "hello",
            appLabel = "Messages",
            packageName = "com.example.messages",
        )
        (prompt.indexOf("<app_context>") < prompt.indexOf("<CURRENT_TEXT>")) shouldBe true
    }

    test("app info is null only when both label and package are blank") {
        RamblerDefaults.appInfo(null, null) shouldBe null
        RamblerDefaults.appInfo("", "") shouldBe null
        RamblerDefaults.appInfo(null, "com.x") shouldBe "Name:  -- Package name: com.x"
    }

    test("rambler constants keep Gboard's values") {
        RamblerDefaults.CLEANUP_TEMPERATURE shouldBe 0.7f
        RamblerDefaults.CONFIDENCE_THRESHOLD shouldBe 0.5
    }
})
