/*
 * Luna — Native Android Digital Assistant
 * Copyright (C) 2026 ultrapg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.ultrapg.luna.overlay

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ultrapg.luna.assistant.AssistantLiveState
import com.ultrapg.luna.assistant.AssistantOverlayPhase
import com.ultrapg.luna.assistant.LunaAssistantController
import com.ultrapg.luna.core.settings.BubbleEdge
import com.ultrapg.luna.core.settings.LunaSettings
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

private val NotchDarkBg = Color(0xF5111318)
private val NotchSubtleBorder = Color(0xFF282C37)
private val AccentDotBlue = Color(0xFF7AA2F7)
private val AccentDotGreen = Color(0xFF4ADE80)
private val AccentDotAmber = Color(0xFFFBBF24)

/**
 * Slim Vertical Side-Notch Bubble Overlay:
 * - Tall and slim (`~34dp x 136dp`), snapped flush against the left or right screen edge like a hardware side-button notch
 * - Zero text and zero obvious buttons inside:
 *   - Tap anywhere on the bubble while listening -> immediately sends & processes speech
 *   - Tap while speaking -> stops TTS and dismisses
 *   - Tap while idle -> starts listening
 *   - Tapping anywhere outside the bubble on the screen immediately dismisses the bubble & stops the assistant
 *   - Drag vertically or horizontally -> moves along the screen edge and dynamically snaps to Left or Right edge
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GeminiSidePillOverlay(
    liveState: AssistantLiveState,
    settings: LunaSettings,
    micRms: Float,
    waveBands: FloatArray,
    ttsRms: Float,
    isSpeaking: Boolean,
    onDragDelta: (Float, Float) -> Unit,
    onToggleExpand: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    val rawLevel = when (liveState.phase) {
        AssistantOverlayPhase.LISTENING -> max(micRms, 0.16f)
        AssistantOverlayPhase.SPEAKING -> if (isSpeaking) max(ttsRms, 0.24f) else 0.06f
        AssistantOverlayPhase.THINKING, AssistantOverlayPhase.TRANSCRIBING -> 0.42f
        else -> if (isSpeaking) max(ttsRms, 0.24f) else 0.06f
    }

    val smoothedAudio by animateFloatAsState(
        targetValue = rawLevel.coerceIn(0.05f, 1.0f),
        animationSpec = tween(durationMillis = 70, easing = FastOutSlowInEasing),
        label = "verticalNotchAudio"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "verticalNotchWave")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 820, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    val scale = settings.bubbleScale.coerceIn(0.70f, 1.40f)
    val isActive = liveState.isExpanded || liveState.phase != AssistantOverlayPhase.IDLE_NOTCH

    val targetWidth by animateDpAsState(
        targetValue = if (isActive) {
            (settings.bubbleExpandedWidthDp.coerceIn(24, 54) * scale).dp
        } else {
            (settings.bubbleWidthDp.coerceIn(20, 48) * scale).dp
        },
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "notchW"
    )

    val targetHeight by animateDpAsState(
        targetValue = if (isActive) {
            (settings.bubbleExpandedHeightDp.coerceIn(88, 220) * scale).dp
        } else {
            (settings.bubbleHeightDp.coerceIn(72, 180) * scale).dp
        },
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "notchH"
    )

    val cornerRadius = 18.dp
    val notchShape = when (settings.bubbleEdge) {
        BubbleEdge.RIGHT -> RoundedCornerShape(
            topStart = cornerRadius,
            bottomStart = cornerRadius,
            topEnd = 0.dp,
            bottomEnd = 0.dp
        )
        BubbleEdge.LEFT -> RoundedCornerShape(
            topStart = 0.dp,
            bottomStart = 0.dp,
            topEnd = cornerRadius,
            bottomEnd = cornerRadius
        )
        BubbleEdge.FREE -> RoundedCornerShape(percent = 50)
    }

    val isThinking = liveState.phase == AssistantOverlayPhase.THINKING ||
        liveState.phase == AssistantOverlayPhase.TRANSCRIBING

    val isAnimated = liveState.phase == AssistantOverlayPhase.LISTENING ||
        isThinking ||
        isSpeaking

    val statusDotColor = when {
        liveState.phase == AssistantOverlayPhase.LISTENING -> AccentDotBlue
        isThinking -> AccentDotAmber
        isSpeaking -> AccentDotGreen
        else -> Color(0xFF8E95A5)
    }

    val barColor = if (isThinking) {
        Color(0xFFFDE68A)
    } else {
        Color(0xFFE8EAED)
    }

    Box(
        modifier = Modifier
            .width(targetWidth)
            .height(targetHeight)
            .shadow(elevation = 10.dp, shape = notchShape, clip = false)
            .clip(notchShape)
            .background(NotchDarkBg)
            .border(width = 1.dp, color = NotchSubtleBorder, shape = notchShape)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDragDelta(dragAmount.x, dragAmount.y)
                }
            }
            .combinedClickable(
                onClick = {
                    if (!isActive) {
                        onToggleExpand()
                        LunaAssistantController.triggerFromSideButton(
                            context,
                            startListeningImmediately = true
                        )
                    } else {
                        LunaAssistantController.toggleListeningFromPill(context)
                    }
                },
                onLongClick = { onDismiss() }
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = 10.dp,
                    bottom = 10.dp,
                    start = if (settings.bubbleEdge == BubbleEdge.LEFT) 4.dp else 6.dp,
                    end = if (settings.bubbleEdge == BubbleEdge.RIGHT) 4.dp else 6.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(statusDotColor)
            )
            Spacer(modifier = Modifier.height(6.dp))
            VerticalEqualizerBars(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                barCount = if (isActive) 11 else 7,
                audioAmplitude = smoothedAudio,
                waveBands = waveBands,
                phase = phase,
                isAnimated = isAnimated,
                barColor = barColor
            )
        }
    }
}

@Composable
private fun VerticalEqualizerBars(
    modifier: Modifier,
    barCount: Int = 11,
    audioAmplitude: Float,
    waveBands: FloatArray,
    phase: Float,
    isAnimated: Boolean,
    barColor: Color
) {
    // Bell-curve profile arranged vertically from top to bottom inside the slim side notch
    val baseProfile = if (barCount == 7) {
        floatArrayOf(0.16f, 0.36f, 0.68f, 0.94f, 0.68f, 0.36f, 0.16f)
    } else {
        floatArrayOf(0.12f, 0.24f, 0.38f, 0.54f, 0.74f, 0.94f, 0.74f, 0.54f, 0.38f, 0.24f, 0.12f)
    }

    Canvas(modifier = modifier) {
        val count = baseProfile.size
        val barThickness = 2.6.dp.toPx()
        val totalSpan = size.height
        val step = totalSpan / (count + 1)
        val cx = size.width / 2f
        val maxBarWidth = size.width * 0.78f
        val minDotWidth = 3.2.dp.toPx()

        for (i in 0 until count) {
            val y = step * (i + 1) - barThickness / 2f
            val base = baseProfile[i]
            val bandIdx = if (waveBands.isNotEmpty()) (i % waveBands.size) else 0
            val bandEnergy = if (waveBands.isNotEmpty()) waveBands[bandIdx] else audioAmplitude

            val waveFactor = if (isAnimated) {
                val osc = 0.50f + 0.50f * abs(sin(phase + i * 0.55f)).toFloat()
                val dynamic = (0.35f + (audioAmplitude * 0.55f + bandEnergy * 0.45f) * 0.95f)
                    .coerceIn(0.28f, 1.25f)
                base * osc * dynamic
            } else {
                base * 0.45f
            }

            val barW = (minDotWidth + maxBarWidth * waveFactor).coerceIn(minDotWidth, maxBarWidth)
            drawRoundRect(
                color = barColor,
                topLeft = Offset(cx - barW / 2f, y),
                size = Size(barW, barThickness),
                cornerRadius = CornerRadius(barThickness / 2f, barThickness / 2f)
            )
        }
    }
}
