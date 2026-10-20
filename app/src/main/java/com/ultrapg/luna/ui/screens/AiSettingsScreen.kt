package com.ultrapg.luna.ui.screens

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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
        // Section 1: OpenAI-Compatible API Connection & Editable Model Dropdown
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
                        imageVector = Icons.Default.CloudSync,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = if (isGerman) {
                            "OpenAI-Kompatible API (LLM-Backend)"
                        } else {
                            "OpenAI-Compatible API (LLM Backend)"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = if (isGerman) {
                        "Verbinde jeden OpenAI-kompatiblen Endpunkt (OpenAI, OpenRouter, Groq, DeepSeek, Ollama, vLLM, LM Studio). Du kannst das Modell frei eintippen (auch wenn es nicht in der Liste ist) oder aus dem Dropdown wählen."
                    } else {
                        "Connect any OpenAI-compatible endpoint (OpenAI, OpenRouter, Groq, DeepSeek, Ollama, vLLM, LM Studio). Type any custom model ID directly (even if unlisted) or select from the dropdown."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Base URL
                OutlinedTextField(
                    value = baseUrlInput,
                    onValueChange = { newUrl ->
                        baseUrlInput = newUrl
                        LunaSettingsRepository.update(context) { s -> s.copy(openAiBaseUrl = newUrl) }
                    },
                    label = { Text("API Base URL") },
                    placeholder = { Text("https://api.openai.com/v1") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth()
                )

                // Quick Endpoint Presets
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val presets = listOf(
                        "OpenAI" to "https://api.openai.com/v1",
                        "OpenRouter" to "https://openrouter.ai/api/v1",
                        "Groq" to "https://api.groq.com/openai/v1",
                        "DeepSeek" to "https://api.deepseek.com/v1"
                    )
                    presets.forEach { (name, url) ->
                        SuggestionChip(
                            onClick = {
                                baseUrlInput = url
                                LunaSettingsRepository.update(context) { it.copy(openAiBaseUrl = url) }
                            },
                            label = { Text(name, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                // API Key
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { newKey ->
                        apiKeyInput = newKey
                        LunaSettingsRepository.update(context) { s -> s.copy(openAiApiKey = newKey) }
                    },
                    label = { Text(if (isGerman) "API-Schlüssel (API Key)" else "API Key") },
                    placeholder = { Text("sk-...") },
                    singleLine = true,
                    visualTransformation = if (showApiKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                imageVector = if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle API key visibility"
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
                                if (isGerman) {
                                    "Modell (Eintippen oder aus Dropdown wählen)"
                                } else {
                                    "Model (Type any ID or select from dropdown)"
                                }
                            )
                        },
                        placeholder = { Text("gpt-4o-mini / claude-3-7-sonnet / llama-3.3-70b") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Psychology, contentDescription = null) },
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
                        // Always allow selecting the exact custom text typed by the user even if not in list
                        if (modelInput.isNotBlank() && !settings.availableModels.contains(modelInput.trim())) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (isGerman) {
                                            "Benutzerdefiniert nutzen: \"${modelInput.trim()}\""
                                        } else {
                                            "Use custom model: \"${modelInput.trim()}\""
                                        },
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

                // Fetch Models from /v1/models Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
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
                                        "${fetched.size} Modelle erfolgreich geladen"
                                    } else {
                                        "Loaded ${fetched.size} models from endpoint"
                                    }
                                    modelDropdownExpanded = true
                                }.onFailure { err ->
                                    fetchStatusMessage = err.message ?: "Failed to fetch models"
                                }
                            }
                        },
                        enabled = !isFetchingModels
                    ) {
                        if (isFetchingModels) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (isGerman) "Verfügbare Modelle abrufen (/v1/models)"
                            else "Fetch Models from Endpoint (/v1/models)"
                        )
                    }
                }

                if (fetchStatusMessage != null) {
                    Text(
                        text = fetchStatusMessage!!,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Temperature Slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isGerman) "Kreativität (Temperature)" else "Temperature",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = String.format(Locale.US, "%.2f", settings.temperature),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
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

                // Custom System Prompt
                OutlinedTextField(
                    value = systemPromptInput,
                    onValueChange = { newPrompt ->
                        systemPromptInput = newPrompt
                        LunaSettingsRepository.update(context) { s -> s.copy(customSystemPrompt = newPrompt) }
                    },
                    label = {
                        Text(
                            if (isGerman) "Zusätzlicher System-Prompt (Optional)"
                            else "Custom System Prompt Instructions (Optional)"
                        )
                    },
                    placeholder = {
                        Text(
                            if (isGerman) "z.B. Antworte immer kurz und direkt..."
                            else "e.g. Keep answers under 2 sentences and speak casually..."
                        )
                    },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Section 2: Language & Bilingual Mode (English & German)
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
                        imageVector = Icons.Default.Translate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = if (isGerman) "Sprache (Deutsch & Englisch)" else "Language (English & German)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = if (isGerman) {
                        "Desert Ant EAR erkennt automatisch, ob du Deutsch oder Englisch sprichst, und Luna antwortet in derselben Sprache."
                    } else {
                        "Desert Ant EAR automatically detects whether you speak English or German, and Luna replies in the matching language."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppLanguage.entries.forEach { lang ->
                        FilterChip(
                            selected = settings.appLanguage == lang,
                            onClick = {
                                LunaSettingsRepository.update(context) { it.copy(appLanguage = lang) }
                            },
                            label = { Text(if (isGerman) lang.labelDe else lang.labelEn) }
                        )
                    }
                }
            }
        }

        // Section 3: NPU / GPU / CPU Hardware Acceleration
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
                        imageVector = Icons.Default.Memory,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = if (isGerman) {
                            "NPU / GPU / CPU Hardware-Beschleunigung"
                        } else {
                            "NPU / GPU / CPU Hardware Acceleration"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "SoC: ${hwProfile.socModel} (${hwProfile.vendor.displayName})",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "RAM: ${String.format(Locale.US, "%.1f", hwProfile.totalRamGb)} GB • NPU Arch: ${hwProfile.npuArchitecture}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "Active Runtime: ${hwProfile.summaryLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Text(
                    text = if (isGerman) {
                        "Bevorzugter Beschleuniger für lokale Spracherkennung (EAR + VOZ) und neuronale Sprachausgabe (TTS):"
                    } else {
                        "Preferred hardware accelerator for on-device speech recognition (EAR + VOZ) and neural TTS:"
                    },
                    style = MaterialTheme.typography.bodySmall
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HardwareAccelerator.entries.forEach { acc ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.hardwareAccelerator == acc,
                                onClick = {
                                    LunaSettingsRepository.update(context) { it.copy(hardwareAccelerator = acc) }
                                }
                            )
                            Column {
                                Text(
                                    text = if (isGerman) acc.displayNameDe else acc.displayNameEn,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = if (isGerman) acc.descDe else acc.descEn,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
