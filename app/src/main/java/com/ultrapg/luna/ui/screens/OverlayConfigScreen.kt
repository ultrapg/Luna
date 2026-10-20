package com.ultrapg.luna.ui.screens

import android.os.Build
import android.provider.Settings
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.ultrapg.luna.assistant.AssistantLiveState
import com.ultrapg.luna.assistant.AssistantOverlayPhase
import com.ultrapg.luna.assistant.AssistantRoleHelper
import com.ultrapg.luna.core.settings.BubbleEdge
import com.ultrapg.luna.core.settings.LunaSettingsRepository
import com.ultrapg.luna.overlay.GeminiSidePillOverlay
import com.ultrapg.luna.overlay.LunaOverlayService
import com.ultrapg.luna.overlay.LunaOverlayWindowManager
import com.ultrapg.luna.overlay.LunaScreenMetrics
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverlayConfigScreen(
    onRequestAssistantRole: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val settings by LunaSettingsRepository.settingsFlow.collectAsState()
    val scrollState = rememberScrollState()
    val isGerman = settings.appLanguage.isGerman

    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    // 0 = Listening (white bars), 1 = Thinking (white stretched pill), 2 = Speaking (light blue bars)
    var previewMode by remember { mutableIntStateOf(0) }
    var showFullScreenCalibrator by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        canDrawOverlays = Settings.canDrawOverlays(context)
    }

    val previewState = remember(previewMode) {
        when (previewMode) {
            0 -> AssistantLiveState(
                isVisible = true,
                isExpanded = true,
                phase = AssistantOverlayPhase.LISTENING
            )
            1 -> AssistantLiveState(
                isVisible = true,
                isExpanded = true,
                phase = AssistantOverlayPhase.THINKING
            )
            else -> AssistantLiveState(
                isVisible = true,
                isExpanded = true,
                phase = AssistantOverlayPhase.SPEAKING
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Tall Interactive Screen-Edge Preview & Fullscreen Calibrator Section
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isGerman) "Vorschau & Power-Tasten-Position" else "Preview & Power Button Position",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // State switcher: Listen (White Bars) | Think (White Stretched Pill) | Speak (Light-Blue Bars)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val labels = if (isGerman) {
                        listOf("Hören", "Denken", "Sprechen")
                    } else {
                        listOf("Listen", "Think", "Speak")
                    }
                    labels.forEachIndexed { idx, label ->
                        SegmentedButton(
                            selected = previewMode == idx,
                            onClick = { previewMode = idx },
                            shape = SegmentedButtonDefaults.itemShape(index = idx, count = labels.size)
                        ) {
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                // Tall proportional Phone Screen Canvas where the user can drag the vertical notch up/down
                var canvasHeightPx by remember { mutableIntStateOf(1) }
                val notchHeightPx = with(density) {
                    (settings.bubbleExpandedHeightDp * settings.bubbleScale).dp.roundToPx()
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xFF090B10))
                        .border(1.5.dp, Color(0xFF252A3A), RoundedCornerShape(22.dp))
                        .onSizeChanged { canvasHeightPx = it.height.coerceAtLeast(1) }
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val deltaFrac = dragAmount.y / canvasHeightPx.toFloat()
                                val newFrac = (settings.bubbleVerticalFraction + deltaFrac).coerceIn(0.04f, 0.88f)
                                LunaSettingsRepository.update(context) {
                                    it.copy(bubbleVerticalFraction = newFrac)
                                }
                                LunaOverlayWindowManager.syncWindowPosition(context)
                            }
                        }
                ) {
                    // Subtle guide label inside the screen canvas
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "${(settings.bubbleVerticalFraction * 100).roundToInt()}%",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4A5268)
                        )
                        Text(
                            text = if (isGerman) {
                                "Ziehe die Pill hier hoch/runter oder nutze den Vollbild-Modus, um sie direkt neben deiner Power-Taste zu platzieren."
                            } else {
                                "Drag the pill up/down here or open Fullscreen Mode to align it directly next to your physical power button."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF687089)
                        )
                    }

                    val yOffsetPx = ((canvasHeightPx - notchHeightPx).coerceAtLeast(0) *
                        settings.bubbleVerticalFraction).roundToInt()

                    val horizontalAlign = when (settings.bubbleEdge) {
                        BubbleEdge.RIGHT -> Alignment.TopEnd
                        BubbleEdge.LEFT -> Alignment.TopStart
                        BubbleEdge.FREE -> Alignment.TopCenter
                    }

                    Box(
                        modifier = Modifier
                            .align(horizontalAlign)
                            .offset { IntOffset(0, yOffsetPx) }
                    ) {
                        GeminiSidePillOverlay(
                            liveState = previewState,
                            settings = settings,
                            micRms = 0.56f,
                            waveBands = floatArrayOf(0.35f, 0.65f, 0.88f, 0.60f, 0.40f),
                            ttsRms = 0.60f,
                            isSpeaking = previewMode == 2,
                            onDragDelta = { dx, dy ->
                                val deltaFrac = dy / canvasHeightPx.toFloat()
                                val newFrac = (settings.bubbleVerticalFraction + deltaFrac).coerceIn(0.04f, 0.88f)
                                var newEdge = settings.bubbleEdge
                                if (dx < -40f && settings.bubbleEdge == BubbleEdge.RIGHT) {
                                    newEdge = BubbleEdge.LEFT
                                } else if (dx > 40f && settings.bubbleEdge == BubbleEdge.LEFT) {
                                    newEdge = BubbleEdge.RIGHT
                                }
                                LunaSettingsRepository.update(context) {
                                    it.copy(bubbleEdge = newEdge, bubbleVerticalFraction = newFrac)
                                }
                                LunaOverlayWindowManager.syncWindowPosition(context)
                            },
                            onToggleExpand = {
                                previewMode = (previewMode + 1) % 3
                            },
                            onDismiss = {}
                        )
                    }
                }

                // Prominent Fullscreen Real-Edge Calibration Button
                Button(
                    onClick = { showFullScreenCalibrator = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Fullscreen, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isGerman) {
                            "Vollbild-Ausrichtung an der Power-Taste"
                        } else {
                            "Fullscreen Power-Button Calibration"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // 2. Clean Full-Width Setup Actions
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (isGerman) "Seitentaste & Berechtigungen" else "Side Button & Permissions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Button(
                    onClick = onRequestAssistantRole,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Assistant, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isGerman) "Als Standard-Assistent festlegen" else "Set as Default Assistant",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                FilledTonalButton(
                    onClick = {
                        if (!Settings.canDrawOverlays(context)) {
                            AssistantRoleHelper.openOverlayPermissionSettings(context)
                        } else {
                            canDrawOverlays = true
                            LunaOverlayService.startOrTrigger(context, startListening = true)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.SmartButton, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (canDrawOverlays) {
                            if (isGerman) "Pill jetzt testen" else "Test Pill Now"
                        } else {
                            if (isGerman) "Overlay-Berechtigung erteilen" else "Grant Overlay Permission"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = { AssistantRoleHelper.openAccessibilitySettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.AccessibilityNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isGerman) "Bedienungshilfe (Optional)" else "Accessibility Shortcut (Optional)",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // 3. Position, Size & 3s Auto-Send Controls
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = if (isGerman) "Position, Größe & Auto-Senden" else "Position, Size & Auto-Send",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                // Edge Segmented Row
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    BubbleEdge.entries.forEachIndexed { idx, edge ->
                        SegmentedButton(
                            selected = settings.bubbleEdge == edge,
                            onClick = {
                                LunaSettingsRepository.update(context) { it.copy(bubbleEdge = edge) }
                                LunaOverlayWindowManager.syncWindowPosition(context)
                            },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = idx,
                                count = BubbleEdge.entries.size
                            )
                        ) {
                            Text(
                                text = when (edge) {
                                    BubbleEdge.RIGHT -> if (isGerman) "Rechts" else "Right"
                                    BubbleEdge.LEFT -> if (isGerman) "Links" else "Left"
                                    BubbleEdge.FREE -> if (isGerman) "Frei" else "Free"
                                },
                                maxLines = 1
                            )
                        }
                    }
                }

                SliderSettingRow(
                    label = if (isGerman) "Höhe am Bildschirmrand (Power-Taste)" else "Edge Height (Power Button)",
                    valueText = "${(settings.bubbleVerticalFraction * 100).roundToInt()}%",
                    value = settings.bubbleVerticalFraction,
                    valueRange = 0.04f..0.88f,
                    onValueChange = { v ->
                        LunaSettingsRepository.update(context) { it.copy(bubbleVerticalFraction = v) }
                        LunaOverlayWindowManager.syncWindowPosition(context)
                    }
                )

                SliderSettingRow(
                    label = if (isGerman) "Auto-Senden nach Stille" else "Auto-Send After Silence",
                    valueText = String.format(Locale.US, "%.1f s", settings.silenceTimeoutMs / 1000f),
                    value = (settings.silenceTimeoutMs / 1000f).coerceIn(1.5f, 5.0f),
                    valueRange = 1.5f..5.0f,
                    onValueChange = { sec ->
                        val ms = ((sec * 10f).roundToInt() * 100L).coerceIn(1500L, 5000L)
                        LunaSettingsRepository.update(context) { it.copy(silenceTimeoutMs = ms) }
                    }
                )

                SliderSettingRow(
                    label = if (isGerman) {
                        "Chat fortsetzen bis (danach neuer Chat)"
                    } else {
                        "Continue Same Chat Window (New Chat After)"
                    },
                    valueText = "${settings.sideButtonContinuationTimeoutSec} s",
                    value = settings.sideButtonContinuationTimeoutSec.toFloat().coerceIn(5f, 90f),
                    valueRange = 5f..90f,
                    onValueChange = { sec ->
                        LunaSettingsRepository.update(context) {
                            it.copy(sideButtonContinuationTimeoutSec = sec.roundToInt().coerceIn(5, 180))
                        }
                    }
                )

                SliderSettingRow(
                    label = if (isGerman) "Pill-Breite" else "Pill Width",
                    valueText = "${settings.bubbleExpandedWidthDp} dp",
                    value = settings.bubbleExpandedWidthDp.toFloat().coerceIn(24f, 54f),
                    valueRange = 24f..54f,
                    onValueChange = { v ->
                        LunaSettingsRepository.update(context) { it.copy(bubbleExpandedWidthDp = v.roundToInt()) }
                    }
                )

                SliderSettingRow(
                    label = if (isGerman) "Pill-Höhe" else "Pill Height",
                    valueText = "${settings.bubbleExpandedHeightDp} dp",
                    value = settings.bubbleExpandedHeightDp.toFloat().coerceIn(88f, 220f),
                    valueRange = 88f..220f,
                    onValueChange = { v ->
                        LunaSettingsRepository.update(context) { it.copy(bubbleExpandedHeightDp = v.roundToInt()) }
                    }
                )

                SliderSettingRow(
                    label = if (isGerman) "Skalierung" else "Scale",
                    valueText = "${(settings.bubbleScale * 100).roundToInt()}%",
                    value = settings.bubbleScale,
                    valueRange = 0.75f..1.35f,
                    onValueChange = { v ->
                        LunaSettingsRepository.update(context) { it.copy(bubbleScale = v) }
                    }
                )

                TextButton(
                    onClick = {
                        LunaSettingsRepository.update(context) {
                            it.copy(
                                bubbleEdge = BubbleEdge.RIGHT,
                                bubbleVerticalFraction = 0.28f,
                                bubbleWidthDp = 28,
                                bubbleHeightDp = 104,
                                bubbleExpandedWidthDp = 34,
                                bubbleExpandedHeightDp = 136,
                                bubbleScale = 1.0f,
                                silenceTimeoutMs = 3000L,
                                sideButtonContinuationTimeoutSec = 20,
                                bubbleKeepAliveDock = false
                            )
                        }
                        LunaOverlayWindowManager.syncWindowPosition(context)
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(if (isGerman) "Zurücksetzen" else "Reset Defaults")
                }
            }
        }
    }

    // Fullscreen Interactive Calibrator so the user can slide the vertical pill directly next to their physical power button
    if (showFullScreenCalibrator) {
        Dialog(
            onDismissRequest = { showFullScreenCalibrator = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            val dialogView = LocalView.current
            SideEffect {
                val dialogWindow = (dialogView.parent as? DialogWindowProvider)?.window
                dialogWindow?.let { win ->
                    win.setLayout(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    val params = win.attributes
                    params.flags = params.flags or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        params.layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        params.fitInsetsTypes = 0
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        params.layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                    win.attributes = params
                }
            }

            val realScreenHeightPx = remember(context) {
                LunaScreenMetrics.getRealScreenHeightPx(context).coerceAtLeast(1)
            }
            var calibratorTopOnScreenPx by remember { mutableIntStateOf(0) }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xE607090E))
                    .onGloballyPositioned { coords ->
                        calibratorTopOnScreenPx = coords.positionOnScreen().y.roundToInt()
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val newFrac = (settings.bubbleVerticalFraction + (dragAmount.y / realScreenHeightPx.toFloat()))
                                .coerceIn(0.04f, 0.88f)
                            var newEdge = settings.bubbleEdge
                            if (dragAmount.x < -36f && settings.bubbleEdge == BubbleEdge.RIGHT) {
                                newEdge = BubbleEdge.LEFT
                            } else if (dragAmount.x > 36f && settings.bubbleEdge == BubbleEdge.LEFT) {
                                newEdge = BubbleEdge.RIGHT
                            }
                            LunaSettingsRepository.update(context) {
                                it.copy(bubbleEdge = newEdge, bubbleVerticalFraction = newFrac)
                            }
                            LunaOverlayWindowManager.syncWindowPosition(context)
                        }
                    }
            ) {
                // Center floating control card
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(24.dp),
                    tonalElevation = 8.dp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 36.dp)
                        .widthIn(max = 340.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (isGerman) "Power-Tasten-Kalibrierung" else "Power Button Calibration",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isGerman) {
                                "Ziehe die Pill am Bildschirmrand genau auf die Höhe deiner physischen Power-Taste."
                            } else {
                                "Drag the pill along the screen edge to match the exact height of your physical power button."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            val labels = if (isGerman) {
                                listOf("Hören", "Denken", "Sprechen")
                            } else {
                                listOf("Listen", "Think", "Speak")
                            }
                            labels.forEachIndexed { idx, label ->
                                SegmentedButton(
                                    selected = previewMode == idx,
                                    onClick = { previewMode = idx },
                                    shape = SegmentedButtonDefaults.itemShape(index = idx, count = labels.size)
                                ) {
                                    Text(label, maxLines = 1)
                                }
                            }
                        }

                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            listOf(BubbleEdge.LEFT, BubbleEdge.RIGHT).forEachIndexed { idx, edge ->
                                SegmentedButton(
                                    selected = settings.bubbleEdge == edge,
                                    onClick = {
                                        LunaSettingsRepository.update(context) { it.copy(bubbleEdge = edge) }
                                        LunaOverlayWindowManager.syncWindowPosition(context)
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(index = idx, count = 2)
                                ) {
                                    Text(
                                        text = if (edge == BubbleEdge.LEFT) {
                                            if (isGerman) "Linker Rand" else "Left Edge"
                                        } else {
                                            if (isGerman) "Rechter Rand" else "Right Edge"
                                        },
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        SliderSettingRow(
                            label = if (isGerman) "Höhe" else "Height Position",
                            valueText = "${(settings.bubbleVerticalFraction * 100).roundToInt()}%",
                            value = settings.bubbleVerticalFraction,
                            valueRange = 0.04f..0.88f,
                            onValueChange = { v ->
                                LunaSettingsRepository.update(context) { it.copy(bubbleVerticalFraction = v) }
                                LunaOverlayWindowManager.syncWindowPosition(context)
                            }
                        )

                        SliderSettingRow(
                            label = if (isGerman) "Größe" else "Scale",
                            valueText = "${(settings.bubbleScale * 100).roundToInt()}%",
                            value = settings.bubbleScale,
                            valueRange = 0.75f..1.35f,
                            onValueChange = { v ->
                                LunaSettingsRepository.update(context) { it.copy(bubbleScale = v) }
                            }
                        )

                        Button(
                            onClick = { showFullScreenCalibrator = false },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (isGerman) "Fertig" else "Done")
                        }
                    }
                }

                // Actual Vertical Side-Notch rendered at the exact real-world physical screen Y coordinate
                val targetScreenYPx = LunaScreenMetrics.computeTargetScreenYPx(
                    context,
                    settings.bubbleVerticalFraction
                )
                val realYOffsetPx = (targetScreenYPx - calibratorTopOnScreenPx).coerceAtLeast(0)
                val edgeAlign = if (settings.bubbleEdge == BubbleEdge.LEFT) {
                    Alignment.TopStart
                } else {
                    Alignment.TopEnd
                }

                Box(
                    modifier = Modifier
                        .align(edgeAlign)
                        .offset { IntOffset(0, realYOffsetPx) }
                ) {
                    GeminiSidePillOverlay(
                        liveState = previewState,
                        settings = settings,
                        micRms = 0.58f,
                        waveBands = floatArrayOf(0.35f, 0.65f, 0.88f, 0.60f, 0.40f),
                        ttsRms = 0.62f,
                        isSpeaking = previewMode == 2,
                        onDragDelta = { dx, dy ->
                            val newFrac = (settings.bubbleVerticalFraction + (dy / realScreenHeightPx.toFloat()))
                                .coerceIn(0.04f, 0.88f)
                            var newEdge = settings.bubbleEdge
                            if (dx < -40f && settings.bubbleEdge == BubbleEdge.RIGHT) {
                                newEdge = BubbleEdge.LEFT
                            } else if (dx > 40f && settings.bubbleEdge == BubbleEdge.LEFT) {
                                newEdge = BubbleEdge.RIGHT
                            }
                            LunaSettingsRepository.update(context) {
                                it.copy(bubbleEdge = newEdge, bubbleVerticalFraction = newFrac)
                            }
                            LunaOverlayWindowManager.syncWindowPosition(context)
                        },
                        onToggleExpand = {
                            previewMode = (previewMode + 1) % 3
                        },
                        onDismiss = {}
                    )
                }
            }
        }
    }
}

@Composable
private fun SliderSettingRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}
