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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
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
import com.ultrapg.luna.core.settings.LunaSettings
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

private val PillDarkBg = Color(0xF5111318)
private val PillSubtleBorder = Color(0xFF282C37)
private val MicButtonBlue = Color(0xFF2B5CAD)
private val MicButtonActiveRed = Color(0xFFC63F47)

/**
 * Clean, simple Gemini-style Pill Overlay with no fancy glow:
 * - Matte dark capsule (`#111318`) with a subtle 1dp dark border
 * - Center row of 11 clean white vertical audio-reactive bars (`• | | | | | | | | •`)
 * - Circular blue microphone button on the right and a minimal dismiss button on the left
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
        AssistantOverlayPhase.LISTENING -> max(micRms, 0.12f)
        AssistantOverlayPhase.SPEAKING -> max(ttsRms, 0.20f)
        AssistantOverlayPhase.THINKING, AssistantOverlayPhase.TRANSCRIBING -> 0.35f
        else -> if (isSpeaking) max(ttsRms, 0.20f) else 0.05f
    }

    val smoothedAudio by animateFloatAsState(
        targetValue = rawLevel.coerceIn(0.04f, 1.0f),
        animationSpec = tween(durationMillis = 75, easing = FastOutSlowInEasing),
        label = "pillAudio"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "barWave")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val scale = settings.bubbleScale.coerceIn(0.70f, 1.40f)
    val isExpanded = liveState.isExpanded

    val targetWidth by animateDpAsState(
        targetValue = if (isExpanded) {
            (settings.bubbleExpandedWidthDp.coerceIn(140, 300) * scale).dp
        } else {
            (settings.bubbleWidthDp.coerceIn(28, 72) * scale).dp
        },
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "pillW"
    )

    val targetHeight by animateDpAsState(
        targetValue = if (isExpanded) {
            (settings.bubbleExpandedHeightDp.coerceIn(44, 84) * scale).dp
        } else {
            (settings.bubbleHeightDp.coerceIn(64, 160) * scale).dp
        },
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "pillH"
    )

    val pillShape = RoundedCornerShape(percent = 50)

    Box(
        modifier = Modifier
            .width(targetWidth)
            .height(targetHeight)
            .shadow(elevation = 12.dp, shape = pillShape, clip = false)
            .clip(pillShape)
            .background(PillDarkBg)
            .border(width = 1.dp, color = PillSubtleBorder, shape = pillShape)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDragDelta(dragAmount.x, dragAmount.y)
                }
            }
            .combinedClickable(
                onClick = {
                    if (!isExpanded) {
                        onToggleExpand()
                        LunaAssistantController.triggerFromSideButton(context, startListeningImmediately = true)
                    } else {
                        LunaAssistantController.toggleListeningFromPill(context)
                    }
                },
                onLongClick = { onDismiss() }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (!isExpanded) {
            // Compact vertical edge pill
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                VerticalEqualizerDots(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    audioAmplitude = smoothedAudio,
                    phase = phase
                )

                Box(
                    modifier = Modifier
                        .size((24 * scale).dp)
                        .clip(CircleShape)
                        .background(MicButtonBlue),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Luna",
                        tint = Color.White,
                        modifier = Modifier.size((14 * scale).dp)
                    )
                }
            }
        } else {
            // Simple horizontal Gemini-style pill: [ X ]   • | | | | | | | | •   [ (Mic) ]
            val micBtnSize = (targetHeight - 12.dp).coerceIn(32.dp, 54.dp)
            val isThinking = liveState.phase == AssistantOverlayPhase.THINKING ||
                liveState.phase == AssistantOverlayPhase.TRANSCRIBING

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: Minimal dismiss button
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color(0xFFD5D8E0),
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Center: 11 white vertical equalizer bars (• | | | | | | | | •)
                HorizontalEqualizerBars(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = 12.dp),
                    audioAmplitude = smoothedAudio,
                    waveBands = waveBands,
                    phase = phase,
                    isAnimated = liveState.phase == AssistantOverlayPhase.LISTENING ||
                        liveState.phase == AssistantOverlayPhase.SPEAKING ||
                        isThinking ||
                        isSpeaking
                )

                // Right: Circular blue Microphone button
                Box(
                    modifier = Modifier
                        .size(micBtnSize)
                        .clip(CircleShape)
                        .background(
                            if (liveState.phase == AssistantOverlayPhase.SPEAKING) {
                                MicButtonActiveRed
                            } else {
                                MicButtonBlue
                            }
                        )
                        .clickable {
                            LunaAssistantController.toggleListeningFromPill(context)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (liveState.phase == AssistantOverlayPhase.SPEAKING) {
                            Icons.Default.Stop
                        } else {
                            Icons.Default.Mic
                        },
                        contentDescription = "Microphone",
                        tint = Color.White,
                        modifier = Modifier.size(micBtnSize * 0.50f)
                    )
                }
            }
        }
    }
}

@Composable
private fun HorizontalEqualizerBars(
    modifier: Modifier,
    audioAmplitude: Float,
    waveBands: FloatArray,
    phase: Float,
    isAnimated: Boolean
) {
    // Bell-curve base profile matching Gemini's `• | | | | | | | | •` bar heights
    val baseProfile = floatArrayOf(
        0.10f, 0.22f, 0.36f, 0.52f, 0.72f, 0.92f, 0.72f, 0.52f, 0.36f, 0.22f, 0.10f
    )

    Canvas(modifier = modifier) {
        val count = baseProfile.size
        val barWidth = 2.6.dp.toPx()
        val totalSpan = size.width
        val step = totalSpan / (count + 1)
        val cy = size.height / 2f
        val maxBarHeight = size.height * 0.54f
        val minDotHeight = 3.0.dp.toPx()

        for (i in 0 until count) {
            val x = step * (i + 1) - barWidth / 2f
            val base = baseProfile[i]
            val bandIdx = if (waveBands.isNotEmpty()) (i % waveBands.size) else 0
            val bandEnergy = if (waveBands.isNotEmpty()) waveBands[bandIdx] else audioAmplitude

            val waveFactor = if (isAnimated) {
                val osc = 0.55f + 0.45f * abs(sin(phase + i * 0.55f)).toFloat()
                val dynamic = (0.35f + (audioAmplitude * 0.55f + bandEnergy * 0.45f) * 0.95f).coerceIn(0.25f, 1.25f)
                base * osc * dynamic
            } else {
                base * 0.55f
            }

            val barH = (minDotHeight + maxBarHeight * waveFactor).coerceIn(minDotHeight, maxBarHeight)
            drawRoundRect(
                color = Color(0xFFE8EAED),
                topLeft = Offset(x, cy - barH / 2f),
                size = Size(barWidth, barH),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

@Composable
private fun VerticalEqualizerDots(
    modifier: Modifier,
    audioAmplitude: Float,
    phase: Float
) {
    val baseProfile = floatArrayOf(0.18f, 0.42f, 0.75f, 0.95f, 0.75f, 0.42f, 0.18f)
    Canvas(modifier = modifier) {
        val count = baseProfile.size
        val barThickness = 2.4.dp.toPx()
        val step = size.height / (count + 1)
        val cx = size.width / 2f
        val maxBarWidth = size.width * 0.65f
        val minDotWidth = 3.0.dp.toPx()

        for (i in 0 until count) {
            val y = step * (i + 1) - barThickness / 2f
            val osc = 0.65f + 0.35f * abs(sin(phase + i * 0.6f)).toFloat()
            val w = (minDotWidth + maxBarWidth * baseProfile[i] * osc * (0.45f + audioAmplitude * 0.55f))
                .coerceIn(minDotWidth, maxBarWidth)
            drawRoundRect(
                color = Color(0xFFE8EAED),
                topLeft = Offset(cx - w / 2f, y),
                size = Size(w, barThickness),
                cornerRadius = CornerRadius(barThickness / 2f, barThickness / 2f)
            )
        }
    }
}
