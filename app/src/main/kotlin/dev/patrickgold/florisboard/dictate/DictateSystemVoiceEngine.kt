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

import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry

/**
 * Which of the phone's own speech engines a keyless dictation uses.
 *
 * Both engines are clients of Android's public `android.speech.SpeechRecognizer` API, and on a device
 * with Google's Speech Services (`com.google.android.tts`) installed they are served by the very same
 * app — which is why they are one provider choice with two engines rather than two independent features.
 * What differs is the request each one builds and what the service does with it:
 *
 *  * [BUILT_IN] — the plain recognizer request every keyboard app makes: language model `free_form`,
 *    partial results, dictation mode. This is the engine Basic voice typing has always shipped, byte for
 *    byte (`helium314.keyboard.voice.VoiceController`), and it stays the default so nothing changes for
 *    anyone who does not go looking.
 *  * [LIVE_TRANSCRIBE] — the request the installed Google Live Transcribe app builds, decompiled from
 *    `com/google/audio/hearing/visualization/accessibility/asr/offline/SodaSpeechSession.java`: the
 *    on-device model is preferred (`PREFER_OFFLINE`), the recognizer itself punctuates and capitalises
 *    (`ENABLE_TEXT_FORMATTING`), and it emits its own end-pointing/stability events
 *    (`REQUEST_SODA_EVENTS`). Served by `helium314.keyboard.voice.LiveTranscribeSession`.
 *
 * The engine choice is stored once ([dev.patrickgold.florisboard.app.AppPrefs.dictate.basicVoiceEngine])
 * and read by both routes into the system recognizer, so choosing "Google Live Transcribe" as the
 * provider and choosing it as Basic voice typing's engine cannot drift apart in behaviour.
 */
enum class DictateSystemVoiceEngine {
    BUILT_IN,
    LIVE_TRANSCRIBE;

    companion object {
        /**
         * The engine a dictation actually runs, or `null` when [providerId] is not a system provider.
         *
         * Basic voice typing is the one provider that offers a choice, so it answers with [preferred]
         * — the stored setting. Google Live Transcribe is that engine under its own name and ignores the
         * preference, so picking it is always the Live Transcribe request and never silently the plain
         * one.
         */
        fun resolve(providerId: String, preferred: DictateSystemVoiceEngine): DictateSystemVoiceEngine? =
            when (providerId) {
                ProviderRegistry.BASIC.id -> preferred
                ProviderRegistry.LIVE_TRANSCRIBE.id -> LIVE_TRANSCRIBE
                else -> null
            }
    }
}
