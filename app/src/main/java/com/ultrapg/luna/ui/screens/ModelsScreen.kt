package com.ultrapg.luna.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
        // Active Download Banner
        if (dlState.isRunning || dlState.errorMessage != null) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(16.dp),
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
                                "${dlState.activeModel?.id?.uppercase() ?: ""} • ${dlState.formattedOverallBytes}"
                            } else {
                                dlState.errorMessage ?: "Paused"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (dlState.isRunning) {
                            TextButton(onClick = { ModelDownloadManager.pauseDownloads() }) {
                                Text("Pause")
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

        // 1. Speech Input (Desert Ant EAR + VOZ)
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
                    text = if (isGerman) "Spracheingabe (Lokal)" else "Speech Input (On-Device)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
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

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isGerman) "Automatische Spracherkennung (EAR)" else "Auto Language Detection (EAR)",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(end = 12.dp)
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

        // 2. Female Neural TTS Voices (EN & DE)
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isGerman) "Weibliche Stimme (TTS)" else "Female Voice (TTS)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )

                    FilledTonalButton(
                        onClick = {
                            val activeLang = if (settings.selectedTtsVoice.langCode == "de") "de" else "en"
                            val sample = if (activeLang == "de") {
                                "Hallo! Ich bin Luna."
                            } else {
                                "Hi! I am Luna."
                            }
                            LunaTtsEngine.getInstance(context).speak(
                                text = sample,
                                languageCode = activeLang
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(50)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (isGerman) "Test" else "Test", maxLines = 1)
                    }
                }

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isGerman) "Geschwindigkeit" else "Voice Speed",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = String.format(Locale.US, "%.2fx", settings.ttsSpeed),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Slider(
                        value = settings.ttsSpeed,
                        onValueChange = { v ->
                            LunaSettingsRepository.update(context) { it.copy(ttsSpeed = v) }
                        },
                        valueRange = 0.75f..1.40f
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
                        // Single active selection across the list so only 1 voice is "Aktiv" at a time
                        val isSelected = !settings.useCustomTtsModel && settings.selectedTtsVoice == voiceEnum

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
                                    cur.copy(
                                        selectedTtsVoice = voiceEnum,
                                        preferredGermanTts = if (voiceEnum.langCode == "de") voiceEnum else cur.preferredGermanTts,
                                        preferredEnglishTts = if (voiceEnum.langCode == "en") voiceEnum else cur.preferredEnglishTts,
                                        useCustomTtsModel = false
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }

        // 3. Custom Trained Voice Model (.onnx)
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
                    text = if (isGerman) "Eigenes ONNX-Stimmmodell" else "Custom ONNX Voice Model",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                OutlinedTextField(
                    value = customOnnxPath,
                    onValueChange = { newPath ->
                        customOnnxPath = newPath
                        LunaSettingsRepository.update(context) { s -> s.copy(customTtsModelPath = newPath) }
                    },
                    label = { Text(".onnx Model", maxLines = 1) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
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
                    label = { Text("tokens.txt", maxLines = 1) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
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
                                LunaSettingsRepository.update(context) { it.copy(useCustomTtsModel = true) }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = if (settings.useCustomTtsModel) {
                                    if (isGerman) "Aktiv" else "Active"
                                } else {
                                    if (isGerman) "Aktivieren" else "Activate"
                                },
                                maxLines = 1
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
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(if (isGerman) "Löschen" else "Clear", maxLines = 1)
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
        color = MaterialTheme.colorScheme.surface,
        border = if (isSelected) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                Text(
                    text = if (isGerman) model.displayNameDe else model.displayNameEn,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${model.sizeMb} MB • ${if (isGerman) model.roleDe else model.roleEn}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isInstalled) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onSelect != null) {
                        if (isSelected) {
                            Button(
                                onClick = onSelect,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(50)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (isGerman) "Aktiv" else "Active",
                                    maxLines = 1
                                )
                            }
                        } else {
                            FilledTonalButton(
                                onClick = onSelect,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    text = if (isGerman) "Wählen" else "Use",
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
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
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
            } else {
                FilledTonalButton(
                    onClick = onDownload,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(50)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (isGerman) "Laden" else "Get", maxLines = 1)
                }
            }
        }
    }
}
