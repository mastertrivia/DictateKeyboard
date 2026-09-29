/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.dictate.provider

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * The two providers served by the phone's own speech service rather than by an HTTP endpoint: Basic
 * voice typing and Google Live Transcribe.
 *
 * Both are keyless and model-less, both are routed by the dictation flow to a `helium314.keyboard.voice`
 * engine, and both therefore have to be recognised as the same *kind* of provider everywhere a provider
 * is classified. The tests below pin that: the kind is a property of the wire-format value, the ordering
 * helper is the single answer to "where does this row go", and neither provider may ever start asking for
 * a credential — the mistake that would turn a setting that needs nothing into a form.
 */
class SystemSpeechProviderTest : FunSpec({

    val basic = ProviderRegistry.BASIC
    val liveTranscribe = ProviderRegistry.LIVE_TRANSCRIBE

    test("both phone-side providers are recognised as system speech, and nothing else is") {
        ProviderRegistry.isSystemSpeechApi(basic.transcriptionApi) shouldBe true
        ProviderRegistry.isSystemSpeechApi(liveTranscribe.transcriptionApi) shouldBe true
        ProviderRegistry.isSystemSpeechApi(TranscriptionApi.LOCAL_ONDEVICE) shouldBe false
        ProviderRegistry.isSystemSpeechApi(TranscriptionApi.OPENAI_MULTIPART) shouldBe false
    }

    test("neither provider asks for a key, a model or a base URL") {
        // `apiKeyUrl` is the app's single answer to "is there a key to enter": `requiresCredential` reads
        // it, so null here is what keeps the row free of a credential form. A non-empty base URL would
        // likewise put a network endpoint behind an engine that only ever talks to the phone.
        for (preset in listOf(basic, liveTranscribe)) {
            preset.apiKeyUrl shouldBe null
            preset.baseUrl shouldBe ""
            preset.supportsDynamicModels shouldBe false
            preset.curatedTranscriptionModels shouldBe emptyList()
            preset.capabilities.transcription shouldBe true
            preset.capabilities.chat shouldBe false
        }
    }

    test("the two are distinct providers with distinct wire formats") {
        basic.id shouldNotBe liveTranscribe.id
        basic.transcriptionApi shouldNotBe liveTranscribe.transcriptionApi
        basic.displayName shouldBe "Basic Voice Typing"
        liveTranscribe.displayName shouldBe "Google Live Transcribe"
    }

    test("ranking puts the phone-side engines first, basic before live transcribe") {
        // The picker and the manage list both sort by this number, so one change here re-orders both.
        ProviderRegistry.systemSpeechRank(basic) shouldBe 0
        ProviderRegistry.systemSpeechRank(liveTranscribe) shouldBe 1
        ProviderRegistry.systemSpeechRank(ProviderRegistry.LOCAL) shouldBe 2
        ProviderRegistry.systemSpeechRank(ProviderRegistry.OPENAI) shouldBe 3
    }

    test("the preset list keeps the phone-side engines at the top, in order") {
        val ordered = ProviderRegistry.presets
            .filter { it.capabilities.transcription }
            .sortedBy { ProviderRegistry.systemSpeechRank(it) }

        ordered.take(3).map { it.id } shouldContainExactly
            listOf(basic.id, liveTranscribe.id, ProviderRegistry.LOCAL.id)
        ordered.first().id shouldBe basic.id
    }
})
