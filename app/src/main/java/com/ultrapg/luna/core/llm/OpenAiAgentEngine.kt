/*
 * Luna — Native Android Digital Assistant
 * Copyright (C) 2026 ultrapg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.ultrapg.luna.core.llm

import android.content.Context
import android.util.Log
import com.ultrapg.luna.core.database.ChatMessageEntity
import com.ultrapg.luna.core.database.LunaDatabase
import com.ultrapg.luna.core.mcp.LunaMcpClient
import com.ultrapg.luna.core.settings.LunaSettings
import com.ultrapg.luna.core.settings.LunaSettingsRepository
import com.ultrapg.luna.core.tools.LunaAgentTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class AgentStepEvent(
    val toolName: String,
    val statusText: String
)

data class AgentCompletionResult(
    val replyText: String,
    val executedToolsSummary: List<String>,
    val modelUsed: String
)

/**
 * OpenAI-Compatible API Client & Autonomous Agent Engine for Luna.
 *
 * Features:
 * - Connects to any OpenAI-compatible endpoint (`/v1/chat/completions`, `/v1/models`) —
 *   OpenAI, OpenRouter, Groq, DeepSeek, Ollama, LM Studio, vLLM, Google Gemini OpenAI endpoint, etc.
 * - Fetches available models dynamically for the dropdown while allowing any custom free-text model ID.
 * - Multi-step Function Calling loop (`tools` + `tool_calls`) for Android device control (Flashlight,
 *   Apps, Volume, Alarms/Timers, Clipboard, Settings), DuckDuckGo Web Search, Website Reading,
 *   Persistent Memory, and external MCP Servers.
 * - Bilingual system prompt (English & German / Deutsch).
 */
object OpenAiAgentEngine {
    private const val TAG = "OpenAiAgentEngine"
    private const val MAX_AGENT_STEPS = 5
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Queries `GET <baseUrl>/models` to populate the selectable model dropdown in settings.
     */
    suspend fun fetchAvailableModels(
        baseUrl: String,
        apiKey: String
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = normalizeBaseUrl(baseUrl)
            val url = "$cleanBase/models"
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .get()

            if (apiKey.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer ${apiKey.trim()}")
            }

            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}: ${body.take(160)}")
                }
                val json = JSONObject(body)
                val dataArr = json.optJSONArray("data") ?: json.optJSONArray("models") ?: JSONArray()
                val models = mutableListOf<String>()
                for (i in 0 until dataArr.length()) {
                    val item = dataArr.optJSONObject(i)
                    val id = item?.optString("id", "")?.takeIf { it.isNotBlank() }
                        ?: item?.optString("name", "")?.takeIf { it.isNotBlank() }
                        ?: dataArr.optString(i, "")
                    if (id.isNotBlank()) {
                        models.add(id.trim())
                    }
                }
                models.distinct().sorted()
            }
        }
    }

    /**
     * Runs the full multi-step Luna agent loop with tool execution, web grounding, MCP tools,
     * and persistent memory.
     */
    suspend fun runAgentTurn(
        context: Context,
        conversationHistory: List<ChatMessageEntity>,
        userPrompt: String,
        detectedLanguage: String? = null,
        onToolStep: ((AgentStepEvent) -> Unit)? = null
    ): AgentCompletionResult = withContext(Dispatchers.IO) {
        val settings = LunaSettingsRepository.current(context)
        val cleanBase = normalizeBaseUrl(settings.openAiBaseUrl)
        val modelId = settings.openAiModel.trim()

        if (cleanBase.isBlank() || modelId.isBlank()) {
            val isDe = settings.appLanguage.isGerman
            return@withContext AgentCompletionResult(
                replyText = if (isDe) {
                    "Bitte trage zuerst deine Base-URL, deinen API-Key und dein Modell im KI-Tab ein."
                } else {
                    "Please enter your Base URL, API Key, and Model in the AI tab first."
                },
                executedToolsSummary = emptyList(),
                modelUsed = modelId.ifBlank { "unconfigured" }
            )
        }

        // Load persistent memories to inject into system prompt
        val memories = if (settings.memoryEnabled) {
            runCatching { LunaDatabase.getInstance(context).memoryDao().getAllMemories() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val systemPrompt = buildSystemPrompt(settings, memories.map { "${it.key}: ${it.value}" }, detectedLanguage)

        // Build OpenAI tools array (Android actions + DuckDuckGo + Web Reader + Memory + MCP servers)
        val toolsArray = LunaAgentTools.buildOpenAiToolSchemas(context)
        val mcpTools = if (settings.mcpServers.any { it.enabled }) {
            runCatching { LunaMcpClient.discoverAllTools(settings.mcpServers) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        LunaMcpClient.appendMcpToolsToOpenAiArray(toolsArray, mcpTools)

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))

        // Add recent conversation history (up to last 14 messages)
        val recentHistory = conversationHistory.takeLast(14)
        for (msg in recentHistory) {
            if (msg.role == "user" || msg.role == "assistant") {
                messages.put(
                    JSONObject()
                        .put("role", msg.role)
                        .put("content", msg.content)
                )
            }
        }
        messages.put(JSONObject().put("role", "user").put("content", userPrompt))

        val executedTools = mutableListOf<String>()
        var useToolsInRequest = toolsArray.length() > 0

        for (step in 0 until MAX_AGENT_STEPS) {
            val payload = JSONObject()
                .put("model", modelId)
                .put("messages", messages)
                .put("temperature", settings.temperature.toDouble())
                .put("max_tokens", settings.maxTokens)

            if (useToolsInRequest && toolsArray.length() > 0) {
                payload.put("tools", toolsArray)
                payload.put("tool_choice", "auto")
            }

            val responseJson = try {
                postChatCompletion(cleanBase, settings.openAiApiKey, payload)
            } catch (e: ToolNotSupportedHttpException) {
                // If the user's custom model doesn't support native OpenAI `tools` parameter,
                // retry cleanly without `tools` parameter so any model works seamlessly!
                if (useToolsInRequest) {
                    useToolsInRequest = false
                    payload.remove("tools")
                    payload.remove("tool_choice")
                    postChatCompletion(cleanBase, settings.openAiApiKey, payload)
                } else {
                    throw e
                }
            }

            val choice = responseJson.optJSONArray("choices")?.optJSONObject(0)
                ?: throw IOException("Empty choices in API response")
            val messageObj = choice.optJSONObject("message")
                ?: throw IOException("Missing message in API response")

            val toolCalls = messageObj.optJSONArray("tool_calls")
            if (toolCalls != null && toolCalls.length() > 0) {
                // Append assistant message with tool_calls
                messages.put(messageObj)

                for (i in 0 until toolCalls.length()) {
                    val tc = toolCalls.optJSONObject(i) ?: continue
                    val callId = tc.optString("id", "call_${step}_$i")
                    val fnObj = tc.optJSONObject("function") ?: continue
                    val fnName = fnObj.optString("name", "")
                    val rawArgs = fnObj.optString("arguments", "{}")
                    val parsedArgs = runCatching { JSONObject(rawArgs) }.getOrDefault(JSONObject())

                    onToolStep?.invoke(
                        AgentStepEvent(
                            toolName = fnName,
                            statusText = formatToolDisplayLabel(fnName, parsedArgs, settings.appLanguage.isGerman)
                        )
                    )

                    val toolOutput = if (fnName.startsWith("mcp_")) {
                        LunaMcpClient.callMcpTool(settings.mcpServers, fnName, parsedArgs)
                    } else {
                        LunaAgentTools.executeBuiltInTool(context, fnName, parsedArgs)
                    }

                    executedTools.add(fnName)
                    messages.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", callId)
                            .put("name", fnName)
                            .put("content", toolOutput)
                    )
                }
                continue
            }

            val rawContent = messageObj.optString("content", "").trim()
            val apiReasoning = messageObj.optString("reasoning_content", "")
                .ifBlank { messageObj.optString("reasoning", "") }
                .ifBlank { messageObj.optString("thought", "") }
                .trim()

            val hadInlineThink = rawContent.contains("<think", ignoreCase = true)
            val cleanReply = stripThinkingBlocks(rawContent)

            if (apiReasoning.isNotBlank() || hadInlineThink) {
                val thinkBadge = if (settings.appLanguage.isGerman) "Gedacht" else "Thought"
                if (!executedTools.contains(thinkBadge)) {
                    executedTools.add(0, thinkBadge)
                }
            }

            return@withContext AgentCompletionResult(
                replyText = cleanReply.ifBlank {
                    if (settings.appLanguage.isGerman) "Erledigt." else "Done."
                },
                executedToolsSummary = executedTools,
                modelUsed = modelId
            )
        }

        AgentCompletionResult(
            replyText = if (settings.appLanguage.isGerman) {
                "Ich habe die gewünschten Aktionen ausgeführt."
            } else {
                "I have completed the requested actions."
            },
            executedToolsSummary = executedTools,
            modelUsed = modelId
        )
    }

    private class ToolNotSupportedHttpException(message: String) : IOException(message)

    private fun postChatCompletion(
        cleanBaseUrl: String,
        apiKey: String,
        payload: JSONObject
    ): JSONObject {
        val url = "$cleanBaseUrl/chat/completions"
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(jsonMediaType))

        if (apiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${apiKey.trim()}")
        }

        httpClient.newCall(reqBuilder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val lower = body.lowercase(Locale.ROOT)
                if (response.code == 400 && (lower.contains("tool") || lower.contains("function"))) {
                    throw ToolNotSupportedHttpException("HTTP ${response.code}: $body")
                }
                val errMsg = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull() ?: body.take(220)
                throw IOException("API Error (${response.code}): $errMsg")
            }
            return JSONObject(body)
        }
    }

    /**
     * Strips `<think>...</think>` and `<thinking>...</thinking>` blocks from reasoning models
     * so internal chain-of-thought is never spoken aloud by TTS.
     */
    fun stripThinkingBlocks(raw: String): String {
        return raw
            .replace(Regex("(?i)<think>[\\s\\S]*?</think>"), "")
            .replace(Regex("(?i)<thinking>[\\s\\S]*?</thinking>"), "")
            .replace(Regex("(?i)<thought>[\\s\\S]*?</thought>"), "")
            .replace(Regex("(?i)<think>[\\s\\S]*$"), "")
            .replace(Regex("(?i)<thinking>[\\s\\S]*$"), "")
            .trim()
    }

    private fun buildSystemPrompt(
        settings: LunaSettings,
        memoriesList: List<String>,
        detectedLanguage: String?
    ): String {
        val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm z (EEEE)", Locale.getDefault()).format(Date())
        val isDeviceGerman = settings.appLanguage.isGerman
        val langHint = when {
            detectedLanguage == "de" || isDeviceGerman ->
                "Default language: German (Deutsch). Always respond in natural, fluent German unless the user explicitly speaks another language."
            else ->
                "Default language: English. Respond in the user's language (defaulting to the device language). Keep responses concise, warm, and natural."
        }

        val memoryBlock = if (memoriesList.isNotEmpty()) {
            "\n\n### Persistent User Memories:\n" + memoriesList.joinToString("\n") { "- $it" }
        } else {
            "\n\n### Persistent User Memories:\n(No memories saved yet. Use `save_memory` whenever the user shares personal facts, preferences, or asks you to remember something.)"
        }

        val customExtra = if (settings.customSystemPrompt.isNotBlank()) {
            "\n\n### User Custom Instructions:\n${settings.customSystemPrompt.trim()}"
        } else {
            ""
        }

        return """
            You are Luna, a fast, intelligent, native Android Digital Assistant triggered via the phone's side button.
            Current local time on phone: $nowStr.
            $langHint
            
            ### STRICT VOICE / TTS OUTPUT RULES (FLOW TEXT ONLY):
            - Your response is spoken aloud directly by a neural Text-to-Speech (TTS) engine.
            - Write ONLY in smooth, natural, conversational flow text (Fließtext / full spoken sentences).
            - NEVER use Markdown tables, bullet points, numbered lists, headers (#), bold/italic asterisks (*), code blocks, or URLs.
            - Summarize comparisons, lists, or data into natural spoken sentences that are easy to listen to.
            - If you use internal thinking (<think>...</think>), keep all reasoning inside <think>...</think> and put only the final spoken flow-text answer outside.
            
            ### TOOLS & CAPABILITIES:
            You have direct access to Android hardware & system actions (`set_flashlight`, `open_app`, `set_volume`, `set_alarm_or_timer`, `get_device_status`, `open_system_settings`, `control_media`, `clipboard_action`), Web Grounding (`search_duckduckgo`, `read_website`), Persistent Memory (`save_memory`, `recall_memories`, `forget_memory`), and connected MCP tools.
            - When the user asks to turn on/off the flashlight ("Taschenlampe an/aus"), open an app, set an alarm/timer, or change volume, immediately invoke the corresponding tool.
            - When the user asks about current events, facts, prices, or websites, use `search_duckduckgo` and/or `read_website` to ground your answer accurately.
            $memoryBlock$customExtra
        """.trimIndent()
    }

    private fun formatToolDisplayLabel(fnName: String, args: JSONObject, isDe: Boolean): String {
        return when (fnName) {
            "set_flashlight" -> {
                val on = args.optBoolean("enabled", true)
                if (isDe) "Taschenlampe ${if (on) "EIN" else "AUS"}" else "Flashlight ${if (on) "ON" else "OFF"}"
            }
            "open_app" -> {
                val app = args.optString("app_name", "App")
                if (isDe) "Öffne $app..." else "Opening $app..."
            }
            "set_volume" -> {
                val pct = args.optInt("percent", 50)
                if (isDe) "Lautstärke auf $pct%..." else "Setting volume to $pct%..."
            }
            "set_alarm_or_timer" -> {
                if (isDe) "Stelle Wecker / Timer..." else "Setting alarm / timer..."
            }
            "search_duckduckgo" -> {
                val q = args.optString("query", "")
                if (isDe) "DuckDuckGo-Suche: \"$q\"" else "Searching DuckDuckGo: \"$q\""
            }
            "read_website" -> {
                val u = args.optString("url", "")
                if (isDe) "Lese Webseite: $u" else "Reading website: $u"
            }
            "save_memory" -> {
                val k = args.optString("key", "")
                if (isDe) "Speichere Erinnerung ($k)..." else "Saving memory ($k)..."
            }
            "recall_memories" -> {
                if (isDe) "Rufe Erinnerungen ab..." else "Recalling memories..."
            }
            else -> if (fnName.startsWith("mcp_")) {
                "MCP: ${fnName.removePrefix("mcp_")}"
            } else {
                fnName
            }
        }
    }

    fun normalizeBaseUrl(rawUrl: String): String {
        var clean = rawUrl.trim().trimEnd('/')
        if (clean.endsWith("/chat/completions")) {
            clean = clean.removeSuffix("/chat/completions").trimEnd('/')
        }
        return clean
    }
}
