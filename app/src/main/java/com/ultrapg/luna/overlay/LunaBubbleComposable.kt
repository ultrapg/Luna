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

import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.content.res.Resources
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ultrapg.luna.assistant.AssistantLiveState
import com.ultrapg.luna.assistant.AssistantOverlayPhase
import com.ultrapg.luna.assistant.LunaAssistantController
import com.ultrapg.luna.core.settings.BubbleEdge
import com.ultrapg.luna.core.settings.BubbleStyle
import com.ultrapg.luna.core.settings.LunaSettings
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// Dark Mode Tokens (Classic default preserves exact original Dark colors)
private val DarkNotchBg = Color(0xF513151B)
private val DarkNotchBorder = Color(0xFF2B303C)
private val DarkListeningPrimary = Color(0xFFE8EAED)
private val DarkSpeakingBlue = Color(0xFF7DC4FF)
private val DarkSecondaryAccent = Color(0xFFA8C7FA)

// Light Mode Tokens (High-contrast & clean Material 3 surface tokens)
private val LightNotchBg = Color(0xF7F7F9FF)
private val LightNotchBorder = Color(0xFFCFD6E6)
private val LightListeningPrimary = Color(0xFF1A1E29)
private val LightSpeakingBlue = Color(0xFF1B6EF3)
private val LightSecondaryAccent = Color(0xFF4285F4)

// Retry & Error Red Tokens (works across all 5 designs in both Light and Dark mode)
private val RetryRedDark = Color(0xFFFF5252)
private val RetryRedSecondaryDark = Color(0xFFFF8A80)
private val RetryRedLight = Color(0xFFD32F2F)
private val RetryRedSecondaryLight = Color(0xFFE53935)

private val ErrorFlashBgDark = Color(0xFAD32F2F)
private val ErrorFlashBorderDark = Color(0xFFFF8A80)
private val ErrorFlashInnerDark = Color(0xFFFF5252)

private val ErrorFlashBgLight = Color(0xFAE53935)
private val ErrorFlashBorderLight = Color(0xFFB71C1C)
private val ErrorFlashInnerLight = Color(0xFFFF8A80)

private data class PillPalette(
    val containerBg: Color,
    val borderColor: Color,
    val listeningColor: Color,
    val speakingColor: Color,
    val secondaryColor: Color,
    val trackAlpha: Float
)

@Composable
fun rememberSystemDarkMode(forceDarkTheme: Boolean? = null): Boolean {
    val context = LocalContext.current
    val composeDark = isSystemInDarkTheme()
    val localConfig = LocalConfiguration.current

    var systemNight by remember(localConfig) {
        val mask = Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        mutableStateOf(mask == Configuration.UI_MODE_NIGHT_YES)
    }

    DisposableEffect(context) {
        val appCtx = context.applicationContext
        val callback = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                val mask = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
                systemNight = (mask == Configuration.UI_MODE_NIGHT_YES)
            }

            override fun onLowMemory() {}
        }
        appCtx.registerComponentCallbacks(callback)
        onDispose {
            runCatching { appCtx.unregisterComponentCallbacks(callback) }
        }
    }

    return forceDarkTheme ?: (systemNight || composeDark)
}

private fun resolvePillPalette(style: BubbleStyle, isDark: Boolean, isSpeaking: Boolean): PillPalette {
    return if (isDark) {
        when (style) {
            BubbleStyle.CLASSIC_NOTCH -> PillPalette(
                containerBg = DarkNotchBg,
                borderColor = DarkNotchBorder,
                listeningColor = DarkListeningPrimary,
                speakingColor = DarkSpeakingBlue,
                secondaryColor = DarkSecondaryAccent,
                trackAlpha = 0.16f
            )
            BubbleStyle.FLUID_WAVE -> PillPalette(
                containerBg = Color(0xF5111520),
                borderColor = if (isSpeaking) Color(0xFF335680) else Color(0xFF283044),
                listeningColor = Color(0xFFEAF0FA),
                speakingColor = Color(0xFF69BFFF),
                secondaryColor = Color(0xFFA388FF),
                trackAlpha = 0.20f
            )
            BubbleStyle.DYNAMIC_ORB -> PillPalette(
                containerBg = Color(0xF5151926),
                borderColor = if (isSpeaking) Color(0xFF3A6094) else Color(0xFF30384E),
                listeningColor = Color(0xFFF0F4FC),
                speakingColor = Color(0xFF7DC4FF),
                secondaryColor = Color(0xFFB69DF8),
                trackAlpha = 0.18f
            )
            BubbleStyle.MINIMAL_BLADE -> PillPalette(
                containerBg = Color(0xF20E1016),
                borderColor = if (isSpeaking) Color(0xFF3D76B8) else Color(0xFF292E3B),
                listeningColor = Color(0xFFF1F3F7),
                speakingColor = Color(0xFF70BFFF),
                secondaryColor = Color(0xFF90CAF9),
                trackAlpha = 0.18f
            )
            BubbleStyle.DOT_MATRIX -> PillPalette(
                containerBg = Color(0xF510131A),
                borderColor = Color(0xFF282E3D),
                listeningColor = Color(0xFFE8ECF4),
                speakingColor = Color(0xFF73C2FF),
                secondaryColor = Color(0xFF8AB4F8),
                trackAlpha = 0.15f
            )
        }
    } else {
        when (style) {
            BubbleStyle.CLASSIC_NOTCH -> PillPalette(
                containerBg = LightNotchBg,
                borderColor = LightNotchBorder,
                listeningColor = LightListeningPrimary,
                speakingColor = LightSpeakingBlue,
                secondaryColor = LightSecondaryAccent,
                trackAlpha = 0.16f
            )
            BubbleStyle.FLUID_WAVE -> PillPalette(
                containerBg = Color(0xF7F4F7FF),
                borderColor = if (isSpeaking) Color(0xFF9ABDF5) else Color(0xFFCAD4E8),
                listeningColor = Color(0xFF1B2234),
                speakingColor = Color(0xFF1565D8),
                secondaryColor = Color(0xFF6750A4),
                trackAlpha = 0.16f
            )
            BubbleStyle.DYNAMIC_ORB -> PillPalette(
                containerBg = Color(0xF7EEF3FF),
                borderColor = if (isSpeaking) Color(0xFF8EB4F0) else Color(0xFFC5D0E6),
                listeningColor = Color(0xFF1A2133),
                speakingColor = Color(0xFF1B66DF),
                secondaryColor = Color(0xFF5E479A),
                trackAlpha = 0.15f
            )
            BubbleStyle.MINIMAL_BLADE -> PillPalette(
                containerBg = Color(0xF7F8FAFF),
                borderColor = if (isSpeaking) Color(0xFF7FAEF2) else Color(0xFFCBD2E1),
                listeningColor = Color(0xFF181C26),
                speakingColor = Color(0xFF1766E0),
                secondaryColor = Color(0xFF4A8CFF),
                trackAlpha = 0.15f
            )
            BubbleStyle.DOT_MATRIX -> PillPalette(
                containerBg = Color(0xF7F5F7FC),
                borderColor = Color(0xFFCDD4E3),
                listeningColor = Color(0xFF1A1E2B),
                speakingColor = Color(0xFF1668E3),
                secondaryColor = Color(0xFF4285F4),
                trackAlpha = 0.14f
            )
        }
    }
}

/**
 * Slim Vertical Side-Notch Bubble Overlay with 5 selectable styles & automatic Light/Dark mode:
 * 1. `CLASSIC_NOTCH` (Default): Vertical equalizer bars + stretched pill thinking indicator
 * 2. `FLUID_WAVE`: Smooth harmonic sine-wave ribbons flowing vertically
 * 3. `DYNAMIC_ORB`: Material 3 tonal capsule with breathing voice orb & concentric ripples
 * 4. `MINIMAL_BLADE`: Sleek hardware-edge light blade with expanding core & scanner beam
 * 5. `DOT_MATRIX`: 3-column vertical LED dot-matrix level meter & chasing wave pulse
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LunaSidePillOverlay(
    liveState: AssistantLiveState,
    settings: LunaSettings,
    micRms: Float,
    waveBands: FloatArray,
    ttsRms: Float,
    isSpeaking: Boolean,
    onDragDelta: (Float, Float) -> Unit,
    onToggleExpand: () -> Unit,
    onDismiss: () -> Unit,
    forceDarkTheme: Boolean? = null
) {
    val context = LocalContext.current
    val isDark = rememberSystemDarkMode(forceDarkTheme)

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
            spring(
                dampingRatio = 0.68f,
                stiffness = 380f
            )
        } else {
            tween(durationMillis = 250, easing = FastOutSlowInEasing)
        },
        label = "notchPresence"
    )

    val isThinking = liveState.phase == AssistantOverlayPhase.THINKING ||
        liveState.phase == AssistantOverlayPhase.TRANSCRIBING ||
        liveState.isRetrying ||
        liveState.isErrorFlash
    val isSpeakingNow = (liveState.phase == AssistantOverlayPhase.SPEAKING || isSpeaking) &&
        !liveState.isErrorFlash
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

    val styleWidthFactor = when (settings.bubbleStyle) {
        BubbleStyle.MINIMAL_BLADE -> 0.72f
        BubbleStyle.DYNAMIC_ORB -> 1.06f
        else -> 1.0f
    }

    val audioBreathW = if (isAnimated) (smoothedAudio * 3.2f) else 0f
    val audioBreathH = if (isAnimated) (smoothedAudio * 8.5f) else 0f

    val targetWidth by animateDpAsState(
        targetValue = if (isActive) {
            ((settings.bubbleExpandedWidthDp.coerceIn(24, 54) * styleWidthFactor + audioBreathW) * scale).dp
        } else {
            ((settings.bubbleWidthDp.coerceIn(20, 48) * styleWidthFactor) * scale).dp
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

    val cornerRadius = when (settings.bubbleStyle) {
        BubbleStyle.MINIMAL_BLADE -> 12.dp
        BubbleStyle.DYNAMIC_ORB -> 22.dp
        else -> 18.dp
    }
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

    val palette = resolvePillPalette(settings.bubbleStyle, isDark, isSpeakingNow)

    val targetContainerBg = when {
        liveState.isErrorFlash -> if (isDark) ErrorFlashBgDark else ErrorFlashBgLight
        else -> palette.containerBg
    }
    val targetBorderColor = when {
        liveState.isErrorFlash -> if (isDark) ErrorFlashBorderDark else ErrorFlashBorderLight
        liveState.isRetrying -> if (isDark) RetryRedDark.copy(alpha = 0.65f) else RetryRedLight.copy(alpha = 0.65f)
        else -> palette.borderColor
    }
    val targetPrimaryColor = when {
        liveState.isErrorFlash -> if (isDark) ErrorFlashInnerDark else ErrorFlashInnerLight
        liveState.isRetrying -> if (isDark) RetryRedDark else RetryRedLight
        isSpeakingNow -> palette.speakingColor
        else -> palette.listeningColor
    }
    val targetSecondaryColor = when {
        liveState.isErrorFlash -> if (isDark) ErrorFlashBorderDark else ErrorFlashBorderLight
        liveState.isRetrying -> if (isDark) RetryRedSecondaryDark else RetryRedSecondaryLight
        else -> palette.secondaryColor
    }
    val targetThinkingColor = when {
        liveState.isErrorFlash -> if (isDark) ErrorFlashInnerDark else ErrorFlashInnerLight
        liveState.isRetrying -> if (isDark) RetryRedDark else RetryRedLight
        else -> palette.listeningColor
    }

    val bgColor by animateColorAsState(
        targetValue = targetContainerBg,
        animationSpec = tween(durationMillis = 180),
        label = "pillBgColor"
    )
    val borderColor by animateColorAsState(
        targetValue = targetBorderColor,
        animationSpec = tween(durationMillis = 180),
        label = "pillBorderColor"
    )
    val activeAccentColor by animateColorAsState(
        targetValue = targetPrimaryColor,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "barColorTransition"
    )
    val activeSecondaryColor by animateColorAsState(
        targetValue = targetSecondaryColor,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "secondaryColorTransition"
    )
    val activeThinkingColor by animateColorAsState(
        targetValue = targetThinkingColor,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "thinkingColorTransition"
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
                translationX = (1f - presenceProgress) * size.width * 0.95f * slideSign
                scaleX = (0.76f + 0.24f * presenceProgress) * pressScale
                scaleY = (0.80f + 0.20f * presenceProgress) * pressScale * dragStretchY
            }
            .shadow(elevation = if (isDark) 10.dp else 8.dp, shape = notchShape, clip = false)
            .clip(notchShape)
            .background(bgColor)
            .border(width = 1.dp, color = borderColor, shape = notchShape)
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
        RenderPillStyleContent(
            style = settings.bubbleStyle,
            edge = settings.bubbleEdge,
            isActive = isActive,
            isThinking = isThinking,
            isAnimated = isAnimated,
            smoothedAudio = smoothedAudio,
            waveBands = waveBands,
            phase = phase,
            thinkingProgress = thinkingProgress,
            primaryColor = activeAccentColor,
            secondaryColor = activeSecondaryColor,
            listeningColor = activeThinkingColor,
            trackAlpha = if (liveState.isErrorFlash) 0.45f else palette.trackAlpha
        )
    }
}

/**
 * Miniature live preview for the 5-card Pill Style selector in `OverlayConfigScreen`.
 */
@Composable
fun LunaPillStyleMiniPreview(
    style: BubbleStyle,
    isSpeaking: Boolean,
    isThinking: Boolean,
    modifier: Modifier = Modifier,
    forceDarkTheme: Boolean? = null
) {
    val isDark = rememberSystemDarkMode(forceDarkTheme)
    val palette = resolvePillPalette(style, isDark, isSpeaking)
    val infiniteTransition = rememberInfiniteTransition(label = "miniPreview_${style.id}")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "miniPhase"
    )
    val thinkingProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 950, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "miniThinking"
    )

    val corner = if (style == BubbleStyle.MINIMAL_BLADE) 10.dp else 14.dp
    val shape = RoundedCornerShape(
        topStart = corner,
        bottomStart = corner,
        topEnd = 4.dp,
        bottomEnd = 4.dp
    )
    val activeColor = if (isSpeaking) palette.speakingColor else palette.listeningColor

    Box(
        modifier = modifier
            .clip(shape)
            .background(palette.containerBg)
            .border(1.dp, palette.borderColor, shape),
        contentAlignment = Alignment.Center
    ) {
        RenderPillStyleContent(
            style = style,
            edge = BubbleEdge.RIGHT,
            isActive = true,
            isThinking = isThinking,
            isAnimated = true,
            smoothedAudio = 0.58f,
            waveBands = floatArrayOf(0.38f, 0.68f, 0.92f, 0.64f, 0.42f),
            phase = phase,
            thinkingProgress = thinkingProgress,
            primaryColor = activeColor,
            secondaryColor = palette.secondaryColor,
            listeningColor = palette.listeningColor,
            trackAlpha = palette.trackAlpha
        )
    }
}

@Composable
private fun RenderPillStyleContent(
    style: BubbleStyle,
    edge: BubbleEdge,
    isActive: Boolean,
    isThinking: Boolean,
    isAnimated: Boolean,
    smoothedAudio: Float,
    waveBands: FloatArray,
    phase: Float,
    thinkingProgress: Float,
    primaryColor: Color,
    secondaryColor: Color,
    listeningColor: Color,
    trackAlpha: Float
) {
    when (style) {
        BubbleStyle.CLASSIC_NOTCH -> {
            if (isThinking) {
                StretchedPillLoadingIndicator(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 14.dp, horizontal = 6.dp),
                    progress = thinkingProgress,
                    color = listeningColor,
                    trackAlpha = trackAlpha
                )
            } else {
                VerticalEqualizerBars(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            top = 12.dp,
                            bottom = 12.dp,
                            start = if (edge == BubbleEdge.LEFT) 4.dp else 6.dp,
                            end = if (edge == BubbleEdge.RIGHT) 4.dp else 6.dp
                        ),
                    barCount = if (isActive) 11 else 7,
                    audioAmplitude = smoothedAudio,
                    waveBands = waveBands,
                    phase = phase,
                    isAnimated = isAnimated,
                    barColor = primaryColor
                )
            }
        }

        BubbleStyle.FLUID_WAVE -> {
            FluidWaveRibbonIndicator(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                audioAmplitude = smoothedAudio,
                waveBands = waveBands,
                phase = phase,
                thinkingProgress = thinkingProgress,
                isThinking = isThinking,
                isAnimated = isAnimated,
                primaryColor = primaryColor,
                secondaryColor = secondaryColor
            )
        }

        BubbleStyle.DYNAMIC_ORB -> {
            DynamicAuraOrbIndicator(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                audioAmplitude = smoothedAudio,
                waveBands = waveBands,
                phase = phase,
                thinkingProgress = thinkingProgress,
                isThinking = isThinking,
                isAnimated = isAnimated,
                primaryColor = primaryColor,
                secondaryColor = secondaryColor
            )
        }

        BubbleStyle.MINIMAL_BLADE -> {
            MinimalEdgeBladeIndicator(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                audioAmplitude = smoothedAudio,
                phase = phase,
                thinkingProgress = thinkingProgress,
                isThinking = isThinking,
                isAnimated = isAnimated,
                primaryColor = primaryColor,
                secondaryColor = secondaryColor,
                trackAlpha = trackAlpha
            )
        }

        BubbleStyle.DOT_MATRIX -> {
            DotMatrixPulseIndicator(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 12.dp, horizontal = 5.dp),
                rows = if (isActive) 11 else 9,
                audioAmplitude = smoothedAudio,
                waveBands = waveBands,
                phase = phase,
                thinkingProgress = thinkingProgress,
                isThinking = isThinking,
                isAnimated = isAnimated,
                primaryColor = primaryColor,
                trackAlpha = trackAlpha
            )
        }
    }
}

/**
 * Style 1 (Default): Stretched pill-shaped loading symbol for the thinking state.
 */
@Composable
private fun StretchedPillLoadingIndicator(
    modifier: Modifier,
    progress: Float,
    color: Color,
    trackAlpha: Float
) {
    Canvas(modifier = modifier) {
        val pillWidth = (size.width * 0.42f).coerceIn(5.dp.toPx(), 10.dp.toPx())
        val stretchFactor = 1f - 2f * abs(progress - 0.5f)
        val minPillHeight = (size.height * 0.22f).coerceAtLeast(pillWidth * 1.8f)
        val maxPillHeight = (size.height * 0.58f).coerceAtLeast(minPillHeight)
        val currentPillHeight = minPillHeight + (maxPillHeight - minPillHeight) * stretchFactor

        val travelSpan = (size.height - currentPillHeight).coerceAtLeast(0f)
        val topY = travelSpan * progress
        val leftX = (size.width - pillWidth) / 2f

        drawRoundRect(
            color = color.copy(alpha = trackAlpha),
            topLeft = Offset(leftX, 0f),
            size = Size(pillWidth, size.height),
            cornerRadius = CornerRadius(pillWidth / 2f, pillWidth / 2f)
        )

        drawRoundRect(
            color = color,
            topLeft = Offset(leftX, topY),
            size = Size(pillWidth, currentPillHeight),
            cornerRadius = CornerRadius(pillWidth / 2f, pillWidth / 2f)
        )
    }
}

/**
 * Style 1 (Default): Classic vertical equalizer bars.
 */
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

/**
 * Style 2: Fluid Wave Ribbon — Dual harmonic vertical sine-wave ribbons with a luminous spine.
 */
@Composable
private fun FluidWaveRibbonIndicator(
    modifier: Modifier,
    audioAmplitude: Float,
    waveBands: FloatArray,
    phase: Float,
    thinkingProgress: Float,
    isThinking: Boolean,
    isAnimated: Boolean,
    primaryColor: Color,
    secondaryColor: Color
) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val maxAmp = (size.width * 0.38f).coerceAtLeast(3.dp.toPx())
        val steps = 36
        val primaryPath = Path()
        val secondaryPath = Path()

        val avgBand = if (waveBands.isNotEmpty()) waveBands.average().toFloat() else audioAmplitude
        val activeEnergy = when {
            isThinking -> 0.55f
            isAnimated -> (audioAmplitude * 0.65f + avgBand * 0.45f).coerceIn(0.25f, 1.0f)
            else -> 0.18f
        }

        for (i in 0..steps) {
            val t = i.toFloat() / steps.toFloat()
            val y = t * size.height
            // Bell envelope so wave tapers cleanly at top and bottom of the capsule
            val envelope = sin(t * PI).toFloat()

            val focusBoost = if (isThinking) {
                val dist = abs(t - thinkingProgress)
                (1f - (dist * 2.6f).coerceIn(0f, 1f)) * 0.55f + 0.45f
            } else {
                1f
            }

            val wave1 = sin(t * 2.8f * PI + phase).toFloat()
            val wave2 = cos(t * 2.2f * PI - phase * 1.15f).toFloat()

            val x1 = cx + wave1 * maxAmp * envelope * activeEnergy * focusBoost
            val x2 = cx + wave2 * maxAmp * 0.78f * envelope * activeEnergy * focusBoost

            if (i == 0) {
                primaryPath.moveTo(x1, y)
                secondaryPath.moveTo(x2, y)
            } else {
                primaryPath.lineTo(x1, y)
                secondaryPath.lineTo(x2, y)
            }
        }

        drawPath(
            path = secondaryPath,
            color = secondaryColor.copy(alpha = 0.52f),
            style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
        )
        drawPath(
            path = primaryPath,
            color = primaryColor,
            style = Stroke(width = 2.8.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

/**
 * Style 3: Material 3 Aura Orb — Expressive M3 breathing voice orb with symmetric vertical acoustic rings.
 */
@Composable
private fun DynamicAuraOrbIndicator(
    modifier: Modifier,
    audioAmplitude: Float,
    waveBands: FloatArray,
    phase: Float,
    thinkingProgress: Float,
    isThinking: Boolean,
    isAnimated: Boolean,
    primaryColor: Color,
    secondaryColor: Color
) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = if (isThinking) {
            val travel = size.height * 0.24f
            (size.height / 2f) + (thinkingProgress - 0.5f) * 2f * travel
        } else {
            size.height / 2f
        }

        val energy = if (isAnimated) audioAmplitude.coerceIn(0.22f, 1.0f) else 0.25f
        val pulse = 0.88f + 0.16f * sin(phase).toFloat()
        val baseRadius = (size.width * 0.30f).coerceIn(4.dp.toPx(), 10.dp.toPx())
        val orbRadius = baseRadius * (0.90f + energy * 0.35f) * pulse

        // Soft outer radial aura glow
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    primaryColor.copy(alpha = 0.42f),
                    secondaryColor.copy(alpha = 0.14f),
                    Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = orbRadius * 2.2f
            ),
            radius = orbRadius * 2.2f,
            center = Offset(cx, cy)
        )

        // Symmetric vertical satellite capsules / acoustic ripples above and below the orb
        val pairCount = 3
        val maxSpan = size.height * 0.40f
        for (idx in 1..pairCount) {
            val frac = idx.toFloat() / pairCount.toFloat()
            val bandEnergy = if (waveBands.isNotEmpty()) waveBands[idx % waveBands.size] else energy
            val offsetDist = (orbRadius * 1.15f) + frac * maxSpan * (0.68f + 0.32f * bandEnergy)
            val satWidth = (size.width * (0.58f - frac * 0.14f)).coerceAtLeast(3.dp.toPx())
            val satHeight = 3.2.dp.toPx()
            val alpha = (0.75f - frac * 0.18f).coerceIn(0.20f, 0.85f)

            // Upper acoustic ripple capsule
            val topY = (cy - offsetDist - satHeight / 2f).coerceIn(2.dp.toPx(), size.height - satHeight - 2.dp.toPx())
            drawRoundRect(
                color = primaryColor.copy(alpha = alpha),
                topLeft = Offset(cx - satWidth / 2f, topY),
                size = Size(satWidth, satHeight),
                cornerRadius = CornerRadius(satHeight / 2f, satHeight / 2f)
            )

            // Lower acoustic ripple capsule
            val botY = (cy + offsetDist - satHeight / 2f).coerceIn(2.dp.toPx(), size.height - satHeight - 2.dp.toPx())
            drawRoundRect(
                color = primaryColor.copy(alpha = alpha),
                topLeft = Offset(cx - satWidth / 2f, botY),
                size = Size(satWidth, satHeight),
                cornerRadius = CornerRadius(satHeight / 2f, satHeight / 2f)
            )
        }

        // Core Material 3 morphing voice orb
        drawCircle(
            color = primaryColor,
            radius = orbRadius,
            center = Offset(cx, cy)
        )
    }
}

/**
 * Style 4: Minimal Edge Blade — Sleek hardware-edge light bar with reactive core glow & scanner sweep.
 */
@Composable
private fun MinimalEdgeBladeIndicator(
    modifier: Modifier,
    audioAmplitude: Float,
    phase: Float,
    thinkingProgress: Float,
    isThinking: Boolean,
    isAnimated: Boolean,
    primaryColor: Color,
    secondaryColor: Color,
    trackAlpha: Float
) {
    Canvas(modifier = modifier) {
        val bladeWidth = (size.width * 0.42f).coerceIn(3.5.dp.toPx(), 7.dp.toPx())
        val leftX = (size.width - bladeWidth) / 2f

        // Full-height subtle hardware track
        drawRoundRect(
            color = primaryColor.copy(alpha = trackAlpha),
            topLeft = Offset(leftX, 0f),
            size = Size(bladeWidth, size.height),
            cornerRadius = CornerRadius(bladeWidth / 2f, bladeWidth / 2f)
        )

        if (isThinking) {
            // Smooth scanner beam sweeping vertically
            val beamHeight = (size.height * 0.36f).coerceAtLeast(bladeWidth * 3f)
            val topY = (size.height - beamHeight) * thinkingProgress
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        secondaryColor.copy(alpha = 0.25f),
                        primaryColor,
                        secondaryColor.copy(alpha = 0.25f)
                    ),
                    startY = topY,
                    endY = topY + beamHeight
                ),
                topLeft = Offset(leftX, topY),
                size = Size(bladeWidth, beamHeight),
                cornerRadius = CornerRadius(bladeWidth / 2f, bladeWidth / 2f)
            )
        } else {
            // Voice-reactive vertical light blade expanding symmetrically from the center
            val osc = if (isAnimated) (0.85f + 0.15f * abs(sin(phase)).toFloat()) else 0.38f
            val activeFrac = if (isAnimated) {
                (0.28f + audioAmplitude * 0.70f * osc).coerceIn(0.24f, 0.96f)
            } else {
                0.28f
            }
            val activeHeight = size.height * activeFrac
            val topY = (size.height - activeHeight) / 2f

            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        secondaryColor.copy(alpha = 0.65f),
                        primaryColor,
                        secondaryColor.copy(alpha = 0.65f)
                    ),
                    startY = topY,
                    endY = topY + activeHeight
                ),
                topLeft = Offset(leftX, topY),
                size = Size(bladeWidth, activeHeight),
                cornerRadius = CornerRadius(bladeWidth / 2f, bladeWidth / 2f)
            )
        }
    }
}

/**
 * Style 5: Dot Matrix Pulse — 3-column × N-row vertical LED dot-matrix visualizer.
 */
@Composable
private fun DotMatrixPulseIndicator(
    modifier: Modifier,
    rows: Int = 11,
    audioAmplitude: Float,
    waveBands: FloatArray,
    phase: Float,
    thinkingProgress: Float,
    isThinking: Boolean,
    isAnimated: Boolean,
    primaryColor: Color,
    trackAlpha: Float
) {
    Canvas(modifier = modifier) {
        val cols = 3
        val dotRadius = (size.width / 9.2f).coerceIn(1.3.dp.toPx(), 2.6.dp.toPx())
        val colSpacing = (size.width - dotRadius * 2f) / (cols + 0.4f)
        val rowSpacing = size.height / (rows + 1)
        val centerRow = (rows - 1) / 2f
        val cx = size.width / 2f

        for (r in 0 until rows) {
            val cy = rowSpacing * (r + 1)
            val normDistFromCenter = (abs(r - centerRow) / centerRow.coerceAtLeast(1f)).coerceIn(0f, 1f)
            val bandIdx = if (waveBands.isNotEmpty()) (r % waveBands.size) else 0
            val bandEnergy = if (waveBands.isNotEmpty()) waveBands[bandIdx] else audioAmplitude

            val rowActivity = when {
                isThinking -> {
                    val rowFrac = r.toFloat() / (rows - 1).coerceAtLeast(1).toFloat()
                    val dist = abs(rowFrac - thinkingProgress)
                    (1f - dist * 2.8f).coerceIn(0f, 1f)
                }
                isAnimated -> {
                    val wave = 0.55f + 0.45f * abs(sin(phase + r * 0.55f)).toFloat()
                    val level = (audioAmplitude * 0.60f + bandEnergy * 0.55f) * wave
                    ((1.05f - normDistFromCenter * 0.75f) * level * 1.35f).coerceIn(0f, 1f)
                }
                else -> (1f - normDistFromCenter) * 0.35f
            }

            for (c in 0 until cols) {
                val colOffset = (c - 1) * colSpacing
                val x = cx + colOffset
                val isCenterCol = (c == 1)

                val litAlpha = if (isCenterCol) {
                    (trackAlpha + rowActivity * (1f - trackAlpha)).coerceIn(trackAlpha, 1f)
                } else {
                    if (rowActivity > 0.42f) {
                        (trackAlpha + (rowActivity - 0.30f) * 1.1f).coerceIn(trackAlpha, 0.95f)
                    } else {
                        trackAlpha
                    }
                }

                drawCircle(
                    color = primaryColor.copy(alpha = litAlpha),
                    radius = if (litAlpha > 0.5f) dotRadius * 1.08f else dotRadius * 0.90f,
                    center = Offset(x, cy)
                )
            }
        }
    }
}
