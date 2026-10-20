/*
 * Luna — Native Android Digital Assistant
 * Copyright (C) 2026 ultrapg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.ultrapg.luna.core.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class AppLanguage(val code: String, val labelEn: String, val labelDe: String) {
    AUTO("auto", "Auto", "Auto"),
    ENGLISH("en", "English", "English"),
    GERMAN("de", "Deutsch", "Deutsch");

    fun resolveLanguageCode(): String {
        return when (this) {
            ENGLISH -> "en"
            GERMAN -> "de"
            AUTO -> {
                val sys = Locale.getDefault().language.lowercase(Locale.ROOT)
                if (sys.startsWith("de")) "de" else "en"
            }
        }
    }

    val isGerman: Boolean
        get() = resolveLanguageCode() == "de"

    companion object {
        fun fromCode(code: String?): AppLanguage {
            return entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: AUTO
        }
    }
}

enum class HardwareAccelerator(
    val id: String,
    val displayNameEn: String,
    val displayNameDe: String,
    val shortLabel: String,
    val descEn: String,
    val descDe: String
) {
    NPU(
        id = "npu",
        displayNameEn = "NPU Priority (Hexagon / NeuroPilot / Tensor)",
        displayNameDe = "NPU-Priorität (Hexagon / NeuroPilot / Tensor)",
        shortLabel = "NPU",
        descEn = "Prioritizes Qualcomm Hexagon HTP/QNN, MediaTek NeuroPilot APU, Google Tensor TPU & NNAPI with automatic GPU/CPU fallback",
        descDe = "Priorisiert Qualcomm Hexagon HTP/QNN, MediaTek NeuroPilot APU, Google Tensor TPU & NNAPI mit automatischem GPU/CPU-Fallback"
    ),
    AUTO(
        id = "auto",
        displayNameEn = "Auto (NPU -> GPU -> CPU)",
        displayNameDe = "Automatisch (NPU -> GPU -> CPU)",
        shortLabel = "Auto",
        descEn = "Automatically selects NPU if available, otherwise OpenCL GPU or XNNPACK CPU",
        descDe = "Wählt automatisch NPU falls verfügbar, sonst OpenCL-GPU oder XNNPACK-CPU"
    ),
    GPU(
        id = "gpu",
        displayNameEn = "GPU (Adreno / Mali OpenCL & GLES)",
        displayNameDe = "GPU (Adreno / Mali OpenCL & GLES)",
        shortLabel = "GPU",
        descEn = "Accelerates neural speech models on GPU with multi-core CPU fallback",
        descDe = "Beschleunigt neuronale Sprachmodelle auf der GPU mit Multi-Core-CPU-Fallback"
    ),
    CPU(
        id = "cpu",
        displayNameEn = "CPU (ARM NEON / XNNPACK)",
        displayNameDe = "CPU (ARM NEON / XNNPACK)",
        shortLabel = "CPU",
        descEn = "Performance-core pinned ARM NEON / XNNPACK execution",
        descDe = "Auf Performance-Kerne optimierte ARM NEON / XNNPACK-Ausführung"
    );

    companion object {
        fun fromId(id: String?): HardwareAccelerator {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: NPU
        }
    }
}

enum class BubbleEdge(val id: String, val labelEn: String, val labelDe: String) {
    RIGHT("right", "Right Edge (Default Side Button)", "Rechter Rand (Standard-Seitentaste)"),
    LEFT("left", "Left Edge", "Linker Rand"),
    FREE("free", "Free Floating Anywhere", "Frei positionierbar");

    companion object {
        fun fromId(id: String?): BubbleEdge {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: RIGHT
        }
    }
}

enum class TtsVoiceModelId(
    val id: String,
    val langCode: String,
    val displayNameEn: String,
    val displayNameDe: String,
    val summaryEn: String,
    val summaryDe: String,
    val sizeMb: Int
) {
    PIPER_EN_AMY(
        id = "piper_en_amy",
        langCode = "en",
        displayNameEn = "Amy (English Female • Fast Neural VITS)",
        displayNameDe = "Amy (Englisch Weiblich • Schnelles Neural VITS)",
        summaryEn = "Warm, natural US English female voice • <40ms latency",
        summaryDe = "Warme, natürliche US-englische Frauenstimme • <40ms Latenz",
        sizeMb = 64
    ),
    KOKORO_EN_HEART(
        id = "kokoro_en_heart",
        langCode = "en",
        displayNameEn = "Kokoro 82M (af_heart / af_bella • Studio Female)",
        displayNameDe = "Kokoro 82M (af_heart / af_bella • Studio-Frauenstimme)",
        summaryEn = "Ultra-expressive 82M parameter multi-speaker female voice (Heart, Bella, Sarah)",
        summaryDe = "Ausdrucksstarke 82M-Parameter Frauenstimme (Heart, Bella, Sarah)",
        sizeMb = 98
    ),
    PIPER_DE_DII(
        id = "piper_de_dii",
        langCode = "de",
        displayNameEn = "Dii High (German Female • High Quality Neural VITS)",
        displayNameDe = "Dii High (Deutsch Weiblich • Hochwertiges Neural VITS)",
        summaryEn = "Clear, natural high-quality German female voice",
        summaryDe = "Klare, natürliche deutsche Frauenstimme in hoher Qualität",
        sizeMb = 64
    ),
    PIPER_DE_KERSTIN(
        id = "piper_de_kerstin",
        langCode = "de",
        displayNameEn = "Kerstin (German Female • Ultra-Fast VITS)",
        displayNameDe = "Kerstin (Deutsch Weiblich • Ultraschnelles VITS)",
        summaryEn = "Ultra-low-latency German female voice",
        summaryDe = "Ultraschnelle deutsche Frauenstimme mit minimaler Latenz",
        sizeMb = 64
    ),
    CUSTOM_ONNX(
        id = "custom_onnx",
        langCode = "any",
        displayNameEn = "Custom Trained Female Model (.onnx + tokens.txt)",
        displayNameDe = "Eigenes trainiertes Frauenstimmen-Modell (.onnx + tokens.txt)",
        summaryEn = "Drop-in replacement slot for your own trained VITS/Piper/Kokoro ONNX model",
        summaryDe = "Direkter Ersatz-Slot für dein eigenes trainiertes VITS/Piper/Kokoro ONNX-Modell",
        sizeMb = 0
    );

    companion object {
        fun fromId(id: String?): TtsVoiceModelId {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: PIPER_EN_AMY
        }
    }
}

data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val apiKey: String = "",
    val enabled: Boolean = true
)

data class LunaSettings(
    // Language & Localization
    val appLanguage: AppLanguage = AppLanguage.AUTO,

    // Hardware Acceleration (prefers NPU by default as requested)
    val hardwareAccelerator: HardwareAccelerator = HardwareAccelerator.NPU,

    // OpenAI-Compatible API Settings
    val openAiBaseUrl: String = "https://api.openai.com/v1",
    val openAiApiKey: String = "",
    val openAiModel: String = "gpt-4o-mini",
    val availableModels: List<String> = listOf(
        "gpt-4o-mini",
        "gpt-4o",
        "gpt-4.1",
        "gpt-4.1-mini",
        "gemini-2.5-flash",
        "gemini-2.5-pro",
        "claude-3-7-sonnet",
        "deepseek-chat",
        "qwen-2.5-72b-instruct",
        "llama-3.3-70b-instruct"
    ),
    val temperature: Float = 0.6f,
    val maxTokens: Int = 2048,
    val customSystemPrompt: String = "",

    // Desert Ant Labs STT Settings (Ear + Voz)
    val earEnabled: Boolean = true,
    val autoSilenceStop: Boolean = true,
    val silenceTimeoutMs: Long = 3000L,

    // Fast Local Female TTS Settings
    val ttsEnabled: Boolean = true,
    val preferredEnglishTts: TtsVoiceModelId = TtsVoiceModelId.PIPER_EN_AMY,
    val preferredGermanTts: TtsVoiceModelId = TtsVoiceModelId.PIPER_DE_DII,
    val kokoroSpeakerId: Int = 0, // 0 = af_heart, 1 = af_bella, 2 = af_nicole, 3 = af_sarah
    val ttsSpeed: Float = 1.02f,
    val ttsPitch: Float = 1.04f,
    val useCustomTtsModel: Boolean = false,
    val customTtsModelPath: String = "",
    val customTtsTokensPath: String = "",
    val customTtsDataDirPath: String = "",

    // Gemini-Style Side-Button Horizontal Notch Bubble Settings
    val bubbleEdge: BubbleEdge = BubbleEdge.RIGHT,
    val bubbleVerticalFraction: Float = 0.28f, // 28% from top right next to physical power/side button
    val bubbleOffsetXDp: Int = 0,
    val bubbleWidthDp: Int = 68,
    val bubbleHeightDp: Int = 38,
    val bubbleExpandedWidthDp: Int = 164,
    val bubbleExpandedHeightDp: Int = 46,
    val bubbleScale: Float = 1.0f,
    val bubbleGlowIntensity: Float = 0.92f,
    val bubbleKeepAliveDock: Boolean = false,

    // Agent Grounding, Tools & Memory Settings
    val webSearchEnabled: Boolean = true,
    val webReaderEnabled: Boolean = true,
    val androidActionsEnabled: Boolean = true,
    val memoryEnabled: Boolean = true,
    val mcpServers: List<McpServerConfig> = emptyList()
)

object LunaSettingsRepository {
    private const val PREFS_NAME = "luna_assistant_settings"

    private val _settingsFlow = MutableStateFlow(LunaSettings())
    val settingsFlow: StateFlow<LunaSettings> = _settingsFlow.asStateFlow()

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                _settingsFlow.value = loadFromPrefs(context.applicationContext)
                initialized = true
            }
        }
    }

    fun current(context: Context): LunaSettings {
        if (!initialized) init(context)
        return _settingsFlow.value
    }

    fun update(context: Context, transform: (LunaSettings) -> LunaSettings): LunaSettings {
        if (!initialized) init(context)
        val updated = transform(_settingsFlow.value)
        saveToPrefs(context.applicationContext, updated)
        _settingsFlow.value = updated
        return updated
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun loadFromPrefs(context: Context): LunaSettings {
        val p = getPrefs(context)
        val modelsJson = p.getString("available_models", null)
        val parsedModels = if (!modelsJson.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(modelsJson)
                buildList {
                    for (i in 0 until arr.length()) {
                        val m = arr.optString(i).trim()
                        if (m.isNotEmpty()) add(m)
                    }
                }
            }.getOrNull() ?: LunaSettings().availableModels
        } else {
            LunaSettings().availableModels
        }

        val mcpJson = p.getString("mcp_servers", null)
        val parsedMcp = if (!mcpJson.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(mcpJson)
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        add(
                            McpServerConfig(
                                id = obj.optString("id", "mcp_$i"),
                                name = obj.optString("name", "MCP Server"),
                                url = obj.optString("url", ""),
                                apiKey = obj.optString("apiKey", ""),
                                enabled = obj.optBoolean("enabled", true)
                            )
                        )
                    }
                }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        return LunaSettings(
            appLanguage = AppLanguage.fromCode(p.getString("app_language", AppLanguage.AUTO.code)),
            hardwareAccelerator = HardwareAccelerator.fromId(p.getString("hardware_accelerator", HardwareAccelerator.NPU.id)),
            openAiBaseUrl = p.getString("openai_base_url", "https://api.openai.com/v1") ?: "https://api.openai.com/v1",
            openAiApiKey = p.getString("openai_api_key", "") ?: "",
            openAiModel = p.getString("openai_model", "gpt-4o-mini") ?: "gpt-4o-mini",
            availableModels = parsedModels,
            temperature = p.getFloat("temperature", 0.6f),
            maxTokens = p.getInt("max_tokens", 2048),
            customSystemPrompt = p.getString("custom_system_prompt", "") ?: "",
            earEnabled = p.getBoolean("ear_enabled", true),
            autoSilenceStop = p.getBoolean("auto_silence_stop", true),
            silenceTimeoutMs = p.getLong("silence_timeout_ms", 3000L),
            ttsEnabled = p.getBoolean("tts_enabled", true),
            preferredEnglishTts = TtsVoiceModelId.fromId(p.getString("preferred_en_tts", TtsVoiceModelId.PIPER_EN_AMY.id)),
            preferredGermanTts = TtsVoiceModelId.fromId(p.getString("preferred_de_tts", TtsVoiceModelId.PIPER_DE_DII.id)),
            kokoroSpeakerId = p.getInt("kokoro_speaker_id", 0),
            ttsSpeed = p.getFloat("tts_speed", 1.02f),
            ttsPitch = p.getFloat("tts_pitch", 1.04f),
            useCustomTtsModel = p.getBoolean("use_custom_tts_model", false),
            customTtsModelPath = p.getString("custom_tts_model_path", "") ?: "",
            customTtsTokensPath = p.getString("custom_tts_tokens_path", "") ?: "",
            customTtsDataDirPath = p.getString("custom_tts_datadir_path", "") ?: "",
            bubbleEdge = BubbleEdge.fromId(p.getString("bubble_edge", BubbleEdge.RIGHT.id)),
            bubbleVerticalFraction = p.getFloat("bubble_vertical_fraction", 0.28f).coerceIn(0.05f, 0.90f),
            bubbleOffsetXDp = p.getInt("bubble_offset_x_dp", 0),
            bubbleWidthDp = p.getInt("bubble_width_dp", 68).coerceIn(44, 140),
            bubbleHeightDp = p.getInt("bubble_height_dp", 38).coerceIn(28, 80),
            bubbleExpandedWidthDp = p.getInt("bubble_expanded_width_dp", 164).coerceIn(120, 280),
            bubbleExpandedHeightDp = p.getInt("bubble_expanded_height_dp", 46).coerceIn(36, 84),
            bubbleScale = p.getFloat("bubble_scale", 1.0f).coerceIn(0.6f, 1.8f),
            bubbleGlowIntensity = p.getFloat("bubble_glow_intensity", 0.92f).coerceIn(0.2f, 1.0f),
            bubbleKeepAliveDock = p.getBoolean("bubble_keep_alive_dock", false),
            webSearchEnabled = p.getBoolean("web_search_enabled", true),
            webReaderEnabled = p.getBoolean("web_reader_enabled", true),
            androidActionsEnabled = p.getBoolean("android_actions_enabled", true),
            memoryEnabled = p.getBoolean("memory_enabled", true),
            mcpServers = parsedMcp
        )
    }

    private fun saveToPrefs(context: Context, s: LunaSettings) {
        val modelsArr = JSONArray()
        s.availableModels.distinct().forEach { modelsArr.put(it) }

        val mcpArr = JSONArray()
        s.mcpServers.forEach { srv ->
            val obj = JSONObject()
            obj.put("id", srv.id)
            obj.put("name", srv.name)
            obj.put("url", srv.url)
            obj.put("apiKey", srv.apiKey)
            obj.put("enabled", srv.enabled)
            mcpArr.put(obj)
        }

        getPrefs(context).edit()
            .putString("app_language", s.appLanguage.code)
            .putString("hardware_accelerator", s.hardwareAccelerator.id)
            .putString("openai_base_url", s.openAiBaseUrl.trim())
            .putString("openai_api_key", s.openAiApiKey.trim())
            .putString("openai_model", s.openAiModel.trim())
            .putString("available_models", modelsArr.toString())
            .putFloat("temperature", s.temperature)
            .putInt("max_tokens", s.maxTokens)
            .putString("custom_system_prompt", s.customSystemPrompt)
            .putBoolean("ear_enabled", s.earEnabled)
            .putBoolean("auto_silence_stop", s.autoSilenceStop)
            .putLong("silence_timeout_ms", s.silenceTimeoutMs)
            .putBoolean("tts_enabled", s.ttsEnabled)
            .putString("preferred_en_tts", s.preferredEnglishTts.id)
            .putString("preferred_de_tts", s.preferredGermanTts.id)
            .putInt("kokoro_speaker_id", s.kokoroSpeakerId)
            .putFloat("tts_speed", s.ttsSpeed)
            .putFloat("tts_pitch", s.ttsPitch)
            .putBoolean("use_custom_tts_model", s.useCustomTtsModel)
            .putString("custom_tts_model_path", s.customTtsModelPath)
            .putString("custom_tts_tokens_path", s.customTtsTokensPath)
            .putString("custom_tts_datadir_path", s.customTtsDataDirPath)
            .putString("bubble_edge", s.bubbleEdge.id)
            .putFloat("bubble_vertical_fraction", s.bubbleVerticalFraction)
            .putInt("bubble_offset_x_dp", s.bubbleOffsetXDp)
            .putInt("bubble_width_dp", s.bubbleWidthDp)
            .putInt("bubble_height_dp", s.bubbleHeightDp)
            .putInt("bubble_expanded_width_dp", s.bubbleExpandedWidthDp)
            .putInt("bubble_expanded_height_dp", s.bubbleExpandedHeightDp)
            .putFloat("bubble_scale", s.bubbleScale)
            .putFloat("bubble_glow_intensity", s.bubbleGlowIntensity)
            .putBoolean("bubble_keep_alive_dock", s.bubbleKeepAliveDock)
            .putBoolean("web_search_enabled", s.webSearchEnabled)
            .putBoolean("web_reader_enabled", s.webReaderEnabled)
            .putBoolean("android_actions_enabled", s.androidActionsEnabled)
            .putBoolean("memory_enabled", s.memoryEnabled)
            .putString("mcp_servers", mcpArr.toString())
            .apply()
    }
}
