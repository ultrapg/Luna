package com.ultrapg.luna.ui.screens

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ultrapg.luna.core.hardware.NpuHardwareManager
import com.ultrapg.luna.core.llm.OpenAiAgentEngine
import com.ultrapg.luna.core.settings.AppLanguage
import com.ultrapg.luna.core.settings.HardwareAccelerator
import com.ultrapg.luna.core.settings.LunaSettingsRepository
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by LunaSettingsRepository.settingsFlow.collectAsState()
    val hwProfile = remember { NpuHardwareManager.detectHardwareProfile(context) }
    val scrollState = rememberScrollState()
    val isGerman = settings.appLanguage.isGerman

    var baseUrlInput by remember(settings.openAiBaseUrl) { mutableStateOf(settings.openAiBaseUrl) }
    var apiKeyInput by remember(settings.openAiApiKey) { mutableStateOf(settings.openAiApiKey) }
    var modelInput by remember(settings.openAiModel) { mutableStateOf(settings.openAiModel) }
    var systemPromptInput by remember(settings.customSystemPrompt) { mutableStateOf(settings.customSystemPrompt) }

    var showApiKey by remember { mutableStateOf(false) }
    var modelDropdownExpanded by remember { mutableStateOf(false) }
    var isFetchingModels by remember { mutableStateOf(false) }
    var fetchStatusMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. OpenAI-Compatible API Card
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
                    text = "OpenAI API",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                OutlinedTextField(
                    value = baseUrlInput,
                    onValueChange = { newUrl ->
                        baseUrlInput = newUrl
                        LunaSettingsRepository.update(context) { s -> s.copy(openAiBaseUrl = newUrl) }
                    },
                    label = { Text("Base URL", maxLines = 1) },
                    placeholder = { Text("https://api.openai.com/v1") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // Horizontally scrollable preset chips (Never wraps vertically!)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val presets = listOf(
                        "OpenAI" to "https://api.openai.com/v1",
                        "OpenRouter" to "https://openrouter.ai/api/v1",
                        "Groq" to "https://api.groq.com/openai/v1",
                        "DeepSeek" to "https://api.deepseek.com/v1",
                        "Ollama" to "http://127.0.0.1:11434/v1"
                    )
                    presets.forEach { (name, url) ->
                        SuggestionChip(
                            onClick = {
                                baseUrlInput = url
                                LunaSettingsRepository.update(context) { it.copy(openAiBaseUrl = url) }
                            },
                            label = { Text(name, maxLines = 1) },
                            shape = RoundedCornerShape(50)
                        )
                    }
                }

                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { newKey ->
                        apiKeyInput = newKey
                        LunaSettingsRepository.update(context) { s -> s.copy(openAiApiKey = newKey) }
                    },
                    label = { Text("API Key", maxLines = 1) },
                    placeholder = { Text("sk-...") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    visualTransformation = if (showApiKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                imageVector = if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Visibility"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // Hybrid Editable + Selectable Model Dropdown
                ExposedDropdownMenuBox(
                    expanded = modelDropdownExpanded,
                    onExpandedChange = { modelDropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = modelInput,
                        onValueChange = { typedModel ->
                            modelInput = typedModel
                            modelDropdownExpanded = true
                            LunaSettingsRepository.update(context) { s ->
                                val updatedList = if (typedModel.isNotBlank() && !s.availableModels.contains(typedModel)) {
                                    listOf(typedModel) + s.availableModels
                                } else {
                                    s.availableModels
                                }
                                s.copy(openAiModel = typedModel, availableModels = updatedList)
                            }
                        },
                        label = {
                            Text(
                                text = if (isGerman) "Modell (Tippen oder wählen)" else "Model (Type or select)",
                                maxLines = 1
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelDropdownExpanded)
                        },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryEditable)
                            .fillMaxWidth()
                    )

                    val filteredModels = remember(modelInput, settings.availableModels) {
                        val query = modelInput.trim()
                        val baseList = settings.availableModels
                        if (query.isEmpty()) {
                            baseList
                        } else {
                            val matching = baseList.filter { it.contains(query, ignoreCase = true) }
                            if (matching.isEmpty()) baseList else matching
                        }
                    }

                    ExposedDropdownMenu(
                        expanded = modelDropdownExpanded,
                        onDismissRequest = { modelDropdownExpanded = false }
                    ) {
                        if (modelInput.isNotBlank() && !settings.availableModels.contains(modelInput.trim())) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "\"${modelInput.trim()}\"",
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                },
                                onClick = {
                                    val custom = modelInput.trim()
                                    LunaSettingsRepository.update(context) { s ->
                                        s.copy(
                                            openAiModel = custom,
                                            availableModels = (listOf(custom) + s.availableModels).distinct()
                                        )
                                    }
                                    modelDropdownExpanded = false
                                }
                            )
                            HorizontalDivider()
                        }

                        filteredModels.forEach { candidate ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = candidate,
                                        fontWeight = if (candidate == settings.openAiModel) {
                                            FontWeight.Bold
                                        } else {
                                            FontWeight.Normal
                                        }
                                    )
                                },
                                onClick = {
                                    modelInput = candidate
                                    LunaSettingsRepository.update(context) { s -> s.copy(openAiModel = candidate) }
                                    modelDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            isFetchingModels = true
                            fetchStatusMessage = null
                            val result = OpenAiAgentEngine.fetchAvailableModels(
                                baseUrl = baseUrlInput,
                                apiKey = apiKeyInput
                            )
                            isFetchingModels = false
                            result.onSuccess { fetched ->
                                val merged = (
                                    listOfNotNull(modelInput.trim().takeIf { it.isNotEmpty() }) +
                                        fetched +
                                        settings.availableModels
                                    ).distinct()
                                LunaSettingsRepository.update(context) { s ->
                                    s.copy(availableModels = merged)
                                }
                                fetchStatusMessage = if (isGerman) {
                                    "${fetched.size} Modelle geladen"
                                } else {
                                    "Loaded ${fetched.size} models"
                                }
                                modelDropdownExpanded = true
                            }.onFailure { err ->
                                fetchStatusMessage = err.message ?: "Error"
                            }
                        }
                    },
                    enabled = !isFetchingModels,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isFetchingModels) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isGerman) "Modelle vom Server abrufen" else "Fetch Available Models",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (fetchStatusMessage != null) {
                    Text(
                        text = fetchStatusMessage!!,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Temperature",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = String.format(Locale.US, "%.2f", settings.temperature),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Slider(
                        value = settings.temperature,
                        onValueChange = { v ->
                            LunaSettingsRepository.update(context) { it.copy(temperature = v) }
                        },
                        valueRange = 0.0f..1.5f
                    )
                }

                OutlinedTextField(
                    value = systemPromptInput,
                    onValueChange = { newPrompt ->
                        systemPromptInput = newPrompt
                        LunaSettingsRepository.update(context) { s -> s.copy(customSystemPrompt = newPrompt) }
                    },
                    label = { Text(if (isGerman) "System-Prompt (Optional)" else "System Prompt (Optional)", maxLines = 1) },
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 2. Language & NPU Hardware Card
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
                    text = if (isGerman) "Sprache" else "Language",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    AppLanguage.entries.forEachIndexed { idx, lang ->
                        SegmentedButton(
                            selected = settings.appLanguage == lang,
                            onClick = {
                                LunaSettingsRepository.update(context) { it.copy(appLanguage = lang) }
                            },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = idx,
                                count = AppLanguage.entries.size
                            )
                        ) {
                            Text(
                                text = if (isGerman) lang.labelDe else lang.labelEn,
                                maxLines = 1
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isGerman) "Hardware-Beschleunigung" else "Hardware Acceleration",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${String.format(Locale.US, "%.0f", hwProfile.totalRamGb)} GB RAM",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Text(
                    text = "${hwProfile.vendor.displayName} • ${hwProfile.npuArchitecture}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    HardwareAccelerator.entries.forEachIndexed { idx, acc ->
                        SegmentedButton(
                            selected = settings.hardwareAccelerator == acc,
                            onClick = {
                                LunaSettingsRepository.update(context) { it.copy(hardwareAccelerator = acc) }
                            },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = idx,
                                count = HardwareAccelerator.entries.size
                            )
                        ) {
                            Text(acc.shortLabel, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
