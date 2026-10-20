package com.ultrapg.luna.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ultrapg.luna.core.models.*
import com.ultrapg.luna.core.settings.LunaSettingsRepository
import com.ultrapg.luna.core.settings.TtsVoiceModelId
import com.ultrapg.luna.core.speech.LunaTtsEngine
import java.io.File
import java.util.Locale

@Composable
fun ModelsScreen() {
    val context = LocalContext.current
    val settings by LunaSettingsRepository.settingsFlow.collectAsState()
    val dlState by ModelDownloadManager.sessionState.collectAsState()
    val scrollState = rememberScrollState()

    val isGerman = settings.appLanguage.isGerman
    var refreshTrigger by remember { mutableStateOf(0) }

    var customOnnxPath by remember(settings.customTtsModelPath) {
        mutableStateOf(settings.customTtsModelPath)
    }
    var customTokensPath by remember(settings.customTtsTokensPath) {
        mutableStateOf(settings.customTtsTokensPath)
    }

    val customVoiceDir = remember { LocalModelStorage.customTtsDir(context) }

    val onnxPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val target = File(customVoiceDir, "custom_female_voice.onnx")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                customOnnxPath = target.absolutePath
                LunaSettingsRepository.update(context) {
                    it.copy(
                        customTtsModelPath = target.absolutePath,
                        useCustomTtsModel = true
                    )
                }
            }
        }
    }

    val tokensPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val target = File(customVoiceDir, "tokens.txt")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                customTokensPath = target.absolutePath
                LunaSettingsRepository.update(context) {
                    it.copy(customTtsTokensPath = target.absolutePath)
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Active Download Progress Card if running
        if (dlState.isRunning || dlState.errorMessage != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (dlState.isRunning) {
                                "Downloading ${dlState.activeModel?.id?.uppercase() ?: ""} (${dlState.formattedOverallBytes})"
                            } else {
                                dlState.errorMessage ?: "Download Paused"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (dlState.isRunning) {
                            OutlinedButton(onClick = { ModelDownloadManager.pauseDownloads() }) {
                                Text(if (isGerman) "Pause" else "Pause")
                            }
                        }
                    }
                    if (dlState.isRunning) {
                        LinearProgressIndicator(
                            progress = { dlState.overallProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "${dlState.formattedSpeed} • ${dlState.formattedEta}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        // Section 1: Desert Ant Labs Local Speech-to-Text (EAR + VOZ)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = if (isGerman) {
                            "Desert Ant Labs Lokale Spracherkennung (EAR + VOZ)"
                        } else {
                            "Desert Ant Labs On-Device Speech Input (EAR + VOZ)"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = if (isGerman) {
                        "Vollständig lokale Spracherkennung auf dem Smartphone (EAR für automatische Deutsch/Englisch-Erkennung + VOZ für Transkription)."
                    } else {
                        "100% local on-device voice transcription using Desert Ant Labs EAR (spoken language detection for English & German) and VOZ (multilingual speech-to-text)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val sttModels = remember { listOf(LocalModelId.VOZ, LocalModelId.EAR) }

                sttModels.forEach { modelId ->
                    key(modelId, refreshTrigger, dlState.modelStatuses[modelId], dlState.isCompleted) {
                        val isInstalled = LocalModelStorage.isModelDownloaded(context, modelId)
                        val queueStatus = dlState.modelStatuses[modelId]
                        ModelItemRow(
                            model = modelId,
                            isInstalled = isInstalled,
                            queueStatus = queueStatus,
                            isSelected = false,
                            isGerman = isGerman,
                            onDownload = {
                                ModelDownloadManager.startQueue(context, listOf(modelId)) {
                                    refreshTrigger++
                                }
                            },
                            onDelete = {
                                LocalModelStorage.deleteModel(context, modelId)
                                refreshTrigger++
                            },
                            onSelect = null
                        )
                    }
                }

                // Toggle for EAR
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isGerman) "EAR Automatische Spracherkennung (DE/EN)" else "EAR Automatic Language Detection (EN/DE)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = settings.earEnabled,
                        onCheckedChange = { checked ->
                            LunaSettingsRepository.update(context) { it.copy(earEnabled = checked) }
                        }
                    )
                }
            }
        }

        // Section 2: Fast Local Female TTS Models (English & German)
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.RecordVoiceOver,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = if (isGerman) {
                                "Lokale Weibliche TTS-Stimmen (DE & EN)"
                            } else {
                                "Fast Local Female Neural TTS Voices (EN & DE)"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    FilledTonalButton(
                        onClick = {
                            val sample = if (isGerman) {
                                "Hallo! Ich bin Luna, deine persönliche Assistentin."
                            } else {
                                "Hi there! I am Luna, your personal on-device voice assistant."
                            }
                            LunaTtsEngine.getInstance(context).speak(
                                text = sample,
                                languageCode = if (isGerman) "de" else "en"
                            )
                        }
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(if (isGerman) "Testen" else "Test Voice")
                    }
                }

                // Speed Slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isGerman) "Sprechgeschwindigkeit" else "Voice Speed",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = String.format(Locale.US, "%.2fx", settings.ttsSpeed),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = settings.ttsSpeed,
                        onValueChange = { v ->
                            LunaSettingsRepository.update(context) { it.copy(ttsSpeed = v) }
                        },
                        valueRange = 0.70f..1.45f
                    )
                }

                val ttsModels = remember {
                    listOf(
                        LocalModelId.TTS_PIPER_EN_AMY to TtsVoiceModelId.PIPER_EN_AMY,
                        LocalModelId.TTS_KOKORO_EN_HEART to TtsVoiceModelId.KOKORO_EN_HEART,
                        LocalModelId.TTS_PIPER_DE_DII to TtsVoiceModelId.PIPER_DE_DII,
                        LocalModelId.TTS_PIPER_DE_KERSTIN to TtsVoiceModelId.PIPER_DE_KERSTIN
                    )
                }

                ttsModels.forEach { (modelId, voiceEnum) ->
                    key(modelId, refreshTrigger, dlState.modelStatuses[modelId], dlState.isCompleted) {
                        val isInstalled = LocalModelStorage.isModelDownloaded(context, modelId)
                        val queueStatus = dlState.modelStatuses[modelId]
                        val isSelected = if (voiceEnum.langCode == "de") {
                            settings.preferredGermanTts == voiceEnum
                        } else {
                            settings.preferredEnglishTts == voiceEnum
                        }

                        ModelItemRow(
                            model = modelId,
                            isInstalled = isInstalled,
                            queueStatus = queueStatus,
                            isSelected = isSelected,
                            isGerman = isGerman,
                            onDownload = {
                                ModelDownloadManager.startQueue(context, listOf(modelId)) {
                                    refreshTrigger++
                                }
                            },
                            onDelete = {
                                LocalModelStorage.deleteModel(context, modelId)
                                refreshTrigger++
                            },
                            onSelect = {
                                LunaSettingsRepository.update(context) { cur ->
                                    if (voiceEnum.langCode == "de") {
                                        cur.copy(preferredGermanTts = voiceEnum, useCustomTtsModel = false)
                                    } else {
                                        cur.copy(preferredEnglishTts = voiceEnum, useCustomTtsModel = false)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        // Section 3: Custom Trained Female Voice Model Slot (.onnx + tokens.txt)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        text = if (isGerman) {
                            "Eigenes Trainiertes TTS-Stimmmodell (.onnx)"
                        } else {
                            "Custom Trained Voice Model Replacement (.onnx)"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = if (isGerman) {
                        "Wenn du später dein eigenes weibliches VITS/Piper/Kokoro ONNX-Stimmmodell trainierst, kannst du es hier direkt importieren und aktivieren."
                    } else {
                        "Drop in your own custom-trained VITS / Piper / Kokoro `.onnx` model and `tokens.txt` to replace the built-in voice at any time."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = customOnnxPath,
                    onValueChange = { newPath ->
                        customOnnxPath = newPath
                        LunaSettingsRepository.update(context) { s -> s.copy(customTtsModelPath = newPath) }
                    },
                    label = { Text(if (isGerman) "Pfad zur .onnx Modelldatei" else "Custom .onnx Model File Path") },
                    placeholder = { Text("/sdcard/Download/my_luna_voice.onnx") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { onnxPicker.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Default.FolderOpen, contentDescription = "Pick ONNX")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = customTokensPath,
                    onValueChange = { newTokens ->
                        customTokensPath = newTokens
                        LunaSettingsRepository.update(context) { s -> s.copy(customTtsTokensPath = newTokens) }
                    },
                    label = { Text(if (isGerman) "Pfad zur tokens.txt Datei" else "Custom tokens.txt File Path") },
                    placeholder = { Text("/sdcard/Download/tokens.txt") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { tokensPicker.launch(arrayOf("text/plain", "*/*")) }) {
                            Icon(Icons.Default.FolderOpen, contentDescription = "Pick tokens.txt")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                if (customOnnxPath.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                LunaSettingsRepository.update(context) {
                                    it.copy(useCustomTtsModel = true)
                                }
                            }
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (settings.useCustomTtsModel) {
                                    if (isGerman) "Eigenes Modell aktiv" else "Custom Voice Active"
                                } else {
                                    if (isGerman) "Eigenes Modell aktivieren" else "Use Custom Voice"
                                }
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                customOnnxPath = ""
                                customTokensPath = ""
                                LunaSettingsRepository.update(context) {
                                    it.copy(
                                        customTtsModelPath = "",
                                        customTtsTokensPath = "",
                                        useCustomTtsModel = false
                                    )
                                }
                            }
                        ) {
                            Text(if (isGerman) "Zurücksetzen" else "Clear")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelItemRow(
    model: LocalModelId,
    isInstalled: Boolean,
    queueStatus: ModelQueueStatus?,
    isSelected: Boolean,
    isGerman: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onSelect: (() -> Unit)?
) {
    Surface(
        color = if (isSelected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = if (isGerman) model.displayNameDe else model.displayNameEn,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isGerman) model.roleDe else model.roleEn,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${model.sizeMb} MB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (isInstalled) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (onSelect != null) {
                        FilledTonalButton(onClick = onSelect) {
                            Text(
                                if (isSelected) {
                                    if (isGerman) "Aktiv" else "Active"
                                } else {
                                    if (isGerman) "Wählen" else "Select"
                                }
                            )
                        }
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            } else if (queueStatus == ModelQueueStatus.DOWNLOADING ||
                queueStatus == ModelQueueStatus.EXTRACTING ||
                queueStatus == ModelQueueStatus.QUEUED
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
            } else {
                Button(onClick = onDownload) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(if (isGerman) "Laden" else "Download")
                }
            }
        }
    }
}
