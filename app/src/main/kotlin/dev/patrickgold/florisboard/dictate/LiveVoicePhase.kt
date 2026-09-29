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
 */

package dev.patrickgold.florisboard.dictate

/**
 * What a live dictation is doing right now, as the four answers the bar can honestly give.
 *
 * The point of the ladder is the difference between the first two, which a single "listening" label
 * cannot express and which is the one thing a user waits on: *has it started yet?* A recorder that has
 * been told to listen but has not bound to a recognizer, opened a socket or heard a first sample will
 * lose the first words spoken into it, and nothing about a pulsing dot says so. So the mic tap answers
 * [PLEASE_WAIT] until the engine is genuinely running, and only then [SPEAK_NOW].
 *
 * The ladder is also why the whole indicator is behind a switch (`dictate__live_voice_indicator`): when
 * the engine has not started, the word *Please wait…* is the only thing on screen that tells the truth,
 * and a user who has not asked for that has a perfectly good recording bar instead.
 *
 *  * [PLEASE_WAIT] — a session exists but cannot hear yet: the recognizer is connecting (system engines
 *    report this directly), or a live provider session is still opening.
 *  * [SPEAK_NOW] — it is listening and the room is quiet. This is the state a user must not be left
 *    guessing about, and it is why the label falls *back* here at every pause rather than sticking on
 *    [LISTENING] once it has been shown.
 *  * [LISTENING] — speech is coming in. For a provider session this is the live microphone level; for a
 *    system engine, where the recognizer owns the microphone (see the note in `BasicVoiceHost`), it is
 *    the moment the recognizer's own hypothesis starts arriving.
 *  * [TRANSCRIBING] — the microphone is closed and the words are being finished: the ordinary
 *    `UiState.Transcribing` stage, named the same way here so one indicator can cover both halves of a
 *    dictation without changing vocabulary halfway through.
 *  * [CHECK_NETWORK] — a cloud model was asked to go live and there is no usable network to go live on.
 *    Nothing is listening and nothing will be, so saying so at the moment the microphone is tapped is the
 *    only useful thing the indicator can do; the alternative is a batch dictation that dies minutes later
 *    at the end. Only ever reached for a provider reached over the network — an on-device model has no
 *    connection to check.
 */
enum class LiveVoicePhase {
    PLEASE_WAIT,
    SPEAK_NOW,
    LISTENING,
    TRANSCRIBING,
    CHECK_NETWORK,
}
