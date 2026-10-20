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

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ultrapg.luna.MainActivity
import com.ultrapg.luna.assistant.AssistantLiveState
import com.ultrapg.luna.assistant.AssistantOverlayPhase
import com.ultrapg.luna.assistant.LunaAssistantController
import com.ultrapg.luna.core.settings.BubbleEdge
import com.ultrapg.luna.core.settings.LunaSettings
import com.ultrapg.luna.core.speech.LunaTtsEngine
import kotlin.math.PI
import kotlin.math.sin

// Authentic Gemini 4-color luminous gradient palette
private val GeminiBlue = Color(0xFF4E82EE)
private val GeminiViolet = Color(0xFF9B72CB)
private val GeminiCoral = Color(0xFFD96570)
private val GeminiCyan = Color(0xFF56C8FF)
private val DeepGlassBg = Color(0xEE0D111E)

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
    val isDe = settings.appLanguage.isGerman
    val activeAudioLevel = when (liveState.phase) {
        AssistantOverlayPhase.LISTENING -> micRms
        AssistantOverlayPhase.SPEAKING -> ttsRms
        AssistantOverlayPhase.THINKING, AssistantOverlayPhase.TRANSCRIBING -> 0.42f
        else -> if (isSpeaking) ttsRms else 0.08f
    }

    val smoothedAudio by animateFloatAsState(
        targetValue = activeAudioLevel.coerceIn(0.05f, 1.0f),
        animationSpec = tween(durationMillis = 90, easing = FastOutSlowInEasing),
        label = "audioLevel"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "geminiGlow")
    val glowRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "glowRotation"
    )

    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    val scale = settings.bubbleScale.coerceIn(0.6f, 1.8f)

    if (!liveState.isExpanded) {
        // =========================================================================
        // COLLAPSED SIDE-BUTTON NOTCH PILL (Sticks to edge next to physical button)
        // =========================================================================
        val pillWidth = (settings.bubbleWidthDp * scale).dp
        val pillHeight = (settings.bubbleHeightDp * scale).dp

        val cornerShape = when (settings.bubbleEdge) {
            BubbleEdge.RIGHT -> RoundedCornerShape(
                topStart = 26.dp,
                bottomStart = 26.dp,
                topEnd = 6.dp,
                bottomEnd = 6.dp
            )
            BubbleEdge.LEFT -> RoundedCornerShape(
                topStart = 6.dp,
                bottomStart = 6.dp,
                topEnd = 26.dp,
                bottomEnd = 26.dp
            )
            BubbleEdge.FREE -> RoundedCornerShape(28.dp)
        }

        Box(
            modifier = Modifier
                .width(pillWidth)
                .height(pillHeight)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDragDelta(dragAmount.x, dragAmount.y)
                    }
                }
                .clip(cornerShape)
                .background(DeepGlassBg)
                .clickable {
                    onToggleExpand()
                    LunaAssistantController.triggerFromSideButton(context, startListeningImmediately = true)
                },
            contentAlignment = Alignment.Center
        ) {
            GeminiGlowingBorderAndWaveCanvas(
                modifier = Modifier.fillMaxSize(),
                rotationDegrees = glowRotation,
                wavePhase = wavePhase,
                audioAmplitude = smoothedAudio,
                waveBands = waveBands,
                glowIntensity = settings.bubbleGlowIntensity,
                isVerticalNotch = true
            )

            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = "Luna Voice",
                tint = Color.White,
                modifier = Modifier.size((20 * scale).dp)
            )
        }
    } else {
        // =========================================================================
        // EXPANDED GEMINI-STYLE REACTIVE PILL CARD
        // =========================================================================
        val cardWidth = (settings.bubbleExpandedWidthDp * scale.coerceIn(0.8f, 1.25f)).dp
        var quickText by remember { mutableStateOf("") }

        Box(
            modifier = Modifier
                .width(cardWidth)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDragDelta(dragAmount.x, dragAmount.y)
                    }
                }
                .clip(RoundedCornerShape(28.dp))
                .background(DeepGlassBg)
        ) {
            // Animated Gemini multi-color glowing border around the card
            Canvas(modifier = Modifier.matchParentSize()) {
                val strokeWidth = (3.2f + smoothedAudio * 4.5f).dp.toPx()
                val brush = Brush.sweepGradient(
                    colors = listOf(
                        GeminiBlue.copy(alpha = settings.bubbleGlowIntensity),
                        GeminiViolet.copy(alpha = settings.bubbleGlowIntensity),
                        GeminiCoral.copy(alpha = settings.bubbleGlowIntensity),
                        GeminiCyan.copy(alpha = settings.bubbleGlowIntensity),
                        GeminiBlue.copy(alpha = settings.bubbleGlowIntensity)
                    ),
                    center = Offset(size.width / 2f, size.height / 2f)
                )
                rotate(degrees = glowRotation) {
                    drawRoundRect(
                        brush = brush,
                        topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
                        size = Size(size.width - strokeWidth, size.height - strokeWidth),
                        cornerRadius = CornerRadius(28.dp.toPx(), 28.dp.toPx()),
                        style = Stroke(width = strokeWidth)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                // Header Row: Luna Sparkle + Status Badge + Open App + Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(listOf(GeminiBlue, GeminiCoral))
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Luna",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        if (liveState.statusBadge.isNotBlank()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "• ${liveState.statusBadge}",
                                color = Color(0xFFA8C7FA),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isSpeaking) {
                            IconButton(
                                onClick = { LunaTtsEngine.getInstance(context).stopSpeaking() },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VolumeOff,
                                    contentDescription = "Mute TTS",
                                    tint = Color(0xFFFFB4AB),
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                val intent = Intent(context, MainActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                }
                                context.startActivity(intent)
                                onDismiss()
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInFull,
                                contentDescription = "Open Full App",
                                tint = Color.White.copy(alpha = 0.78f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = Color.White.copy(alpha = 0.78f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Gemini Reactive Wave Visualizer Strip
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF151B2E))
                        .clickable {
                            LunaAssistantController.toggleListeningFromPill(context)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    GeminiWaveCanvas(
                        modifier = Modifier.fillMaxSize(),
                        wavePhase = wavePhase,
                        audioAmplitude = smoothedAudio,
                        waveBands = waveBands,
                        phase = liveState.phase
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (liveState.phase == AssistantOverlayPhase.LISTENING) {
                                Icons.Default.Stop
                            } else {
                                Icons.Default.Mic
                            },
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when (liveState.phase) {
                                AssistantOverlayPhase.LISTENING ->
                                    if (isDe) "Sprich jetzt... (Tippen zum Senden)" else "Speak now... (Tap to finish)"
                                AssistantOverlayPhase.TRANSCRIBING ->
                                    if (isDe) "Lokale Spracherkennung..." else "Transcribing on device..."
                                AssistantOverlayPhase.THINKING ->
                                    if (isDe) "Luna arbeitet..." else "Luna is thinking..."
                                AssistantOverlayPhase.SPEAKING ->
                                    if (isDe) "Luna spricht... (Tippen für neu)" else "Luna speaking... (Tap to ask again)"
                                else ->
                                    if (isDe) "Tippen zum Sprechen" else "Tap to speak"
                            },
                            color = Color.White.copy(alpha = 0.92f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // User Transcript & Executed Tool Chips
                AnimatedVisibility(
                    visible = liveState.userTranscript.isNotBlank() || liveState.executedTools.isNotEmpty(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        if (liveState.userTranscript.isNotBlank()) {
                            Text(
                                text = "“${liveState.userTranscript}”",
                                color = Color(0xFFD3E3FD),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        if (liveState.executedTools.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                liveState.executedTools.take(3).forEach { tool ->
                                    Surface(
                                        color = Color(0xFF232C46),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.FlashlightOn,
                                                contentDescription = null,
                                                tint = GeminiCyan,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = tool,
                                                color = Color(0xFFA8C7FA),
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Assistant Reply Box
                AnimatedVisibility(
                    visible = liveState.assistantReply.isNotBlank(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                            .heightIn(max = 150.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF161D31))
                            .padding(10.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = liveState.assistantReply,
                            color = Color.White,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Quick Type Input Row inside Pill
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = quickText,
                        onValueChange = { quickText = it },
                        placeholder = {
                            Text(
                                text = if (isDe) "Luna fragen..." else "Ask Luna...",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(color = Color.White, fontSize = 13.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GeminiBlue,
                            unfocusedBorderColor = Color(0xFF2B3553),
                            cursorColor = GeminiCyan
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (quickText.isNotBlank()) {
                                    LunaAssistantController.submitTextFromOverlay(context, quickText)
                                    quickText = ""
                                }
                            }
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(
                        onClick = {
                            if (quickText.isNotBlank()) {
                                LunaAssistantController.submitTextFromOverlay(context, quickText)
                                quickText = ""
                            } else {
                                LunaAssistantController.toggleListeningFromPill(context)
                            }
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(GeminiBlue, GeminiViolet)))
                    ) {
                        Icon(
                            imageVector = if (quickText.isNotBlank()) Icons.Default.Send else Icons.Default.Mic,
                            contentDescription = "Send",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GeminiGlowingBorderAndWaveCanvas(
    modifier: Modifier,
    rotationDegrees: Float,
    wavePhase: Float,
    audioAmplitude: Float,
    waveBands: FloatArray,
    glowIntensity: Float,
    isVerticalNotch: Boolean
) {
    Canvas(modifier = modifier) {
        val strokePx = (2.8f + audioAmplitude * 4.2f).dp.toPx()
        val glowColors = listOf(
            GeminiBlue.copy(alpha = glowIntensity),
            GeminiViolet.copy(alpha = glowIntensity),
            GeminiCoral.copy(alpha = glowIntensity),
            GeminiCyan.copy(alpha = glowIntensity),
            GeminiBlue.copy(alpha = glowIntensity)
        )

        val sweepBrush = Brush.sweepGradient(
            colors = glowColors,
            center = Offset(size.width / 2f, size.height / 2f)
        )

        rotate(degrees = rotationDegrees) {
            drawRoundRect(
                brush = sweepBrush,
                topLeft = Offset(strokePx / 2f, strokePx / 2f),
                size = Size(size.width - strokePx, size.height - strokePx),
                cornerRadius = CornerRadius(24.dp.toPx(), 24.dp.toPx()),
                style = Stroke(width = strokePx)
            )
        }

        // Draw reactive Gemini harmonic ribbon along the vertical notch
        if (isVerticalNotch) {
            val path = Path()
            val midX = size.width / 2f
            val maxDeflection = (size.width * 0.36f) * (0.25f + audioAmplitude * 0.75f)
            val steps = 32
            for (i in 0..steps) {
                val frac = i.toFloat() / steps.toFloat()
                val y = frac * size.height
                val envelope = sin(frac * PI).toFloat()
                val bandVal = waveBands.getOrElse((frac * 4).toInt()) { audioAmplitude }
                val xOffset = sin(frac * 4f * PI + wavePhase).toFloat() * maxDeflection * envelope * (0.4f + bandVal * 0.6f)
                val x = midX + xOffset
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                brush = Brush.verticalGradient(listOf(GeminiCyan, GeminiBlue, GeminiViolet, GeminiCoral)),
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
fun GeminiWaveCanvas(
    modifier: Modifier,
    wavePhase: Float,
    audioAmplitude: Float,
    waveBands: FloatArray,
    phase: AssistantOverlayPhase
) {
    Canvas(modifier = modifier) {
        val midY = size.height / 2f
        val width = size.width
        val maxAmp = (size.height * 0.42f) * (0.18f + audioAmplitude * 0.82f)

        val waveConfigs = listOf(
            Triple(GeminiBlue.copy(alpha = 0.75f), 1.0f, 0f),
            Triple(GeminiViolet.copy(alpha = 0.65f), 1.35f, 1.4f),
            Triple(GeminiCoral.copy(alpha = 0.60f), 1.75f, 2.8f),
            Triple(GeminiCyan.copy(alpha = 0.55f), 2.15f, 4.1f)
        )

        for ((color, freqMult, phaseOffset) in waveConfigs) {
            val path = Path()
            val steps = 60
            for (i in 0..steps) {
                val frac = i.toFloat() / steps.toFloat()
                val x = frac * width
                val envelope = sin(frac * PI).toFloat()
                val bandIdx = (frac * 4).toInt().coerceIn(0, 4)
                val bandBoost = waveBands.getOrElse(bandIdx) { audioAmplitude }
                val dynamicAmp = maxAmp * envelope * (0.35f + bandBoost * 0.65f)
                val y = midY + sin((frac * PI * 2.5 * freqMult) + wavePhase + phaseOffset).toFloat() * dynamicAmp
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(
                    width = if (phase == AssistantOverlayPhase.LISTENING || phase == AssistantOverlayPhase.SPEAKING) 3.dp.toPx() else 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            )
        }
    }
}
