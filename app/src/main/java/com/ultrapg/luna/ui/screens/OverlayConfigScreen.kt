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
import androidx.compose.ui.unit.dp
import com.ultrapg.luna.assistant.AssistantLiveState
import com.ultrapg.luna.assistant.AssistantOverlayPhase
import com.ultrapg.luna.assistant.AssistantRoleHelper
import com.ultrapg.luna.core.settings.BubbleEdge
import com.ultrapg.luna.core.settings.LunaSettingsRepository
import com.ultrapg.luna.overlay.GeminiSidePillOverlay
import com.ultrapg.luna.overlay.LunaOverlayService
import com.ultrapg.luna.overlay.LunaOverlayWindowManager
import kotlin.math.roundToInt

@Composable
fun OverlayConfigScreen(
    onRequestAssistantRole: () -> Unit
) {
    val context = LocalContext.current
    val settings by LunaSettingsRepository.settingsFlow.collectAsState()
    val scrollState = rememberScrollState()

    val isGerman = settings.appLanguage.isGerman

    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var isDefaultAssistant by remember { mutableStateOf(AssistantRoleHelper.isDefaultAssistant(context)) }
    var previewExpanded by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        canDrawOverlays = Settings.canDrawOverlays(context)
        isDefaultAssistant = AssistantRoleHelper.isDefaultAssistant(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section 1: Side-Button Digital Assistant Setup
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = if (isGerman) {
                            "Power-/Seitentaste & Standard-Assistent"
                        } else {
                            "Side-Button Trigger & Default Assistant"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = if (isGerman) {
                        "Setze Luna als Standard-Digitalassistent, damit ein langer Druck auf die Seitentaste (wie bei Gemini) die schwebende Luna-Bubble direkt neben der physischen Taste öffnet."
                    } else {
                        "Set Luna as your default Digital Assistant so holding the physical side/power button (just like Gemini) spawns the Luna pill bubble right next to the button."
                    },
                    style = MaterialTheme.typography.bodySmall
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onRequestAssistantRole,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Assistant, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (isDefaultAssistant) {
                                if (isGerman) "Assistent-Einstellungen" else "Assistant Settings"
                            } else {
                                if (isGerman) "Als Standard setzen" else "Set Default Assistant"
                            }
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            if (!Settings.canDrawOverlays(context)) {
                                AssistantRoleHelper.openOverlayPermissionSettings(context)
                            } else {
                                canDrawOverlays = true
                                LunaOverlayService.startOrTrigger(context, startListening = true)
                            }
                        }
                    ) {
                        Icon(Icons.Default.Layers, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (canDrawOverlays) {
                                if (isGerman) "Bubble testen" else "Test Pill Now"
                            } else {
                                if (isGerman) "Overlay erlauben" else "Grant Overlay"
                            }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            AssistantRoleHelper.openAccessibilitySettings(context)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.AccessibilityNew, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (isGerman) "Bedienungshilfe (Optionaler Trigger)" else "Accessibility Shortcut"
                        )
                    }
                }
            }
        }

        // Section 2: Interactive Screen Edge Preview & Drag-to-Position Canvas
        Card(modifier = Modifier.fillMaxWidth()) {
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
                        text = if (isGerman) {
                            "Interaktive Bubble-Position & Vorschau"
                        } else {
                            "Interactive Pill Position & Live Preview"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    FilterChip(
                        selected = previewExpanded,
                        onClick = { previewExpanded = !previewExpanded },
                        label = {
                            Text(
                                if (previewExpanded) {
                                    if (isGerman) "Aktiv (Welle)" else "Active Wave"
                                } else {
                                    if (isGerman) "Notch-Pill" else "Collapsed Notch"
                                }
                            )
                        }
                    )
                }

                Text(
                    text = if (isGerman) {
                        "Ziehe die Kerbe im Vorschau-Bildschirm auf die Höhe deiner physischen Seitentaste oder nutze die Regler unten."
                    } else {
                        "Drag the notch inside the phone preview to align it with your physical side button, or fine-tune with the sliders below."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Phone Frame Simulation + Live Gemini Pill Preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF0A0D14))
                        .border(2.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val newFraction = (settings.bubbleVerticalFraction + (dragAmount.y / size.height))
                                    .coerceIn(0.05f, 0.90f)
                                LunaSettingsRepository.update(context) {
                                    it.copy(bubbleVerticalFraction = newFraction)
                                }
                                LunaOverlayWindowManager.syncWindowPosition(context)
                            }
                        }
                ) {
                    val previewState = remember(previewExpanded, isGerman) {
                        AssistantLiveState(
                            isVisible = true,
                            isExpanded = previewExpanded,
                            phase = if (previewExpanded) AssistantOverlayPhase.LISTENING else AssistantOverlayPhase.IDLE_NOTCH,
                            userTranscript = if (isGerman) "Schalte die Taschenlampe ein..." else "Turn on the flashlight...",
                            statusBadge = if (isGerman) "Höre zu..." else "Listening..."
                        )
                    }

                    Box(
                        modifier = Modifier
                            .align(
                                if (settings.bubbleEdge == BubbleEdge.LEFT) {
                                    Alignment.TopStart
                                } else {
                                    Alignment.TopEnd
                                }
                            )
                            .offset(y = (settings.bubbleVerticalFraction * 120f).dp)
                            .padding(horizontal = 4.dp)
                    ) {
                        GeminiSidePillOverlay(
                            liveState = previewState,
                            settings = settings,
                            micRms = 0.56f,
                            waveBands = floatArrayOf(0.45f, 0.78f, 0.92f, 0.64f, 0.50f),
                            ttsRms = 0f,
                            isSpeaking = false,
                            onDragDelta = { _, dy ->
                                val delta = dy / 500f
                                LunaSettingsRepository.update(context) {
                                    it.copy(
                                        bubbleVerticalFraction = (it.bubbleVerticalFraction + delta)
                                            .coerceIn(0.05f, 0.90f)
                                    )
                                }
                                LunaOverlayWindowManager.syncWindowPosition(context)
                            },
                            onToggleExpand = { previewExpanded = !previewExpanded },
                            onDismiss = { previewExpanded = false }
                        )
                    }
                }
            }
        }

        // Section 3: Resize & Dock Controls
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = if (isGerman) "Größe, Ausrichtung & Gemini-Glow" else "Size, Edge Docking & Gemini Glow",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                // Dock Mode Selector
                Text(
                    text = if (isGerman) "Bildschirmrand-Modus" else "Screen Edge Docking Mode",
                    style = MaterialTheme.typography.labelLarge
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BubbleEdge.entries.forEach { edge ->
                        FilterChip(
                            selected = settings.bubbleEdge == edge,
                            onClick = {
                                LunaSettingsRepository.update(context) { it.copy(bubbleEdge = edge) }
                                LunaOverlayWindowManager.syncWindowPosition(context)
                            },
                            label = {
                                Text(
                                    when (edge) {
                                        BubbleEdge.RIGHT -> if (isGerman) "Rechts (Taste)" else "Right Notch"
                                        BubbleEdge.LEFT -> if (isGerman) "Links (Notch)" else "Left Notch"
                                        BubbleEdge.FREE -> if (isGerman) "Frei" else "Free Floating"
                                    }
                                )
                            }
                        )
                    }
                }

                // Vertical Position Next to Side Button
                SliderSettingRow(
                    label = if (isGerman) "Vertikale Position (Höhe der Seitentaste)" else "Vertical Position (Side Button Height)",
                    valueText = "${(settings.bubbleVerticalFraction * 100).roundToInt()}%",
                    value = settings.bubbleVerticalFraction,
                    valueRange = 0.05f..0.90f,
                    onValueChange = { v ->
                        LunaSettingsRepository.update(context) { it.copy(bubbleVerticalFraction = v) }
                        LunaOverlayWindowManager.syncWindowPosition(context)
                    }
                )

                // Collapsed Notch Pill Width
                SliderSettingRow(
                    label = if (isGerman) "Notch-Breite (Eingeklappt)" else "Collapsed Notch Width",
                    valueText = "${settings.bubbleWidthDp} dp",
                    value = settings.bubbleWidthDp.toFloat(),
                    valueRange = 28f..120f,
                    onValueChange = { v ->
                        LunaSettingsRepository.update(context) { it.copy(bubbleWidthDp = v.roundToInt()) }
                    }
                )

                // Collapsed Notch Pill Height
                SliderSettingRow(
                    label = if (isGerman) "Notch-Höhe (Eingeklappt)" else "Collapsed Notch Height",
                    valueText = "${settings.bubbleHeightDp} dp",
                    value = settings.bubbleHeightDp.toFloat(),
                    valueRange = 64f..240f,
                    onValueChange = { v ->
                        LunaSettingsRepository.update(context) { it.copy(bubbleHeightDp = v.roundToInt()) }
                    }
                )

                // Expanded Pill Scale
                SliderSettingRow(
                    label = if (isGerman) "Skalierung der geöffneten Sprach-Bubble" else "Expanded Voice Pill Scale",
                    valueText = "${(settings.bubbleScale * 100).roundToInt()}%",
                    value = settings.bubbleScale,
                    valueRange = 0.70f..1.40f,
                    onValueChange = { v ->
                        LunaSettingsRepository.update(context) { it.copy(bubbleScale = v) }
                    }
                )

                // Gemini Outline & Audio Wave Glow Intensity
                SliderSettingRow(
                    label = if (isGerman) "Gemini-Umriss & Wellen-Leuchtstärke" else "Gemini Outline & Wave Glow Intensity",
                    valueText = "${(settings.bubbleGlowIntensity * 100).roundToInt()}%",
                    value = settings.bubbleGlowIntensity,
                    valueRange = 0.25f..1.0f,
                    onValueChange = { v ->
                        LunaSettingsRepository.update(context) { it.copy(bubbleGlowIntensity = v) }
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isGerman) "Notch dauerhaft am Rand anzeigen" else "Keep Persistent Notch Docked on Edge",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (isGerman) {
                                "Hält die kleine Kerbe dauerhaft neben der Seitentaste griffbereit."
                            } else {
                                "Keeps the small pill notch docked on the screen edge at all times."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.bubbleKeepAliveDock,
                        onCheckedChange = { checked ->
                            LunaSettingsRepository.update(context) { it.copy(bubbleKeepAliveDock = checked) }
                        }
                    )
                }

                OutlinedButton(
                    onClick = {
                        LunaSettingsRepository.update(context) {
                            it.copy(
                                bubbleEdge = BubbleEdge.RIGHT,
                                bubbleVerticalFraction = 0.28f,
                                bubbleWidthDp = 46,
                                bubbleHeightDp = 132,
                                bubbleScale = 1.0f,
                                bubbleGlowIntensity = 0.92f
                            )
                        }
                        LunaOverlayWindowManager.syncWindowPosition(context)
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (isGerman) "Standardwerte wiederherstellen" else "Reset Bubble Defaults")
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
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}
