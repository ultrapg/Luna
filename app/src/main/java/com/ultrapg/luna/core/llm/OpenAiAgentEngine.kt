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
import android.os.SystemClock
import com.ultrapg.luna.core.database.ChatMessageEntity
import com.ultrapg.luna.core.database.LunaDatabase
import com.ultrapg.luna.core.mcp.LunaMcpClient
import com.ultrapg.luna.core.settings.LunaSettings
import com.ultrapg.luna.core.settings.LunaSettingsRepository
import com.ultrapg.luna.core.settings.ThinkingEffort
import com.ultrapg.luna.core.tools.LunaAgentTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

/**
 * Detailed record of a single tool invocation or reasoning step so the user can tap any tool
 * icon above a chat message to inspect what it did and what the results were.
 */
data class ToolExecutionRecord(
    val stepIndex: Int,
    val toolName: String,
    val displayTitle: String,
    val whatItDid: String,
    val resultSummary: String,
    val rawArgumentsJson: String = "",
    val rawResultJson: String = "",
    val durationMs: Long = 0L
)

object ToolExecutionCodec {
    fun encode(records: List<ToolExecutionRecord>): String {
        if (records.isEmpty()) return ""
        val arr = JSONArray()
        for (r in records) {
            arr.put(
                JSONObject()
                    .put("step", r.stepIndex)
                    .put("tool", r.toolName)
                    .put("title", r.displayTitle)
                    .put("did", r.whatItDid)
                    .put("result", r.resultSummary)
                    .put("argsJson", r.rawArgumentsJson)
                    .put("resultJson", r.rawResultJson)
                    .put("ms", r.durationMs)
            )
        }
        return arr.toString()
    }

    fun decode(raw: String, isDe: Boolean = false): List<ToolExecutionRecord> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.startsWith("[")) {
            val parsed = runCatching {
                val arr = JSONArray(trimmed)
                val out = mutableListOf<ToolExecutionRecord>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val tool = obj.optString("tool", "tool")
                    out.add(
                        ToolExecutionRecord(
                            stepIndex = obj.optInt("step", i + 1),
                            toolName = tool,
                            displayTitle = obj.optString("title", tool),
                            whatItDid = obj.optString("did", ""),
                            resultSummary = obj.optString("result", ""),
                            rawArgumentsJson = obj.optString("argsJson", ""),
                            rawResultJson = obj.optString("resultJson", ""),
                            durationMs = obj.optLong("ms", 0L)
                        )
                    )
                }
                out
            }.getOrNull()
            if (parsed != null) return parsed
        }

        // Backwards-compatible fallback for older comma-separated tool summaries
        return trimmed.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapIndexed { idx, token ->
                ToolExecutionRecord(
                    stepIndex = idx + 1,
                    toolName = token,
                    displayTitle = token,
                    whatItDid = if (isDe) "Werkzeug ausgeführt: $token" else "Executed tool: $token",
                    resultSummary = if (isDe) "Erfolgreich ausgeführt." else "Completed successfully."
                )
            }
    }
}

data class AgentCompletionResult(
    val replyText: String,
    val executedToolsSummary: List<String>,
    val toolRecords: List<ToolExecutionRecord>,
    val encodedToolCallsSummary: String,
    val modelUsed: String
)

/**
 * OpenAI-Compatible API Client & Autonomous Multi-Step Agent Engine for Luna.
 *
 * Features:
 * - Connects to any OpenAI-compatible endpoint (`/v1/chat/completions`, `/v1/models`) —
 *   OpenAI, OpenRouter, Groq, DeepSeek, Ollama, LM Studio, vLLM, Google Gemini OpenAI endpoint, etc.
 * - Multi-step Function Calling loop (`tools` + `tool_calls`) with parallel tool execution within a step
 *   and shortest-path optimization for fast voice responses.
 * - Records detailed `ToolExecutionRecord` entries (`whatItDid` + `resultSummary`) so tapping tool icons
 *   in the chat displays full execution details and results.
 */
object OpenAiAgentEngine {
    private const val MAX_AGENT_STEPS = 8
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
     * Runs the full multi-step Luna agent loop with parallel tool execution, web grounding,
     * MCP tools, persistent memory, and detailed step recording.
     */
    suspend fun runAgentTurn(
        context: Context,
        conversationHistory: List<ChatMessageEntity>,
        userPrompt: String,
        detectedLanguage: String? = null,
        onToolStep: ((AgentStepEvent) -> Unit)? = null
    ): AgentCompletionResult = withContext(Dispatchers.IO) {
        val settings = LunaSettingsRepository.current(context)
        val isDe = settings.appLanguage.isGerman
        val cleanBase = normalizeBaseUrl(settings.openAiBaseUrl)
        val modelId = settings.openAiModel.trim()

        if (cleanBase.isBlank() || modelId.isBlank()) {
            return@withContext AgentCompletionResult(
                replyText = if (isDe) {
                    "Bitte trage zuerst deine Base-URL, deinen API-Key und dein Modell im KI-Tab ein."
                } else {
                    "Please enter your Base URL, API Key, and Model in the AI tab first."
                },
                executedToolsSummary = emptyList(),
                toolRecords = emptyList(),
                encodedToolCallsSummary = "",
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

        // Add recent conversation history (up to last 14 messages), including previous tool results
        // so continuous follow-up questions remember both what was spoken and what tools found
        val recentHistory = conversationHistory.takeLast(14)
        for (msg in recentHistory) {
            if (msg.role == "user" || msg.role == "assistant") {
                val enrichedContent = if (msg.role == "assistant" && msg.toolCallsSummary.isNotBlank()) {
                    val prevTools = ToolExecutionCodec.decode(msg.toolCallsSummary, isDe)
                        .filter { it.toolName != "thinking" }
                    if (prevTools.isNotEmpty()) {
                        val toolCtx = prevTools.joinToString("\n") { t ->
                            "[Tool ${t.toolName}: ${t.whatItDid} -> ${t.resultSummary.take(400)}]"
                        }
                        "$toolCtx\n${msg.content}"
                    } else {
                        msg.content
                    }
                } else {
                    msg.content
                }
                messages.put(
                    JSONObject()
                        .put("role", msg.role)
                        .put("content", enrichedContent)
                )
            }
        }
        messages.put(JSONObject().put("role", "user").put("content", userPrompt))

        val executedTools = mutableListOf<String>()
        val toolRecords = mutableListOf<ToolExecutionRecord>()
        val collectedThoughts = mutableListOf<String>()
        var useToolsInRequest = toolsArray.length() > 0
        var useThinkingParam = settings.thinkingEffort != ThinkingEffort.DEFAULT

        for (step in 0 until MAX_AGENT_STEPS) {
            val payload = JSONObject()
                .put("model", modelId)
                .put("messages", messages)
                .put("temperature", settings.temperature.toDouble())
                .put("max_tokens", settings.maxTokens)

            if (useThinkingParam) {
                when (settings.thinkingEffort) {
                    ThinkingEffort.OFF -> payload.put("reasoning_effort", "none")
                    ThinkingEffort.LOW -> payload.put("reasoning_effort", "low")
                    ThinkingEffort.MEDIUM -> payload.put("reasoning_effort", "medium")
                    ThinkingEffort.HIGH -> payload.put("reasoning_effort", "high")
                    ThinkingEffort.DEFAULT -> Unit
                }
            }

            if (useToolsInRequest && toolsArray.length() > 0) {
                payload.put("tools", toolsArray)
                payload.put("tool_choice", "auto")
            }

            val responseJson = try {
                postChatCompletion(cleanBase, settings.openAiApiKey, payload)
            } catch (e: ToolNotSupportedHttpException) {
                if (useThinkingParam && e.message?.lowercase(Locale.ROOT)?.contains("reasoning") == true) {
                    useThinkingParam = false
                    payload.remove("reasoning_effort")
                    postChatCompletion(cleanBase, settings.openAiApiKey, payload)
                } else if (useToolsInRequest) {
                    useToolsInRequest = false
                    payload.remove("tools")
                    payload.remove("tool_choice")
                    postChatCompletion(cleanBase, settings.openAiApiKey, payload)
                } else if (useThinkingParam) {
                    useThinkingParam = false
                    payload.remove("reasoning_effort")
                    postChatCompletion(cleanBase, settings.openAiApiKey, payload)
                } else {
                    throw e
                }
            }

            val choice = responseJson.optJSONArray("choices")?.optJSONObject(0)
                ?: throw IOException("Empty choices in API response")
            val messageObj = choice.optJSONObject("message")
                ?: throw IOException("Missing message in API response")

            // Capture any reasoning / <think> from this step
            val stepRawContent = messageObj.optString("content", "").trim()
            val stepReasoning = extractThinkingContent(messageObj, stepRawContent)
            if (stepReasoning.isNotBlank()) {
                collectedThoughts.add(stepReasoning)
            }

            val toolCalls = messageObj.optJSONArray("tool_calls")
            if (toolCalls != null && toolCalls.length() > 0) {
                messages.put(messageObj)

                data class PendingCall(
                    val index: Int,
                    val callId: String,
                    val fnName: String,
                    val rawArgs: String,
                    val parsedArgs: JSONObject
                )

                val pendingCalls = mutableListOf<PendingCall>()
                for (i in 0 until toolCalls.length()) {
                    val tc = toolCalls.optJSONObject(i) ?: continue
                    val callId = tc.optString("id", "call_${step}_$i")
                    val fnObj = tc.optJSONObject("function") ?: continue
                    val fnName = fnObj.optString("name", "")
                    if (fnName.isBlank()) continue
                    val rawArgs = fnObj.optString("arguments", "{}")
                    val parsedArgs = runCatching { JSONObject(rawArgs) }.getOrDefault(JSONObject())
                    pendingCalls.add(PendingCall(i, callId, fnName, rawArgs, parsedArgs))
                }

                // Notify UI status for the current step
                pendingCalls.firstOrNull()?.let { firstCall ->
                    onToolStep?.invoke(
                        AgentStepEvent(
                            toolName = firstCall.fnName,
                            statusText = formatToolDisplayLabel(firstCall.fnName, firstCall.parsedArgs, isDe)
                        )
                    )
                }

                // Execute all tool calls in this step concurrently for minimum latency
                val stepOutputs = coroutineScope {
                    pendingCalls.map { call ->
                        async(Dispatchers.IO) {
                            val startMs = SystemClock.elapsedRealtime()
                            val output = if (call.fnName.startsWith("mcp_")) {
                                LunaMcpClient.callMcpTool(settings.mcpServers, call.fnName, call.parsedArgs)
                            } else {
                                LunaAgentTools.executeBuiltInTool(context, call.fnName, call.parsedArgs)
                            }
                            val elapsedMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
                            Triple(call, output, elapsedMs)
                        }
                    }.awaitAll()
                }

                for ((call, toolOutput, elapsedMs) in stepOutputs) {
                    executedTools.add(call.fnName)
                    val record = buildToolExecutionRecord(
                        stepIndex = toolRecords.size + 1,
                        fnName = call.fnName,
                        args = call.parsedArgs,
                        rawArgs = call.rawArgs,
                        rawOutput = toolOutput,
                        durationMs = elapsedMs,
                        isDe = isDe
                    )
                    toolRecords.add(record)

                    messages.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", call.callId)
                            .put("name", call.fnName)
                            .put("content", toolOutput)
                    )
                }
                continue
            }

            val cleanReply = stripThinkingBlocks(stepRawContent)

            if (collectedThoughts.isNotEmpty()) {
                val thinkTitle = if (isDe) "Gedacht" else "Thought"
                executedTools.add(0, thinkTitle)
                toolRecords.add(
                    0,
                    ToolExecutionRecord(
                        stepIndex = 0,
                        toolName = "thinking",
                        displayTitle = thinkTitle,
                        whatItDid = if (isDe) {
                            "Interne mehrstufige Überlegung des Modells (nicht vorgelesen)"
                        } else {
                            "Model internal reasoning & planning (not spoken aloud by TTS)"
                        },
                        resultSummary = collectedThoughts.joinToString("\n\n"),
                        rawArgumentsJson = "",
                        rawResultJson = collectedThoughts.joinToString("\n\n"),
                        durationMs = 0L
                    )
                )
            }

            return@withContext AgentCompletionResult(
                replyText = cleanReply.ifBlank {
                    if (isDe) "Erledigt." else "Done."
                },
                executedToolsSummary = executedTools,
                toolRecords = toolRecords,
                encodedToolCallsSummary = ToolExecutionCodec.encode(toolRecords),
                modelUsed = modelId
            )
        }

        AgentCompletionResult(
            replyText = if (isDe) {
                "Ich habe die gewünschten Aktionen ausgeführt."
            } else {
                "I have completed the requested actions."
            },
            executedToolsSummary = executedTools,
            toolRecords = toolRecords,
            encodedToolCallsSummary = ToolExecutionCodec.encode(toolRecords),
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
                if (response.code == 400 && (lower.contains("tool") || lower.contains("function") || lower.contains("reasoning"))) {
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

    private fun extractThinkingContent(messageObj: JSONObject, rawContent: String): String {
        val apiReasoning = messageObj.optString("reasoning_content", "")
            .ifBlank { messageObj.optString("reasoning", "") }
            .ifBlank { messageObj.optString("thought", "") }
            .trim()

        val inlineMatches = Regex("(?i)<(?:think|thinking|thought)>([\\s\\S]*?)</(?:think|thinking|thought)>")
            .findAll(rawContent)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()

        return (listOfNotNull(apiReasoning.takeIf { it.isNotBlank() }) + inlineMatches)
            .distinct()
            .joinToString("\n\n")
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
            
            ### MULTI-STEP TASKS & SHORTEST-PATH SPEED RULES (CRITICAL FOR VOICE):
            You can execute multi-step tasks and call multiple tools across steps or in parallel, including Android hardware/system actions (`set_flashlight`, `open_app`, `set_volume`, `set_alarm_or_timer`, `get_device_status`, `open_system_settings`, `control_media`, `clipboard_action`), Web Grounding (`search_duckduckgo`, `read_website`), Persistent Memory (`save_memory`, `recall_memories`, `forget_memory`), and MCP tools.
            Because you are a real-time speech assistant, ALWAYS choose the SHORTEST, FASTEST path to get information or complete a task:
            1. Batch independent actions together: If the user asks for multiple actions at once (e.g., "turn on the flashlight, set volume to 40%, and open Spotify"), emit all `tool_calls` in a SINGLE step so they run in parallel.
            2. Fastest web lookup first: When looking up facts, news, weather, or prices, call `search_duckduckgo` first. If the returned `instant_answer` or `results` snippets already answer the question, respond IMMEDIATELY in the next turn without calling `read_website`. Only call `read_website` as a follow-up step when you genuinely need full-page details not present in the search snippets or when the user asks you to read a specific webpage.
            3. Chain dependent steps cleanly: When a request truly requires sequential steps (e.g., search for a topic, read a specific article URL from the results, save a summary to memory, and open an app), execute each necessary step concisely and then give a clear spoken confirmation.
            $memoryBlock$customExtra
        """.trimIndent()
    }

    private fun buildToolExecutionRecord(
        stepIndex: Int,
        fnName: String,
        args: JSONObject,
        rawArgs: String,
        rawOutput: String,
        durationMs: Long,
        isDe: Boolean
    ): ToolExecutionRecord {
        val outJson = runCatching { JSONObject(rawOutput) }.getOrNull()
        val isOk = outJson?.optBoolean("ok", true) ?: !rawOutput.contains("\"error\"")

        return when (fnName) {
            "set_flashlight" -> {
                val on = args.optBoolean("enabled", true)
                ToolExecutionRecord(
                    stepIndex = stepIndex,
                    toolName = fnName,
                    displayTitle = if (isDe) "Taschenlampe ${if (on) "EIN" else "AUS"}" else "Flashlight ${if (on) "ON" else "OFF"}",
                    whatItDid = if (isDe) {
                        "Kamera-Taschenlampe ${if (on) "eingeschaltet" else "ausgeschaltet"}"
                    } else {
                        "Turned phone camera flashlight ${if (on) "ON" else "OFF"}"
                    },
                    resultSummary = outJson?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: if (isOk) {
                            if (isDe) "Taschenlampe ist jetzt ${if (on) "an" else "aus"}." else "Flashlight is now ${if (on) "on" else "off"}."
                        } else {
                            outJson?.optString("error", rawOutput) ?: rawOutput
                        },
                    rawArgumentsJson = rawArgs,
                    rawResultJson = rawOutput,
                    durationMs = durationMs
                )
            }
            "open_app" -> {
                val app = args.optString("app_name", "App")
                val launched = outJson?.optString("launched_app", app).orEmpty().ifBlank { app }
                val pkg = outJson?.optString("package_name", "").orEmpty()
                ToolExecutionRecord(
                    stepIndex = stepIndex,
                    toolName = fnName,
                    displayTitle = if (isDe) "App: $launched" else "Open: $launched",
                    whatItDid = if (isDe) "App \"$app\" auf dem Gerät gesucht und gestartet" else "Searched for and launched installed app \"$app\"",
                    resultSummary = if (isOk) {
                        if (isDe) "Geöffnet: $launched ($pkg)" else "Launched: $launched ($pkg)"
                    } else {
                        outJson?.optString("error", rawOutput) ?: rawOutput
                    },
                    rawArgumentsJson = rawArgs,
                    rawResultJson = rawOutput,
                    durationMs = durationMs
                )
            }
            "set_volume" -> {
                val stream = args.optString("stream", "music")
                val pct = args.optInt("percent", 50)
                val level = outJson?.optString("level", "").orEmpty()
                ToolExecutionRecord(
                    stepIndex = stepIndex,
                    toolName = fnName,
                    displayTitle = if (isDe) "Lautstärke $pct%" else "Volume $pct%",
                    whatItDid = if (isDe) "Systemlautstärke ($stream) auf $pct% gesetzt" else "Set Android '$stream' stream volume to $pct%",
                    resultSummary = if (isOk) {
                        if (isDe) "Lautstärke ($stream) auf $pct% gesetzt ($level)" else "Volume ($stream) set to $pct% ($level)"
                    } else {
                        outJson?.optString("error", rawOutput) ?: rawOutput
                    },
                    rawArgumentsJson = rawArgs,
                    rawResultJson = rawOutput,
                    durationMs = durationMs
                )
            }
            "set_alarm_or_timer" -> {
                val mode = args.optString("mode", "alarm")
                val label = args.optString("label", "Luna")
                val summary = if (mode.equals("timer", ignoreCase = true)) {
                    val secs = args.optInt("seconds", 60)
                    if (isDe) "Timer auf ${secs}s gestellt ($label)" else "Set timer for ${secs}s ($label)"
                } else {
                    val time = outJson?.optString("time", "${args.optInt("hour", 8)}:${args.optInt("minute", 0)}")
                    if (isDe) "Wecker auf $time gestellt ($label)" else "Set alarm for $time ($label)"
                }
                ToolExecutionRecord(
                    stepIndex = stepIndex,
                    toolName = fnName,
                    displayTitle = if (mode.equals("timer", true)) {
                        if (isDe) "Timer" else "Timer"
                    } else {
                        if (isDe) "Wecker" else "Alarm"
                    },
                    whatItDid = summary,
                    resultSummary = if (isOk) summary else (outJson?.optString("error", rawOutput) ?: rawOutput),
                    rawArgumentsJson = rawArgs,
                    rawResultJson = rawOutput,
                    durationMs = durationMs
                )
            }
            "get_device_status" -> {
                val bat = outJson?.optInt("battery_percent", -1) ?: -1
                val charging = outJson?.optBoolean("is_charging", false) ?: false
                val vol = outJson?.optInt("music_volume_percent", -1) ?: -1
                val time = outJson?.optString("current_time", "").orEmpty()
                val formatted = buildString {
                    if (bat >= 0) append(if (isDe) "Akku: $bat% (${if (charging) "Lädt" else "Entlädt"})\n" else "Battery: $bat% (${if (charging) "Charging" else "Discharging"})\n")
                    if (vol >= 0) append(if (isDe) "Medienlautstärke: $vol%\n" else "Media Volume: $vol%\n")
                    if (time.isNotBlank()) append(if (isDe) "Zeit: $time" else "Time: $time")
                }.trim().ifBlank { rawOutput }

                ToolExecutionRecord(
                    stepIndex = stepIndex,
                    toolName = fnName,
                    displayTitle = if (isDe) "Gerätestatus" else "Device Status",
                    whatItDid = if (isDe) "Akku, Lautstärke, Uhrzeit und Taschenlampen-Status abgefragt" else "Queried battery level, charging state, volume, and system time",
                    resultSummary = formatted,
                    rawArgumentsJson = rawArgs,
                    rawResultJson = rawOutput,
                    durationMs = durationMs
                )
            }
            "open_system_settings" -> {
                val sec = args.optString("section", "main")
                ToolExecutionRecord(
                    stepIndex = stepIndex,
                    toolName = fnName,
                    displayTitle = if (isDe) "Einstellungen ($sec)" else "Settings ($sec)",
                    whatItDid = if (isDe) "Android-Systemeinstellungen geöffnet: $sec" else "Opened Android system settings section: $sec",
                    resultSummary = if (isDe) "Bereich '$sec' geöffnet." else "Opened '$sec' settings screen.",
                    rawArgumentsJson = rawArgs,
                    rawResultJson = rawOutput,
                    durationMs = durationMs
                )
            }
            "control_media" -> {
                val act = args.optString("action", "play_pause")
                ToolExecutionRecord(
                    stepIndex = stepIndex,
                    toolName = fnName,
                    displayTitle = if (isDe) "Medien: $act" else "Media: $act",
                    whatItDid = if (isDe) "Medienbefehl '$act' gesendet" else "Sent media playback command '$act'",
                    resultSummary = if (isDe) "Ausgeführt: $act" else "Executed: $act",
                    rawArgumentsJson = rawArgs,
                    rawResultJson = rawOutput,
                    durationMs = durationMs
                )
            }
            "clipboard_action" -> {
                val act = args.optString("action", "read")
                ToolExecutionRecord(
                    stepIndex = stepIndex,
                    toolName = fnName,
                    displayTitle = if (isDe) "Zwischenablage" else "Clipboard",
                    whatItDid = if (isDe) "Zwischenablage-Aktion: $act" else "Clipboard action: $act",
                    resultSummary = outJson?.optString("clipboard_text")?.takeIf { it.isNotBlank() }
                        ?: if (isDe) "Kopiert (${outJson?.optInt("copied_length", 0)} Zeichen)" else "Copied (${outJson?.optInt("copied_length", 0)} chars)",
                    rawArgumentsJson = rawArgs,
                    rawResultJson = rawOutput,
                    durationMs = durationMs
                )
            }
            "search_duckduckgo" -> {
                val q = args.optString("query", "")
                val instant = outJson?.optString("instant_answer", "").orEmpty()
                val arr = outJson?.optJSONArray("results")
                val formattedResults = buildString {
                    if (instant.isNotBlank()) {
                        append(if (isDe) "Direktantwort: " else "Instant Answer: ")
                        append(instant)
                        append("\n\n")
                    }
                    if (arr != null && arr.length() > 0) {
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            val title = item.optString("title", "")
                            val snippet = item.optString("snippet", "")
                            val url = item.optString("url", "")
                            append("${i + 1}. $title\n")
                            if (snippet.isNotBlank()) append("   $snippet\n")
                            if (url.isNotBlank()) append("   $url\n")
                            if (i < arr.length() - 1) append("\n")
                        }
                    } else if (instant.isBlank()) {
                        append(if (isDe) "Keine Treffer gefunden." else "No results found.")
                    }
                }.trim()

                ToolExecutionRecord(
                    stepIndex = stepIndex,
                    toolName = fnName,
                    displayTitle = if (isDe) "Suche: ${q.take(22)}" else "Search: ${q.take(22)}",
                    whatItDid = if (isDe) "DuckDuckGo-Websuche nach \"$q\"" else "Searched DuckDuckGo for \"$q\"",
                    resultSummary = formattedResults,
                    rawArgumentsJson = rawArgs,
                    rawResultJson = rawOutput,
                    durationMs = durationMs
                )
            }
            "read_website" -> {
                val u = args.optString("url", "")
                val title = outJson?.optString("title", u).orEmpty()
                val content = outJson?.optString("content", "").orEmpty().take(1200)
                ToolExecutionRecord(
                    stepIndex = stepIndex,
                    toolName = fnName,
                    displayTitle = if (isDe) "Webseite gelesen" else "Read Website",
                    whatItDid = if (isDe) "Webseite abgerufen und Text extrahiert: $u" else "Fetched and extracted readable text from: $u",
                    resultSummary = buildString {
                        if (title.isNotBlank()) append("$title\n\n")
                        append(content.ifBlank { rawOutput.take(800) })
                    }.trim(),
                    rawArgumentsJson = rawArgs,
                    rawResultJson = rawOutput,
                    durationMs = durationMs
                )
            }
            "save_memory" -> {
                val k = args.optString("key", "")
                val v = args.optString("value", "")
                ToolExecutionRecord(
                    stepIndex = stepIndex,
                    toolName = fnName,
                    displayTitle = if (isDe) "Merke: $k" else "Saved: $k",
                    whatItDid = if (isDe) "Erinnerung \"$k\" lokal gespeichert" else "Saved persistent memory key \"$k\"",
                    resultSummary = "$k = $v",
                    rawArgumentsJson = rawArgs,
                    rawResultJson = rawOutput,
                    durationMs = durationMs
                )
            }
            "recall_memories" -> {
                val q = args.optString("query", "")
                val count = outJson?.optInt("count", 0) ?: 0
                val memArr = outJson?.optJSONArray("memories")
                val summary = buildString {
                    if (memArr != null && memArr.length() > 0) {
                        for (i in 0 until memArr.length()) {
                            val m = memArr.optJSONObject(i) ?: continue
                            append("• ${m.optString("key")}: ${m.optString("value")}\n")
                        }
                    } else {
                        append(if (isDe) "$count Erinnerungen gefunden." else "$count memories found.")
                    }
                }.trim()
                ToolExecutionRecord(
                    stepIndex = stepIndex,
                    toolName = fnName,
                    displayTitle = if (isDe) "Erinnerungen ($count)" else "Memories ($count)",
                    whatItDid = if (q.isBlank()) {
                        if (isDe) "Alle gespeicherten Erinnerungen abgerufen" else "Recalled all stored memories"
                    } else {
                        if (isDe) "Erinnerungen durchsucht nach \"$q\"" else "Searched memories for \"$q\""
                    },
                    resultSummary = summary,
                    rawArgumentsJson = rawArgs,
                    rawResultJson = rawOutput,
                    durationMs = durationMs
                )
            }
            "forget_memory" -> {
                val k = args.optString("key", "")
                ToolExecutionRecord(
                    stepIndex = stepIndex,
                    toolName = fnName,
                    displayTitle = if (isDe) "Gelöscht: $k" else "Forgot: $k",
                    whatItDid = if (isDe) "Erinnerung \"$k\" gelöscht" else "Deleted memory \"$k\"",
                    resultSummary = if (isDe) "Schlüssel \"$k\" entfernt." else "Removed key \"$k\".",
                    rawArgumentsJson = rawArgs,
                    rawResultJson = rawOutput,
                    durationMs = durationMs
                )
            }
            else -> {
                val cleanName = if (fnName.startsWith("mcp_")) "MCP: ${fnName.removePrefix("mcp_")}" else fnName
                ToolExecutionRecord(
                    stepIndex = stepIndex,
                    toolName = fnName,
                    displayTitle = cleanName,
                    whatItDid = if (isDe) "Werkzeug '$cleanName' mit Parametern $rawArgs aufgerufen" else "Called '$cleanName' with parameters $rawArgs",
                    resultSummary = rawOutput.take(1200),
                    rawArgumentsJson = rawArgs,
                    rawResultJson = rawOutput,
                    durationMs = durationMs
                )
            }
        }
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
