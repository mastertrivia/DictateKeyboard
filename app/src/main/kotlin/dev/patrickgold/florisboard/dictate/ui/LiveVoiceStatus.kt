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

package dev.patrickgold.florisboard.dictate.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.dictate.LiveVoicePhase
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import kotlin.math.PI
import kotlin.math.sin
import org.florisboard.lib.snygg.ui.rememberSnyggThemeQuery

/**
 * The live dictation indicator, split into the two things it has to say — and drawn in the two places
 * that can say them.
 *
 * HeliBoard's live microphone is three bars, and the bars are the honest thing to show: a dot can only
 * say "a recording exists", while bars of differing length say what the *voice* is doing, so the moment
 * the speaker stops is visible without waiting for a transcript. They are drawn from
 * [dev.patrickgold.florisboard.dictate.DictateController.liveVoiceLevel] — the same 20 Hz microphone
 * measurement the recording dot and the waveform already use — so the reaction is one-to-one with how loud
 * the speaker is, not a decorative loop. (For a system engine, whose recognizer owns the microphone, the
 * same flow carries its RMS instead; see [dev.patrickgold.florisboard.dictate.BasicVoiceHost].)
 *
 * **The glyph goes on the key, the words go on the bar.** [LiveVoiceGlyph] is the button's whole content
 * while a live dictation runs — the bars, a loader, or a crossed-out cloud — and [LiveVoiceStatus] is the
 * caption beside it, which carries the part a picture cannot: *which* of the states the engine is in, in
 * words, in the middle of the keyboard where there is room for them, plus the cross that gives up on the
 * whole thing. Splitting them this way is what keeps the caption free of symbols (a loader next to a word
 * that already says *Please wait* is one fact told twice) while the key stays the one control under the
 * user's thumb.
 */
@Composable
internal fun LiveVoiceStatus(
    phase: LiveVoicePhase,
    level: Float,
    color: Color,
    modifier: Modifier = Modifier,
    showBars: Boolean = true,
    /**
     * The cross that gives up on the dictation entirely (see `DictateController.abortLiveDictation`), or
     * null for a caller with no way to abort. Pinned to the far end of the row — which is the space
     * immediately left of the mic key — so it reads as "cancel this button", not as part of the sentence.
     */
    onAbort: (() -> Unit)? = null,
) {
    // A Box rather than a Row so the two halves can be laid out independently: the caption is centred in
    // whatever space the bar gives it, and the cross is at the very end of it regardless of how long the
    // caption is. Callers hand this a `fillMaxSize` modifier for that reason.
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showBars) {
                LiveVoiceBars(level = level, phase = phase, color = color)
            }
            LiveLabel(phaseLabel(phase))
        }
        if (onAbort != null) {
            // A plain clickable box rather than an IconButton: Material's button enforces a 48 dp touch
            // target, which is taller than the row this sits in and would push everything else around it.
            // The tint is the affordance; a ripple would be clipped at this size anyway.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onAbort),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.dictate__live_abort),
                    modifier = Modifier.size(17.dp),
                    tint = color,
                )
            }
        }
    }
}

/** The state word itself, centred in the bar. */
@Composable
private fun phaseLabel(phase: LiveVoicePhase): String = stringResource(
    when (phase) {
        LiveVoicePhase.PLEASE_WAIT -> R.string.dictate__live_please_wait
        LiveVoicePhase.SPEAK_NOW -> R.string.dictate__live_speak_now
        LiveVoicePhase.LISTENING -> R.string.dictate__live_listening
        LiveVoicePhase.TRANSCRIBING -> R.string.dictate__status_transcribing
        LiveVoicePhase.CHECK_NETWORK -> R.string.dictate__live_check_network
    },
)

/**
 * What the microphone key shows instead of its icon while a live dictation runs.
 *
 * One function rather than a `when` at the call site, because the key and the caption have to agree about
 * what each state looks like or the two halves of the indicator tell different stories: the key is the
 * state as a picture, the caption is the state in words, and both come from the same phase.
 */
@Composable
internal fun LiveVoiceGlyph(
    phase: LiveVoicePhase,
    level: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    when (phase) {
        // Starting up: a loader, and a deliberately *bigger, steadier* one than the transcribing state's.
        // The two are the same shape in the same colour, so the only way they can be told apart at a
        // glance is size and tempo — and they must be told apart, because one of them means "you can start
        // talking" and the other means "you have stopped and I am working".
        LiveVoicePhase.PLEASE_WAIT -> Box(modifier, contentAlignment = Alignment.Center) {
            Spinner(color = color, size = 30.dp, turnsPerSecond = 0.75f, varying = false)
        }
        // Working. Nothing is being captured any more, so there is no level to react to and the bars would
        // be a lie; the loader is the whole message.
        LiveVoicePhase.TRANSCRIBING -> Box(modifier, contentAlignment = Alignment.Center) {
            Spinner(color = color, size = 22.dp, turnsPerSecond = 1.15f, varying = true)
        }
        // Not listening and not going to: the one state whose picture is not a verb. A microphone would say
        // "speak" and a loader would say "wait", and both would be wrong.
        LiveVoicePhase.CHECK_NETWORK -> Box(modifier, contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(24.dp),
            )
        }
        // The voice itself. Sized up from the caption's bars: this key is a whole key, and the bars are
        // all of its content.
        LiveVoicePhase.SPEAK_NOW, LiveVoicePhase.LISTENING -> LiveVoiceBars(
            level = level,
            phase = phase,
            color = color,
            modifier = modifier,
            barWidth = 4.dp,
            barMax = 26.dp,
            barMin = 6.dp,
        )
    }
}

/**
 * The live bars' colour.
 *
 * Fixed rather than themed, because it is the one thing on the keyboard that has to read identically for
 * everyone: the bars are a *state* — "this dictation is live" — and a colour that followed the keyboard's
 * accent would make the same state look different from one theme to the next, or vanish into an accent
 * that happens to sit near the background. This is the blue HeliBoard's own voice bars read as.
 */
internal val LiveVoiceBlue = Color(0xFF3B82F6)

/** Default reach of a bar at full level, how short it falls when nothing is heard, and its width. */
private val BarMax = 20.dp
private val BarMin = 5.dp
private val BarWidth = 3.dp

/** Below this the microphone counts as silent and the bars breathe instead of reacting (see below). */
private const val LevelFloor = 0.03f

/** How much of the full range the breathing motion uses, so idle is visibly not speech. */
private const val BreathShare = 0.45f

/** Silhouette of the three bars — the middle one leads, as it does on HeliBoard's voice key. */
private val BarShape = listOf(0.62f, 1f, 0.78f)

/** Dimmed while nothing is being heard (waiting, offline, finishing), full strength while the mic is live. */
private fun barsAlpha(phase: LiveVoicePhase): Float = when (phase) {
    LiveVoicePhase.SPEAK_NOW, LiveVoicePhase.LISTENING -> 1f
    else -> 0.45f
}

/**
 * The three bars, fed by the live microphone level.
 *
 * The level is the *smoothed* one ([dev.patrickgold.florisboard.dictate.DictateController.audioLevel])
 * rather than the raw peak the scrolling waveform uses: bars that jump to every 50 ms gust read as noise,
 * while the smoother's short attack keeps them honest about a voice starting and its slower release makes
 * them fall the way a voice does.
 *
 * When there is no level to react to — a session whose microphone belongs to something else, or the
 * instant before the first frame — they breathe rather than freeze, because a still indicator on a live
 * session is indistinguishable from a dead one. The caption carries the real information in that case.
 */
@Composable
internal fun LiveVoiceBars(
    level: Float,
    phase: LiveVoicePhase,
    color: Color,
    modifier: Modifier = Modifier,
    /** The same three lines at two scales: small beside the caption, larger on the mic key. */
    barWidth: Dp = BarWidth,
    barMax: Dp = BarMax,
    barMin: Dp = BarMin,
) {
    val breath by rememberInfiniteTransition(label = "liveVoiceBars").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(760, easing = LinearEasing)),
        label = "liveVoiceBarsBreath",
    )
    val driving = if (level > LevelFloor) level.coerceIn(0f, 1f) else breath * BreathShare
    Row(
        modifier = modifier.alpha(barsAlpha(phase)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        BarShape.forEach { share ->
            Box(
                modifier = Modifier
                    .width(barWidth)
                    .height(barMin + (barMax - barMin) * (driving * share).coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(percent = 50))
                    .background(color),
            )
        }
    }
}

/**
 * The state word itself, weighted like the bars beside it rather than like a caption.
 *
 * Themed rather than fixed: the caption is read, so it takes the Smartbar row's own foreground — the same
 * colour the timer and the status words beside it use, from the Snygg theme the keyboard is painted with.
 * The app's Material scheme has nothing to do with what is legible on a keyboard theme, and the blue is
 * reserved for the things that mean "live".
 */
@Composable
private fun LiveLabel(text: String) {
    val color = rememberSnyggThemeQuery(FlorisImeUi.SmartbarSharedActionsRow.elementName).foreground()
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = color,
        maxLines = 1,
    )
}

/**
 * The round loader, at a rate the caller chooses.
 *
 * A spinner at a fixed rate stops being seen after a few seconds: the eye edits out what does not change,
 * and the wait then reads as *stuck*. So the working loader varies its rotation on a slow sine — one
 * slow-fast sweep every [SlowFastPeriodS] seconds — which is the same trick chat interfaces use for the
 * same reason. It never claims how much is left, because nothing knows.
 *
 * The starting-up loader deliberately does **not** do that ([varying] = false). It is a different claim:
 * *we are getting there*, not *work is happening*, and it is normally over in a second or two. A steady
 * tick that ends quickly reads as startup; the same sine in the same colour as the transcribing loader
 * would only make the two indistinguishable at the moment the user is deciding whether it is their turn
 * to speak.
 */
@Composable
private fun Spinner(
    color: Color,
    size: Dp,
    turnsPerSecond: Float,
    varying: Boolean,
) {
    val angle = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(varying, turnsPerSecond) {
        var previousNanos = 0L
        while (true) {
            withFrameNanos { now ->
                if (previousNanos != 0L) {
                    val seconds = (now - previousNanos) / 1_000_000_000f
                    val t = now / 1_000_000_000f
                    val velocity = if (varying) {
                        // 0.35× to 1.65× the base rate: wide enough to be felt, never so slow at the
                        // bottom that it looks stalled.
                        1f + 0.65f * sin(t * 2f * PI.toFloat() / SlowFastPeriodS)
                    } else {
                        1f
                    }
                    angle.value = (angle.value + turnsPerSecond * velocity * seconds * 360f) % 360f
                }
                previousNanos = now
            }
        }
    }
    Canvas(modifier = Modifier.size(size)) {
        val stroke = this.size.minDimension * 0.16f
        val inset = stroke / 2f
        val ringSize = Size(this.size.width - stroke, this.size.height - stroke)
        // A nearly-full ring with one gap: the quarter that is missing is what makes the rotation visible
        // at all, and the faint track keeps the gap from reading as a broken circle.
        drawArc(
            color = color.copy(alpha = 0.25f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = ringSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawArc(
            color = color,
            startAngle = angle.value,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = ringSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/** How long one slow-fast sweep of the working loader takes. */
private const val SlowFastPeriodS = 2.5f
