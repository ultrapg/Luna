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

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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

// Material Design 3 dark surface container & accent tokens
private val NotchDarkBg = Color(0xF513151B)
private val NotchSubtleBorder = Color(0xFF2B303C)
private val ListeningWhite = Color(0xFFE8EAED)
private val SpeakingLightBlue = Color(0xFF7DC4FF)

/**
 * Slim Vertical Side-Notch Bubble Overlay with Material Design 3 spatial physics:
 * - Pops out from the screen edge with M3 spring physics when triggered
 * - Reacts to voice/TTS amplitude, touch press, and drag with subtle M3 spring motion
 * - Fades away and retracts smoothly into the screen edge after speaking or when dismissed
 * - Listening: white vertical equalizer bars (`ListeningWhite`)
 * - Thinking / Transcribing: white stretched pill-shaped loading symbol (`StretchedPillLoadingIndicator`)
 * - Speaking: light-blue vertical equalizer visualizer bars (`SpeakingLightBlue`)
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

    // Entrance & exit state for M3 side-edge pop-out and fade-away
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        entered = true
    }
    LaunchedEffect(liveState.isVisible, liveState.isDismissing) {
        if (liveState.isVisible && !liveState.isDismissing) {
            entered = true
        }
    }

    val isShown = entered && liveState.isVisible && !liveState.isDismissing
    val presenceProgress by animateFloatAsState(
        targetValue = if (isShown) 1f else 0f,
        animationSpec = if (isShown) {
            // M3 Spatial Spring: crisp pop-out from the hardware side edge
            spring(
                dampingRatio = 0.68f,
                stiffness = 380f
            )
        } else {
            // Smooth M3 fade-away and retract into the edge
            tween(durationMillis = 250, easing = FastOutSlowInEasing)
        },
        label = "notchPresence"
    )

    val isThinking = liveState.phase == AssistantOverlayPhase.THINKING ||
        liveState.phase == AssistantOverlayPhase.TRANSCRIBING
    val isSpeakingNow = liveState.phase == AssistantOverlayPhase.SPEAKING || isSpeaking
    val isAnimated = liveState.phase == AssistantOverlayPhase.LISTENING || isSpeakingNow

    val rawLevel = when {
        liveState.phase == AssistantOverlayPhase.LISTENING -> max(micRms, 0.16f)
        isSpeakingNow -> max(ttsRms, 0.24f)
        isThinking -> 0.36f
        else -> 0.06f
    }

    val smoothedAudio by animateFloatAsState(
        targetValue = rawLevel.coerceIn(0.05f, 1.0f),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "verticalNotchAudio"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "verticalNotchWave")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 780, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    val thinkingProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 950, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "thinkingPillProgress"
    )

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    var dragVelocityY by remember { mutableFloatStateOf(0f) }

    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1f,
        animationSpec = spring(
            dampingRatio = 0.62f,
            stiffness = 520f
        ),
        label = "notchPressScale"
    )

    val dragStretchY by animateFloatAsState(
        targetValue = (1f + (abs(dragVelocityY) / 1400f).coerceIn(0f, 0.09f)),
        animationSpec = spring(
            dampingRatio = 0.65f,
            stiffness = 400f
        ),
        label = "notchDragStretch"
    )

    val scale = settings.bubbleScale.coerceIn(0.70f, 1.40f)
    val isActive = (liveState.isExpanded || liveState.phase != AssistantOverlayPhase.IDLE_NOTCH) &&
        !liveState.isDismissing

    // Subtle M3 audio-reactive breathing on the pill dimensions while listening or speaking
    val audioBreathW = if (isAnimated) (smoothedAudio * 3.2f) else 0f
    val audioBreathH = if (isAnimated) (smoothedAudio * 8.5f) else 0f

    val targetWidth by animateDpAsState(
        targetValue = if (isActive) {
            ((settings.bubbleExpandedWidthDp.coerceIn(24, 54) + audioBreathW) * scale).dp
        } else {
            (settings.bubbleWidthDp.coerceIn(20, 48) * scale).dp
        },
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = 400f
        ),
        label = "notchW"
    )

    val targetHeight by animateDpAsState(
        targetValue = if (isActive) {
            ((settings.bubbleExpandedHeightDp.coerceIn(88, 220) + audioBreathH) * scale).dp
        } else {
            (settings.bubbleHeightDp.coerceIn(72, 180) * scale).dp
        },
        animationSpec = spring(
            dampingRatio = 0.70f,
            stiffness = 360f
        ),
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

    val barColor by animateColorAsState(
        targetValue = if (isSpeakingNow) SpeakingLightBlue else ListeningWhite,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "barColorTransition"
    )

    val edgeOrigin = when (settings.bubbleEdge) {
        BubbleEdge.RIGHT -> TransformOrigin(1f, 0.5f)
        BubbleEdge.LEFT -> TransformOrigin(0f, 0.5f)
        BubbleEdge.FREE -> TransformOrigin(0.5f, 0.5f)
    }

    Box(
        modifier = Modifier
            .width(targetWidth)
            .height(targetHeight)
            .graphicsLayer {
                transformOrigin = edgeOrigin
                val clampedAlpha = presenceProgress.coerceIn(0f, 1f)
                alpha = clampedAlpha
                val slideSign = when (settings.bubbleEdge) {
                    BubbleEdge.RIGHT -> 1f
                    BubbleEdge.LEFT -> -1f
                    BubbleEdge.FREE -> 0f
                }
                // Pop out from the screen edge on appear, retract into the screen edge on dismiss
                translationX = (1f - presenceProgress) * size.width * 0.95f * slideSign
                scaleX = (0.76f + 0.24f * presenceProgress) * pressScale
                scaleY = (0.80f + 0.20f * presenceProgress) * pressScale * dragStretchY
            }
            .shadow(elevation = 10.dp, shape = notchShape, clip = false)
            .clip(notchShape)
            .background(NotchDarkBg)
            .border(width = 1.dp, color = NotchSubtleBorder, shape = notchShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { dragVelocityY = 0f },
                    onDragCancel = { dragVelocityY = 0f },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragVelocityY = dragAmount.y * 12f
                        onDragDelta(dragAmount.x, dragAmount.y)
                    }
                )
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
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
        if (isThinking) {
            // White stretched pill-shaped loading symbol during thinking/transcribing
            StretchedPillLoadingIndicator(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 14.dp, horizontal = 6.dp),
                progress = thinkingProgress,
                color = ListeningWhite
            )
        } else {
            // Equalizer bars: White while listening, Light Blue while speaking
            VerticalEqualizerBars(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = 12.dp,
                        bottom = 12.dp,
                        start = if (settings.bubbleEdge == BubbleEdge.LEFT) 4.dp else 6.dp,
                        end = if (settings.bubbleEdge == BubbleEdge.RIGHT) 4.dp else 6.dp
                    ),
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

/**
 * White stretched pill-shaped loading symbol for the thinking state.
 * Smoothly stretches into an elongated capsule in the middle of its travel and contracts at the ends.
 */
@Composable
private fun StretchedPillLoadingIndicator(
    modifier: Modifier,
    progress: Float,
    color: Color
) {
    Canvas(modifier = modifier) {
        val pillWidth = (size.width * 0.42f).coerceIn(5.dp.toPx(), 10.dp.toPx())
        // Stretch factor peaks in the middle of the travel (progress = 0.5)
        val stretchFactor = 1f - 2f * abs(progress - 0.5f)
        val minPillHeight = (size.height * 0.22f).coerceAtLeast(pillWidth * 1.8f)
        val maxPillHeight = (size.height * 0.58f).coerceAtLeast(minPillHeight)
        val currentPillHeight = minPillHeight + (maxPillHeight - minPillHeight) * stretchFactor

        val travelSpan = (size.height - currentPillHeight).coerceAtLeast(0f)
        val topY = travelSpan * progress
        val leftX = (size.width - pillWidth) / 2f

        // Subtle track pill behind the active stretched pill
        drawRoundRect(
            color = color.copy(alpha = 0.16f),
            topLeft = Offset(leftX, 0f),
            size = Size(pillWidth, size.height),
            cornerRadius = CornerRadius(pillWidth / 2f, pillWidth / 2f)
        )

        // Active white stretched pill
        drawRoundRect(
            color = color,
            topLeft = Offset(leftX, topY),
            size = Size(pillWidth, currentPillHeight),
            cornerRadius = CornerRadius(pillWidth / 2f, pillWidth / 2f)
        )
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
        val maxBarWidth = size.width * 0.80f
        val minDotWidth = 3.2.dp.toPx()

        for (i in 0 until count) {
            val y = step * (i + 1) - barThickness / 2f
            val base = baseProfile[i]
            val bandIdx = if (waveBands.isNotEmpty()) (i % waveBands.size) else 0
            val bandEnergy = if (waveBands.isNotEmpty()) waveBands[bandIdx] else audioAmplitude

            val waveFactor = if (isAnimated) {
                val osc = 0.46f + 0.54f * abs(sin(phase + i * 0.58f)).toFloat()
                val dynamic = (0.32f + (audioAmplitude * 0.55f + bandEnergy * 0.50f) * 1.02f)
                    .coerceIn(0.28f, 1.28f)
                base * osc * dynamic
            } else {
                base * 0.40f
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
