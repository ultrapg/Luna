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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * Horizontal Side-Notch Bubble Overlay:
 * - Docks flush against the left or right screen edge like a hardware side-button notch
 * - Zero obvious buttons inside:
 *   - Tap anywhere on the bubble while listening -> immediately sends & processes speech
 *   - Tap while speaking -> stops TTS
 *   - Tap while idle/result -> starts listening again
 *   - Long-press anywhere -> dismisses the bubble
 *   - Drag vertically or horizontally -> moves along the screen edge and dynamically snaps to Left or Right edge
 * - Dynamically expands (`animateContentSize`) when researching, thinking, or speaking out the response
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
        AssistantOverlayPhase.LISTENING -> max(micRms, 0.14f)
        AssistantOverlayPhase.SPEAKING -> if (isSpeaking) max(ttsRms, 0.22f) else 0.05f
        AssistantOverlayPhase.THINKING, AssistantOverlayPhase.TRANSCRIBING -> 0.32f
        else -> if (isSpeaking) max(ttsRms, 0.22f) else 0.05f
    }

    val smoothedAudio by animateFloatAsState(
        targetValue = rawLevel.coerceIn(0.04f, 1.0f),
        animationSpec = tween(durationMillis = 70, easing = FastOutSlowInEasing),
        label = "notchAudio"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "notchWave")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 860, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    val scale = settings.bubbleScale.coerceIn(0.70f, 1.40f)
    val isExpanded = liveState.isExpanded || liveState.phase != AssistantOverlayPhase.IDLE_NOTCH

    val hasDetailContent = liveState.phase == AssistantOverlayPhase.TRANSCRIBING ||
        liveState.phase == AssistantOverlayPhase.THINKING ||
        liveState.phase == AssistantOverlayPhase.SPEAKING ||
        liveState.phase == AssistantOverlayPhase.RESULT_CARD ||
        liveState.statusBadge.isNotBlank() ||
        liveState.assistantReply.isNotBlank()

    val baseCompactWidth = (settings.bubbleWidthDp.coerceIn(48, 120) * scale).dp
    val baseListeningWidth = (settings.bubbleExpandedWidthDp.coerceIn(120, 280) * scale).dp
    val baseDetailMaxWidth = (268 * scale).coerceIn(200f, 330f).dp
    val barRowHeight = (settings.bubbleExpandedHeightDp.coerceIn(34, 68) * scale).dp
    val compactHeight = (settings.bubbleHeightDp.coerceIn(28, 60) * scale).dp

    val cornerRadius = 22.dp
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
        BubbleEdge.FREE -> RoundedCornerShape(cornerRadius)
    }

    val isAnimated = liveState.phase == AssistantOverlayPhase.LISTENING ||
        liveState.phase == AssistantOverlayPhase.THINKING ||
        liveState.phase == AssistantOverlayPhase.TRANSCRIBING ||
        isSpeaking

    val statusDotColor = when {
        liveState.phase == AssistantOverlayPhase.LISTENING -> AccentDotBlue
        liveState.phase == AssistantOverlayPhase.TRANSCRIBING ||
            liveState.phase == AssistantOverlayPhase.THINKING -> AccentDotAmber
        isSpeaking -> AccentDotGreen
        else -> Color(0xFF8E95A5)
    }

    Box(
        modifier = Modifier
            .animateContentSize(
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
            )
            .then(
                when {
                    !isExpanded -> Modifier
                        .width(baseCompactWidth)
                        .height(compactHeight)
                    hasDetailContent -> Modifier
                        .widthIn(min = baseListeningWidth, max = baseDetailMaxWidth)
                    else -> Modifier
                        .width(baseListeningWidth)
                        .height(barRowHeight)
                }
            )
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
                    if (!isExpanded) {
                        onToggleExpand()
                        LunaAssistantController.triggerFromSideButton(
                            context,
                            startListeningImmediately = true
                        )
                    } else {
                        // Clicking anywhere on the horizontal notch sends when listening,
                        // stops TTS when speaking, or starts listening again when idle/done.
                        LunaAssistantController.toggleListeningFromPill(context)
                    }
                },
                onLongClick = { onDismiss() }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (!isExpanded) {
            // Compact horizontal side-notch: subtle 7-bar equalizer flush with screen edge
            HorizontalEqualizerBars(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(compactHeight)
                    .padding(horizontal = 10.dp),
                barCount = 7,
                audioAmplitude = smoothedAudio,
                waveBands = waveBands,
                phase = phase,
                isAnimated = isAnimated
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (settings.bubbleEdge == BubbleEdge.LEFT) 10.dp else 14.dp,
                        end = if (settings.bubbleEdge == BubbleEdge.RIGHT) 10.dp else 14.dp,
                        top = 8.dp,
                        bottom = 8.dp
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Row: Subtle status indicator dot + 11 clean white vertical equalizer bars (`• | | | | | | | | •`)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((barRowHeight - 16.dp).coerceIn(22.dp, 38.dp)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(statusDotColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    HorizontalEqualizerBars(
                        modifier = Modifier
                            .weight(1f)
                            .height((barRowHeight - 16.dp).coerceIn(22.dp, 38.dp)),
                        barCount = 11,
                        audioAmplitude = smoothedAudio,
                        waveBands = waveBands,
                        phase = phase,
                        isAnimated = isAnimated
                    )
                }

                // Dynamic expansion section for Researching, Thinking, User Transcript & Spoken Reply
                AnimatedVisibility(
                    visible = hasDetailContent && (
                        liveState.phase != AssistantOverlayPhase.LISTENING ||
                            liveState.userTranscript.isNotBlank()
                        ),
                    enter = fadeIn(tween(150)),
                    exit = fadeOut(tween(120))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Show what the user asked when researching or answering
                        if (liveState.userTranscript.isNotBlank()) {
                            Text(
                                text = liveState.userTranscript,
                                color = Color(0xFF9AA0AC),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Show active research / tool status (e.g. DuckDuckGo search, reading website, thinking)
                        if (liveState.statusBadge.isNotBlank() &&
                            liveState.phase != AssistantOverlayPhase.LISTENING
                        ) {
                            Text(
                                text = liveState.statusBadge,
                                color = AccentDotBlue,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Show executed research / Android tool badges compactly
                        if (liveState.executedTools.isNotEmpty() && liveState.assistantReply.isBlank()) {
                            Text(
                                text = liveState.executedTools.last(),
                                color = Color(0xFFB5BCC9),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Show Luna's spoken response inside the dynamically expanded notch
                        if (liveState.assistantReply.isNotBlank()) {
                            val scrollState = rememberScrollState()
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 130.dp)
                                    .verticalScroll(scrollState)
                            ) {
                                Text(
                                    text = liveState.assistantReply,
                                    color = Color(0xFFF1F3F7),
                                    fontSize = 12.5.sp,
                                    lineHeight = 17.sp,
                                    fontWeight = FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HorizontalEqualizerBars(
    modifier: Modifier,
    barCount: Int = 11,
    audioAmplitude: Float,
    waveBands: FloatArray,
    phase: Float,
    isAnimated: Boolean
) {
    // Bell-curve base profile matching Gemini's `• | | | | | | | | •` bar heights
    val baseProfile = if (barCount == 7) {
        floatArrayOf(0.14f, 0.34f, 0.68f, 0.92f, 0.68f, 0.34f, 0.14f)
    } else {
        floatArrayOf(0.10f, 0.22f, 0.36f, 0.52f, 0.72f, 0.92f, 0.72f, 0.52f, 0.36f, 0.22f, 0.10f)
    }

    Canvas(modifier = modifier) {
        val count = baseProfile.size
        val barWidth = 2.6.dp.toPx()
        val totalSpan = size.width
        val step = totalSpan / (count + 1)
        val cy = size.height / 2f
        val maxBarHeight = size.height * 0.76f
        val minDotHeight = 3.0.dp.toPx()

        for (i in 0 until count) {
            val x = step * (i + 1) - barWidth / 2f
            val base = baseProfile[i]
            val bandIdx = if (waveBands.isNotEmpty()) (i % waveBands.size) else 0
            val bandEnergy = if (waveBands.isNotEmpty()) waveBands[bandIdx] else audioAmplitude

            val waveFactor = if (isAnimated) {
                val osc = 0.55f + 0.45f * abs(sin(phase + i * 0.55f)).toFloat()
                val dynamic = (0.35f + (audioAmplitude * 0.55f + bandEnergy * 0.45f) * 0.95f)
                    .coerceIn(0.25f, 1.25f)
                base * osc * dynamic
            } else {
                base * 0.48f
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
