package com.ultrapg.luna.ui.screens

import android.provider.Settings
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ultrapg.luna.assistant.AssistantLiveState
import com.ultrapg.luna.assistant.AssistantOverlayPhase
import com.ultrapg.luna.assistant.AssistantRoleHelper
import com.ultrapg.luna.core.settings.BubbleEdge
import com.ultrapg.luna.core.settings.LunaSettingsRepository
import com.ultrapg.luna.overlay.GeminiSidePillOverlay
import com.ultrapg.luna.overlay.LunaOverlayService
import com.ultrapg.luna.overlay.LunaOverlayWindowManager
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverlayConfigScreen(
    onRequestAssistantRole: () -> Unit
) {
    val context = LocalContext.current
    val settings by LunaSettingsRepository.settingsFlow.collectAsState()
    val scrollState = rememberScrollState()
    val isGerman = settings.appLanguage.isGerman

    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    // 0 = Listening, 1 = Thinking, 2 = Speaking
    var previewMode by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        canDrawOverlays = Settings.canDrawOverlays(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Vertical Side-Notch Preview Box
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (isGerman) "Vertikaler Seiten-Notch" else "Vertical Side-Notch Preview",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
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
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                val previewAlignment = when (settings.bubbleEdge) {
                    BubbleEdge.RIGHT -> Alignment.CenterEnd
                    BubbleEdge.LEFT -> Alignment.CenterStart
                    BubbleEdge.FREE -> Alignment.Center
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(175.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF090B10))
                        .border(1.dp, Color(0xFF222634), RoundedCornerShape(18.dp))
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                LunaOverlayWindowManager.handleDragDelta(context, dragAmount.x, dragAmount.y)
                            }
                        },
                    contentAlignment = previewAlignment
                ) {
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

                    GeminiSidePillOverlay(
                        liveState = previewState,
                        settings = settings,
                        micRms = 0.52f,
                        waveBands = floatArrayOf(0.35f, 0.65f, 0.88f, 0.60f, 0.40f),
                        ttsRms = 0.55f,
                        isSpeaking = previewMode == 2,
                        onDragDelta = { dx, dy ->
                            LunaOverlayWindowManager.handleDragDelta(context, dx, dy)
                        },
                        onToggleExpand = {
                            previewMode = (previewMode + 1) % 3
                        },
                        onDismiss = {}
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
                            if (isGerman) "Seiten-Notch jetzt starten" else "Launch Side-Notch Now"
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
                    valueRange = 0.05f..0.90f,
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
                    label = if (isGerman) "Notch-Breite" else "Notch Width",
                    valueText = "${settings.bubbleExpandedWidthDp} dp",
                    value = settings.bubbleExpandedWidthDp.toFloat().coerceIn(24f, 54f),
                    valueRange = 24f..54f,
                    onValueChange = { v ->
                        LunaSettingsRepository.update(context) { it.copy(bubbleExpandedWidthDp = v.roundToInt()) }
                    }
                )

                SliderSettingRow(
                    label = if (isGerman) "Notch-Höhe" else "Notch Height",
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
