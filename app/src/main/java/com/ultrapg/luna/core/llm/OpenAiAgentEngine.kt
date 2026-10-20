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
import com.ultrapg.luna.core.mcp.McpRemoteTool
import com.ultrapg.luna.core.settings.AppLanguage
import com.ultrapg.luna.core.settings.LunaSettings
import com.ultrapg.luna.core.settings.LunaSettingsRepository
import com.ultrapg.luna.core.settings.ThinkingEffort
import com.ultrapg.luna.core.tools.LunaAgentTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
 * Detailed record of a single tool invocation, API built-in MCP execution, or reasoning step
 * so the user can inspect the full multi-step execution timeline in the chat UI.
 */
data class ToolExecutionRecord(
    val stepIndex: Int,
    val toolName: String,
    val displayTitle: String,
    val whatItDid: String,
    val resultSummary: String,
    val rawArgumentsJson: String = "",
    val rawResultJson: String = "",
    val durationMs: Long = 0L,
    val category: String = inferCategory(toolName),
    val status: String = "DONE" // "RUNNING", "DONE", "ERROR"
) {
    companion object {
        fun inferCategory(name: String): String {
            val lower = name.trim().lowercase(Locale.ROOT)
            return when {
                lower == "thinking" || lower == "thought" || lower == "gedacht" -> "THINKING"
                lower == "search_duckduckgo" || lower == "read_website" || lower == "deep_research" -> "WEB"
                lower == "save_memory" || lower == "recall_memories" || lower == "forget_memory" -> "MEMORY"
                lower.startsWith("api_mcp") || lower.startsWith("mcp_api_") || lower.startsWith("builtin_mcp") -> "API_MCP"
                lower.startsWith("mcp_") -> "MCP"
                LunaAgentTools.isBuiltInTool(lower) -> "ANDROID"
                else -> "API_MCP"
            }
        }
    }
}

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
                    .put("cat", r.category)
                    .put("status", r.status)
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
                    val cat = obj.optString("cat", "").ifBlank { ToolExecutionRecord.inferCategory(tool) }
                    out.add(
                        ToolExecutionRecord(
                            stepIndex = obj.optInt("step", i + 1),
                            toolName = tool,
                            displayTitle = obj.optString("title", tool),
                            whatItDid = obj.optString("did", ""),
                            resultSummary = obj.optString("result", ""),
                            rawArgumentsJson = obj.optString("argsJson", ""),
                            rawResultJson = obj.optString("resultJson", ""),
                            durationMs = obj.optLong("ms", 0L),
                            category = cat,
                            status = obj.optString("status", "DONE")
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
 *   OpenAI, OpenRouter, Groq, DeepSeek, Ollama, LM Studio, vLLM, etc.
 * - Supports both client-side tools (Android Hardware, Web Search/Reader, Memory, User MCP Servers)
 *   AND API Built-In MCPs (`<baseUrl>/mcp` auto-discovery + server-executed `executed_tools` / `mcp_calls` / `citations`).
 * - Streams live multi-step execution traces (`onLiveTraceUpdated`) so the chat UI renders a live
 *   interactive step-by-step progress timeline while the assistant is working.
 */
object OpenAiAgentEngine {
    private const val MAX_AGENT_STEPS = 12
    private const val MAX_PROVIDER_RETRIES = 5
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
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
     * API built-in MCPs + user MCP servers, persistent memory, and live step streaming.
     */
    suspend fun runAgentTurn(
        context: Context,
        conversationHistory: List<ChatMessageEntity>,
        userPrompt: String,
        detectedLanguage: String? = null,
        onToolStep: ((AgentStepEvent) -> Unit)? = null,
        onLiveTraceUpdated: ((List<ToolExecutionRecord>, String) -> Unit)? = null,
        onRetryStateChanged: ((Boolean, Int) -> Unit)? = null
    ): AgentCompletionResult = withContext(Dispatchers.IO) {
        val settings = LunaSettingsRepository.current(context)
        val isDe = settings.appLanguage.isGerman
        val cleanBase = normalizeBaseUrl(settings.openAiBaseUrl)
        val modelId = settings.openAiModel.trim()

        if (cleanBase.isBlank() || modelId.isBlank()) {
            throw IOException(
                if (isDe) {
                    "Bitte trage zuerst deine Base-URL, deinen API-Key und dein Modell im KI-Tab ein."
                } else {
                    "Please enter your Base URL, API Key, and Model in the AI tab first."
                }
            )
        }

        val memories = if (settings.memoryEnabled) {
            runCatching { LunaDatabase.getInstance(context).memoryDao().getAllMemories() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        // Build OpenAI tools array (Android actions + DuckDuckGo + Web Reader + Memory + User MCPs + API Built-In MCPs)
        val toolsArray = LunaAgentTools.buildOpenAiToolSchemas(context)
        val mcpTools = if (settings.mcpServers.any { it.enabled } || settings.apiBuiltInMcpEnabled) {
            runCatching {
                LunaMcpClient.discoverAllTools(
                    servers = settings.mcpServers,
                    apiBaseUrl = cleanBase,
                    apiKey = settings.openAiApiKey,
                    enableApiBuiltInMcp = settings.apiBuiltInMcpEnabled
                )
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        LunaMcpClient.appendMcpToolsToOpenAiArray(toolsArray, mcpTools)

        val effectivePromptLang = detectLanguageOfText(
            text = userPrompt,
            fallback = detectedLanguage ?: settings.appLanguage.resolveLanguageCode()
        )
        val systemPrompt = buildSystemPrompt(
            settings = settings,
            memoriesList = memories.map { "${it.key}: ${it.value}" },
            userPrompt = userPrompt,
            detectedLanguage = effectivePromptLang,
            discoveredMcpTools = mcpTools
        )

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))

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
        var totalThinkingDurationMs = 0L
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

            val llmStepStartMs = SystemClock.elapsedRealtime()
            var responseJson: JSONObject? = null
            var lastError: Throwable? = null

            for (attempt in 0..MAX_PROVIDER_RETRIES) {
                try {
                    val candidate = try {
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

                    val testChoice = candidate.optJSONArray("choices")?.optJSONObject(0)
                        ?: throw IOException("Empty choices in API response")
                    if (testChoice.optJSONObject("message") == null) {
                        throw IOException("Missing message in API response")
                    }

                    responseJson = candidate
                    if (attempt > 0) {
                        onRetryStateChanged?.invoke(false, 0)
                    }
                    break
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    lastError = t
                    if (attempt < MAX_PROVIDER_RETRIES) {
                        val retryNum = attempt + 1
                        onRetryStateChanged?.invoke(true, retryNum)
                        onToolStep?.invoke(
                            AgentStepEvent(
                                toolName = "retry",
                                statusText = if (isDe) {
                                    "Anbieter antwortet nicht – Versuch $retryNum/$MAX_PROVIDER_RETRIES..."
                                } else {
                                    "Provider not responding – Retry $retryNum/$MAX_PROVIDER_RETRIES..."
                                }
                            )
                        )
                        delay(900L)
                    } else {
                        onRetryStateChanged?.invoke(false, MAX_PROVIDER_RETRIES)
                        throw t
                    }
                }
            }

            val validResponseJson = responseJson ?: throw (lastError ?: IOException("Provider did not respond"))
            val llmStepElapsedMs = (SystemClock.elapsedRealtime() - llmStepStartMs).coerceAtLeast(1L)

            val choice = validResponseJson.optJSONArray("choices")?.optJSONObject(0)
                ?: throw IOException("Empty choices in API response")
            val messageObj = choice.optJSONObject("message")
                ?: throw IOException("Missing message in API response")

            // 1. Capture any reasoning / <think> from this step
            val stepRawContent = messageObj.optString("content", "").trim()
            val stepReasoning = extractThinkingContent(messageObj, stepRawContent)
            if (stepReasoning.isNotBlank()) {
                collectedThoughts.add(stepReasoning)
                totalThinkingDurationMs += llmStepElapsedMs
                onLiveTraceUpdated?.invoke(toolRecords.toList(), collectedThoughts.joinToString("\n\n"))
            }

            // 2. Capture any API Built-In MCP / server-executed tools returned directly in the response
            if (settings.apiBuiltInMcpEnabled) {
                val serverExecutedRecords = extractServerSideExecutedTools(
                    responseJson = validResponseJson,
                    choiceObj = choice,
                    messageObj = messageObj,
                    startingStepIndex = toolRecords.size + 1,
                    defaultDurationMs = llmStepElapsedMs,
                    isDe = isDe
                )
                if (serverExecutedRecords.isNotEmpty()) {
                    for (srvRec in serverExecutedRecords) {
                        // Avoid duplicate insertion across turns
                        if (toolRecords.none { it.toolName == srvRec.toolName && it.whatItDid == srvRec.whatItDid }) {
                            executedTools.add(srvRec.displayTitle)
                            toolRecords.add(srvRec)
                        }
                    }
                    onLiveTraceUpdated?.invoke(toolRecords.toList(), collectedThoughts.joinToString("\n\n"))
                }
            }

            val toolCalls = messageObj.optJSONArray("tool_calls")
            if (toolCalls != null && toolCalls.length() > 0) {
                messages.put(messageObj)

                data class PendingCall(
                    val index: Int,
                    val callId: String,
                    val fnName: String,
                    val rawArgs: String,
                    val parsedArgs: JSONObject,
                    val prePopulatedServerOutput: String?
                )

                val pendingCalls = mutableListOf<PendingCall>()
                for (i in 0 until toolCalls.length()) {
                    val tc = toolCalls.optJSONObject(i) ?: continue
                    val callId = tc.optString("id", "call_${step}_$i")
                    val fnObj = tc.optJSONObject("function") ?: tc
                    val fnName = fnObj.optString("name", "").ifBlank { tc.optString("name", "") }
                    if (fnName.isBlank()) continue
                    val rawArgs = fnObj.optString("arguments", "").ifBlank { tc.optString("arguments", "{}") }
                    val parsedArgs = runCatching { JSONObject(rawArgs) }.getOrDefault(JSONObject())
                    val serverOutput = fnObj.optString("output", "")
                        .ifBlank { tc.optString("output", "") }
                        .ifBlank { tc.optString("result", "") }
                        .takeIf { it.isNotBlank() }
                    pendingCalls.add(PendingCall(i, callId, fnName, rawArgs, parsedArgs, serverOutput))
                }

                // Emit live RUNNING placeholders to the UI so the user sees the multi-step plan in real time
                val runningPreviewRecords = pendingCalls.mapIndexed { idx, call ->
                    ToolExecutionRecord(
                        stepIndex = toolRecords.size + idx + 1,
                        toolName = call.fnName,
                        displayTitle = formatToolDisplayLabel(call.fnName, call.parsedArgs, isDe),
                        whatItDid = formatToolDisplayLabel(call.fnName, call.parsedArgs, isDe),
                        resultSummary = if (isDe) "Wird ausgeführt..." else "Running...",
                        rawArgumentsJson = call.rawArgs,
                        durationMs = 0L,
                        category = resolveToolCategory(call.fnName, call.prePopulatedServerOutput != null),
                        status = "RUNNING"
                    )
                }
                onLiveTraceUpdated?.invoke(
                    toolRecords + runningPreviewRecords,
                    collectedThoughts.joinToString("\n\n")
                )

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
                            val matchedMcp = LunaMcpClient.findMatchingMcpTool(call.fnName)
                            val isBuiltIn = LunaAgentTools.isBuiltInTool(call.fnName)

                            val (output, category) = when {
                                call.prePopulatedServerOutput != null -> {
                                    call.prePopulatedServerOutput to "API_MCP"
                                }
                                matchedMcp != null -> {
                                    val mcpOut = LunaMcpClient.callMcpTool(
                                        servers = settings.mcpServers,
                                        requestedToolName = call.fnName,
                                        arguments = call.parsedArgs,
                                        apiBaseUrl = cleanBase,
                                        apiKey = settings.openAiApiKey,
                                        enableApiBuiltInMcp = settings.apiBuiltInMcpEnabled
                                    )
                                    mcpOut to (if (matchedMcp.isApiBuiltIn) "API_MCP" else "MCP")
                                }
                                call.fnName.startsWith("mcp_") || !isBuiltIn -> {
                                    val mcpOut = LunaMcpClient.callMcpTool(
                                        servers = settings.mcpServers,
                                        requestedToolName = call.fnName,
                                        arguments = call.parsedArgs,
                                        apiBaseUrl = cleanBase,
                                        apiKey = settings.openAiApiKey,
                                        enableApiBuiltInMcp = settings.apiBuiltInMcpEnabled
                                    )
                                    val cat = if (call.fnName.startsWith("mcp_api_") || !call.fnName.startsWith("mcp_")) {
                                        "API_MCP"
                                    } else {
                                        "MCP"
                                    }
                                    mcpOut to cat
                                }
                                else -> {
                                    val builtInOut = LunaAgentTools.executeBuiltInTool(context, call.fnName, call.parsedArgs)
                                    builtInOut to ToolExecutionRecord.inferCategory(call.fnName)
                                }
                            }
                            val elapsedMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
                            Triple(call, output to category, elapsedMs)
                        }
                    }.awaitAll()
                }

                var serverAlreadyExecutedAll = true
                for ((call, outputAndCat, elapsedMs) in stepOutputs) {
                    val (toolOutput, category) = outputAndCat
                    if (call.prePopulatedServerOutput == null) {
                        serverAlreadyExecutedAll = false
                    }
                    executedTools.add(call.fnName)
                    val record = buildToolExecutionRecord(
                        stepIndex = toolRecords.size + 1,
                        fnName = call.fnName,
                        args = call.parsedArgs,
                        rawArgs = call.rawArgs,
                        rawOutput = toolOutput,
                        durationMs = elapsedMs,
                        categoryOverride = category,
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

                onLiveTraceUpdated?.invoke(toolRecords.toList(), collectedThoughts.joinToString("\n\n"))

                // If the API executed the tool calls server-side AND already provided the final spoken answer
                // in the same message, we don't need an extra round-trip!
                val possibleFinalText = stripThinkingBlocks(stepRawContent)
                if (serverAlreadyExecutedAll && possibleFinalText.isNotBlank()) {
                    prependThinkingRecordIfPresent(
                        collectedThoughts = collectedThoughts,
                        totalThinkingDurationMs = totalThinkingDurationMs,
                        executedTools = executedTools,
                        toolRecords = toolRecords,
                        isDe = isDe
                    )
                    return@withContext AgentCompletionResult(
                        replyText = possibleFinalText,
                        executedToolsSummary = executedTools,
                        toolRecords = toolRecords,
                        encodedToolCallsSummary = ToolExecutionCodec.encode(toolRecords),
                        modelUsed = modelId
                    )
                }
                continue
            }

            val cleanReply = stripThinkingBlocks(stepRawContent)

            prependThinkingRecordIfPresent(
                collectedThoughts = collectedThoughts,
                totalThinkingDurationMs = totalThinkingDurationMs,
                executedTools = executedTools,
                toolRecords = toolRecords,
                isDe = isDe
            )

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

        prependThinkingRecordIfPresent(
            collectedThoughts = collectedThoughts,
            totalThinkingDurationMs = totalThinkingDurationMs,
            executedTools = executedTools,
            toolRecords = toolRecords,
            isDe = isDe
        )

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

    private fun prependThinkingRecordIfPresent(
        collectedThoughts: List<String>,
        totalThinkingDurationMs: Long,
        executedTools: MutableList<String>,
        toolRecords: MutableList<ToolExecutionRecord>,
        isDe: Boolean
    ) {
        if (collectedThoughts.isNotEmpty() && toolRecords.none { it.toolName == "thinking" }) {
            val thinkTitle = if (isDe) "Gedankengang" else "Reasoning"
            val combinedThoughts = collectedThoughts.joinToString("\n\n---\n\n")
            executedTools.add(0, thinkTitle)
            toolRecords.add(
                0,
                ToolExecutionRecord(
                    stepIndex = 0,
                    toolName = "thinking",
                    displayTitle = thinkTitle,
                    whatItDid = if (isDe) {
                        "Mehrstufige Überlegung & Planung des Modells (${collectedThoughts.size} ${if (collectedThoughts.size == 1) "Schritt" else "Schritte"})"
                    } else {
                        "Model multi-step reasoning & planning (${collectedThoughts.size} ${if (collectedThoughts.size == 1) "step" else "steps"})"
                    },
                    resultSummary = combinedThoughts,
                    rawArgumentsJson = "",
                    rawResultJson = combinedThoughts,
                    durationMs = totalThinkingDurationMs,
                    category = "THINKING",
                    status = "DONE"
                )
            )
        }
    }

    /**
     * Parses server-executed built-in MCP / tool traces returned directly in OpenAI-compatible
     * responses (e.g. Groq `message.executed_tools`, vLLM/LiteLLM/LM Studio `mcp_calls` / `server_tool_use`,
     * OpenRouter/Perplexity `citations` & `annotations`).
     */
    private fun extractServerSideExecutedTools(
        responseJson: JSONObject,
        choiceObj: JSONObject,
        messageObj: JSONObject,
        startingStepIndex: Int,
        defaultDurationMs: Long,
        isDe: Boolean
    ): List<ToolExecutionRecord> {
        val records = mutableListOf<ToolExecutionRecord>()
        var nextIdx = startingStepIndex

        val candidateArrays = listOfNotNull(
            messageObj.optJSONArray("executed_tools"),
            messageObj.optJSONArray("mcp_calls"),
            messageObj.optJSONArray("mcp_tool_calls"),
            messageObj.optJSONArray("server_tool_use"),
            choiceObj.optJSONArray("executed_tools"),
            choiceObj.optJSONArray("mcp_calls"),
            responseJson.optJSONArray("executed_tools")
        )

        for (arr in candidateArrays) {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val rawName = item.optString("name", "")
                    .ifBlank { item.optString("tool_name", "") }
                    .ifBlank { item.optString("type", "api_mcp_tool") }
                val serverLabel = item.optString("server_label", "")
                    .ifBlank { item.optString("server_name", "API MCP") }
                val rawArgs = item.optString("arguments", "")
                    .ifBlank { item.optJSONObject("arguments")?.toString() ?: "" }
                    .ifBlank { item.optJSONObject("input")?.toString() ?: "{}" }
                val rawOut = item.optString("output", "")
                    .ifBlank { item.optString("result", "") }
                    .ifBlank { item.optJSONObject("output")?.toString() ?: "" }
                    .ifBlank { item.toString() }

                val title = if (serverLabel.isNotBlank() && serverLabel != "API MCP") {
                    "$serverLabel: $rawName"
                } else {
                    "API MCP: $rawName"
                }
                records.add(
                    ToolExecutionRecord(
                        stepIndex = nextIdx++,
                        toolName = "api_mcp_$rawName",
                        displayTitle = title,
                        whatItDid = if (isDe) {
                            "Vom API-Server integriertes MCP-Tool '$rawName' ausgeführt" +
                                if (rawArgs.isNotBlank() && rawArgs != "{}") " ($rawArgs)" else ""
                        } else {
                            "Executed API built-in MCP tool '$rawName'" +
                                if (rawArgs.isNotBlank() && rawArgs != "{}") " ($rawArgs)" else ""
                        },
                        resultSummary = rawOut.take(1500),
                        rawArgumentsJson = rawArgs,
                        rawResultJson = rawOut,
                        durationMs = item.optLong("duration_ms", defaultDurationMs),
                        category = "API_MCP",
                        status = "DONE"
                    )
                )
            }
        }

        // Also check OpenRouter / Perplexity / OpenAI web search annotations & citations
        val citationsArr = responseJson.optJSONArray("citations") ?: messageObj.optJSONArray("citations")
        val annotationsArr = messageObj.optJSONArray("annotations")
        if ((citationsArr != null && citationsArr.length() > 0) || (annotationsArr != null && annotationsArr.length() > 0)) {
            val urls = mutableListOf<String>()
            if (citationsArr != null) {
                for (i in 0 until citationsArr.length()) {
                    val u = citationsArr.optString(i, "").trim()
                    if (u.isNotBlank()) urls.add(u)
                }
            }
            if (annotationsArr != null) {
                for (i in 0 until annotationsArr.length()) {
                    val ann = annotationsArr.optJSONObject(i) ?: continue
                    val urlCit = ann.optJSONObject("url_citation") ?: ann
                    val u = urlCit.optString("url", "").trim()
                    val t = urlCit.optString("title", "").trim()
                    if (u.isNotBlank()) {
                        urls.add(if (t.isNotBlank()) "$t — $u" else u)
                    }
                }
            }
            if (urls.isNotEmpty()) {
                records.add(
                    ToolExecutionRecord(
                        stepIndex = nextIdx,
                        toolName = "api_mcp_web_grounding",
                        displayTitle = if (isDe) "API Web-Quellen (${urls.size})" else "API Web Grounding (${urls.size})",
                        whatItDid = if (isDe) {
                            "Modell-integrierte Websuche / API-Grounding verwendet"
                        } else {
                            "Used model's built-in API web search / grounding"
                        },
                        resultSummary = urls.distinct().mapIndexed { idx, s -> "${idx + 1}. $s" }.joinToString("\n"),
                        rawArgumentsJson = "",
                        rawResultJson = urls.joinToString("\n"),
                        durationMs = defaultDurationMs,
                        category = "API_MCP",
                        status = "DONE"
                    )
                )
            }
        }

        return records
    }

    private fun resolveToolCategory(fnName: String, isServerPrePopulated: Boolean): String {
        if (isServerPrePopulated) return "API_MCP"
        val matchedMcp = LunaMcpClient.findMatchingMcpTool(fnName)
        if (matchedMcp != null) {
            return if (matchedMcp.isApiBuiltIn) "API_MCP" else "MCP"
        }
        return ToolExecutionRecord.inferCategory(fnName)
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

    /**
     * Lightweight language detector so Auto-Language mode always matches the language of the user's
     * question (or the assistant's reply for TTS), regardless of the phone's UI locale or TTS model.
     */
    fun detectLanguageOfText(text: String, fallback: String = "en"): String {
        val sample = text.trim().lowercase(Locale.ROOT)
        if (sample.isEmpty()) return fallback

        val hasGermanChars = sample.any { it == 'ä' || it == 'ö' || it == 'ü' || it == 'ß' }
        val words = sample.split(Regex("[^a-zA-ZäöüßÄÖÜ]+")).filter { it.isNotEmpty() }

        val germanStopWords = setOf(
            "ich", "du", "er", "sie", "es", "wir", "ihr", "und", "oder", "aber", "nicht", "kein",
            "keine", "ist", "sind", "war", "wie", "was", "wo", "wann", "warum", "wer", "welche",
            "bitte", "danke", "mach", "mache", "öffne", "schalte", "stelle", "suche", "zeig",
            "zeige", "mir", "mich", "dir", "dich", "auf", "für", "mit", "von", "nach", "über",
            "heute", "morgen", "wetter", "uhr", "taschenlampe", "lautstärke", "akku", "wecker"
        )
        val englishStopWords = setOf(
            "i", "you", "he", "she", "it", "we", "they", "and", "or", "but", "not", "is", "are",
            "was", "were", "how", "what", "where", "when", "why", "who", "which", "please",
            "thanks", "turn", "open", "set", "search", "show", "tell", "me", "my", "your", "the",
            "for", "with", "from", "about", "today", "weather", "time", "flashlight", "volume",
            "battery", "alarm", "can", "could", "would", "should"
        )

        var deScore = if (hasGermanChars) 2 else 0
        var enScore = 0
        for (w in words) {
            if (germanStopWords.contains(w)) deScore++
            if (englishStopWords.contains(w)) enScore++
        }

        return when {
            deScore > enScore -> "de"
            enScore > deScore -> "en"
            else -> fallback
        }
    }

    private fun buildSystemPrompt(
        settings: LunaSettings,
        memoriesList: List<String>,
        userPrompt: String,
        detectedLanguage: String?,
        discoveredMcpTools: List<McpRemoteTool>
    ): String {
        val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm z (EEEE)", Locale.getDefault()).format(Date())
        val promptLang = detectLanguageOfText(
            text = userPrompt,
            fallback = detectedLanguage ?: settings.appLanguage.resolveLanguageCode()
        )

        val langHint = when (settings.appLanguage) {
            AppLanguage.AUTO -> """
                ### LANGUAGE RULE (CRITICAL — AUTO LANGUAGE MODE):
                - ALWAYS reply in the EXACT SAME LANGUAGE that the user used in their latest question/message (`"$userPrompt"`), regardless of the device's system language or what TTS voice output is configured!
                - If the user asks in English, reply 100% in English. If the user asks in German, reply 100% in German. If the user asks in Spanish, French, or any other language, reply in that exact language.
                - Only if the user's message is a language-neutral word (like an app name alone), fall back to ${if (promptLang == "de") "German (Deutsch)" else "English"}.
            """.trimIndent()
            AppLanguage.GERMAN ->
                "Preferred language: German (Deutsch). Respond in natural, fluent German unless the user explicitly asks you to speak another language."
            AppLanguage.ENGLISH ->
                "Preferred language: English. Respond in natural, fluent English unless the user explicitly asks you to speak another language."
        }

        val mcpToolsBlock = if (discoveredMcpTools.isNotEmpty()) {
            val formattedMcp = discoveredMcpTools.joinToString("\n") { t ->
                "- `${t.toolName}` (${if (t.isApiBuiltIn) "API Built-In MCP" else "MCP: ${t.serverName}"}): ${t.description}"
            }
            "\n\n### Active API & MCP Tools Available in This Session:\n$formattedMcp"
        } else {
            ""
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
            You are Luna, a warm, perceptive, and fast native Android voice assistant triggered from the phone's side button bubble.
            Current local time on phone: $nowStr.
            
            $langHint
            
            ### NATURAL SPOKEN VOICE PERSONA (ASSISTANT BUBBLE MODE):
            - Your words are spoken aloud in real time by a neural Text-to-Speech (TTS) engine while the user listens via the side-notch assistant bubble.
            - Speak like an intelligent, natural, friendly human conversation partner — never like a textbook, robot, or search engine readout.
            - Use natural spoken rhythm, smooth sentence transitions, and conversational phrasing (use natural contractions like "I've", "it's", "that's" in English, or natural everyday spoken Deutsch in German).
            - Never start with stiff robotic preambles like "According to my web search", "Based on the provided results", "Here is a summary", or "As an AI". Jump straight into the answer naturally.
            - Write ONLY in smooth spoken flow text (Fließtext / full spoken sentences).
            - NEVER output Markdown tables, bullet points, numbered lists, headers (#), asterisks (*), code blocks, raw URLs, or bracketed citations ([1]).
            - Format numbers, currencies, units, percentages, and times so they sound effortless when read aloud by TTS.
            - Keep internal reasoning strictly inside <think>...</think> and put only the final spoken flow-text answer outside.
            
            ### BALANCED TOOL USAGE & SPEED-FIRST RESEARCH STRATEGY:
            You have access to Android Device Actions (`set_flashlight`, `open_app`, `open_url_or_deep_link`, `set_volume`, `set_alarm_or_timer`, `get_device_status`, `open_system_settings`, `control_media`, `clipboard_action`), Web Tools (`search_duckduckgo`, `read_website`, `deep_research`), Memory (`save_memory`, `recall_memories`, `forget_memory`), and any exposed API / MCP tools (such as web search engines, `context7` documentation lookup, or custom integrations).
            
            Follow these core principles:
            1. Prefer Speed by Default, with Light Grounding When Needed:
               - Because you are a real-time speech assistant, ALWAYS prefer speed unless the user asks for a detailed/deep search.
               - For device commands (flashlight, volume, opening apps, alarms), execute them immediately in 1 step and confirm in 1 short sentence.
               - For normal factual, news, or live-info questions, do a quick, lightweight lookup first (e.g., a single `search_duckduckgo` query or an appropriate API/MCP tool, and only read 1 website with `read_website` if the search snippets don't already contain the answer). Do NOT run `deep_research` for normal everyday questions!
            2. Balanced Use of All Available Tools (Built-In + API/MCP Tools like Context7 or Search Engines):
               - Use whichever available tool (or combination of tools) best fits the request in a balanced, practical way — do not blindly over-prefer or ignore any tool.
               - For example, if the API or MCP exposes specialized tools (such as `context7` for library/framework/code documentation, or a built-in API search tool), use them when relevant alongside or instead of `search_duckduckgo`, as needed to get accurate information quickly.
            3. Deep Multi-Search & Multi-Website Research Only When Requested:
               - Reserve `deep_research` (or running multiple search queries + pulling multiple websites in parallel) for when the user EXPLICITLY asks for a detailed search, deep research, or a thorough multi-source comparison.
            4. Self-Correction on App Names:
               - If `open_app` returns `installed_apps_sample` because an app name didn't match directly, immediately call `open_app` with the exact matching name from `installed_apps_sample`.
            $mcpToolsBlock$memoryBlock$customExtra
        """.trimIndent()
    }

    private fun buildToolExecutionRecord(
        stepIndex: Int,
        fnName: String,
        args: JSONObject,
        rawArgs: String,
        rawOutput: String,
        durationMs: Long,
        categoryOverride: String,
        isDe: Boolean
    ): ToolExecutionRecord {
        val outJson = runCatching { JSONObject(rawOutput) }.getOrNull()
        val isOk = outJson?.optBoolean("ok", true) ?: !rawOutput.contains("\"error\"")
        val statusStr = if (isOk) "DONE" else "ERROR"

        return when (fnName.trim().lowercase(Locale.ROOT)) {
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
                    durationMs = durationMs,
                    category = "ANDROID",
                    status = statusStr
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
                    durationMs = durationMs,
                    category = "ANDROID",
                    status = statusStr
                )
            }
            "open_url_or_deep_link" -> {
                val target = args.optString("target", "")
                val type = args.optString("type", "url")
                val uriStr = outJson?.optString("opened_uri", target).orEmpty()
                ToolExecutionRecord(
                    stepIndex = stepIndex,
                    toolName = fnName,
                    displayTitle = if (isDe) "Öffne Link ($type)" else "Open Link ($type)",
                    whatItDid = if (isDe) "Deep-Link / URL auf dem Gerät geöffnet: $target" else "Opened deep link / URL on device: $target",
                    resultSummary = if (isOk) uriStr else (outJson?.optString("error", rawOutput) ?: rawOutput),
                    rawArgumentsJson = rawArgs,
                    rawResultJson = rawOutput,
                    durationMs = durationMs,
                    category = "ANDROID",
                    status = statusStr
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
                    durationMs = durationMs,
                    category = "ANDROID",
                    status = statusStr
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
                        "Timer"
                    } else {
                        if (isDe) "Wecker" else "Alarm"
                    },
                    whatItDid = summary,
                    resultSummary = if (isOk) summary else (outJson?.optString("error", rawOutput) ?: rawOutput),
                    rawArgumentsJson = rawArgs,
                    rawResultJson = rawOutput,
                    durationMs = durationMs,
                    category = "ANDROID",
                    status = statusStr
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
                    durationMs = durationMs,
                    category = "ANDROID",
                    status = statusStr
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
                    durationMs = durationMs,
                    category = "ANDROID",
                    status = statusStr
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
                    durationMs = durationMs,
                    category = "ANDROID",
                    status = statusStr
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
                    durationMs = durationMs,
                    category = "ANDROID",
                    status = statusStr
                )
            }
            "search_duckduckgo", "deep_research" -> {
                val queriesArr = outJson?.optJSONArray("queries") ?: args.optJSONArray("queries")
                val queriesList = mutableListOf<String>()
                if (queriesArr != null) {
                    for (i in 0 until queriesArr.length()) {
                        val qItem = queriesArr.optString(i, "").trim()
                        if (qItem.isNotBlank()) queriesList.add(qItem)
                    }
                }
                val primaryQ = args.optString("query", "").ifBlank { outJson?.optString("query", "").orEmpty() }
                if (queriesList.isEmpty() && primaryQ.isNotBlank()) {
                    queriesList.add(primaryQ)
                }
                val qLabel = queriesList.joinToString(", ")
                val pagesReadCount = outJson?.optInt("pages_read_count", 0) ?: 0
                val isDeep = fnName.equals("deep_research", ignoreCase = true) || queriesList.size > 1 || pagesReadCount > 0

                val instant = outJson?.optString("instant_answer", "").orEmpty()
                val arr = outJson?.optJSONArray("results")
                val formattedResults = buildString {
                    if (instant.isNotBlank()) {
                        append(if (isDe) "Direktantwort: " else "Instant Answer: ")
                        append(instant)
                        append("\n\n")
                    }
                    if (pagesReadCount > 0) {
                        append(
                            if (isDe) "Volltext von $pagesReadCount Webseiten parallel extrahiert.\n\n"
                            else "Extracted full text from $pagesReadCount websites in parallel.\n\n"
                        )
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

                val titleText = if (isDeep) {
                    if (isDe) {
                        "Tiefensuche (${queriesList.size} Suchen" + (if (pagesReadCount > 0) ", $pagesReadCount Seiten)" else ")")
                    } else {
                        "Deep Search (${queriesList.size} queries" + (if (pagesReadCount > 0) ", $pagesReadCount sites)" else ")")
                    }
                } else {
                    if (isDe) "Suche: ${qLabel.take(24)}" else "Search: ${qLabel.take(24)}"
                }

                val didText = if (isDeep) {
                    if (isDe) {
                        "Mehrstufige DuckDuckGo-Suche (${queriesList.joinToString(" | ")})" +
                            if (pagesReadCount > 0) " + $pagesReadCount Webseiten gelesen" else ""
                    } else {
                        "Multi-query DuckDuckGo research (${queriesList.joinToString(" | ")})" +
                            if (pagesReadCount > 0) " + pulled $pagesReadCount websites" else ""
                    }
                } else {
                    if (isDe) "DuckDuckGo-Websuche nach \"$qLabel\"" else "Searched DuckDuckGo for \"$qLabel\""
                }

                ToolExecutionRecord(
                    stepIndex = stepIndex,
                    toolName = fnName,
                    displayTitle = titleText,
                    whatItDid = didText,
                    resultSummary = formattedResults,
                    rawArgumentsJson = rawArgs,
                    rawResultJson = rawOutput,
                    durationMs = durationMs,
                    category = "WEB",
                    status = statusStr
                )
            }
            "read_website" -> {
                val pagesArr = outJson?.optJSONArray("pages")
                val pagesCount = pagesArr?.length() ?: 0
                val u = args.optString("url", "")
                val title = outJson?.optString("title", u).orEmpty()
                val content = outJson?.optString("content", "").orEmpty().take(1200)
                ToolExecutionRecord(
                    stepIndex = stepIndex,
                    toolName = fnName,
                    displayTitle = if (pagesCount > 1) {
                        if (isDe) "$pagesCount Webseiten gelesen" else "Read $pagesCount Websites"
                    } else {
                        if (isDe) "Webseite gelesen" else "Read Website"
                    },
                    whatItDid = if (pagesCount > 1) {
                        if (isDe) "$pagesCount Webseiten parallel abgerufen und Volltext extrahiert" else "Fetched and extracted readable text from $pagesCount websites in parallel"
                    } else {
                        if (isDe) "Webseite abgerufen und Text extrahiert: $u" else "Fetched and extracted readable text from: $u"
                    },
                    resultSummary = buildString {
                        if (title.isNotBlank()) append("$title\n\n")
                        append(content.ifBlank { rawOutput.take(800) })
                    }.trim(),
                    rawArgumentsJson = rawArgs,
                    rawResultJson = rawOutput,
                    durationMs = durationMs,
                    category = "WEB",
                    status = statusStr
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
                    durationMs = durationMs,
                    category = "MEMORY",
                    status = statusStr
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
                    durationMs = durationMs,
                    category = "MEMORY",
                    status = statusStr
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
                    durationMs = durationMs,
                    category = "MEMORY",
                    status = statusStr
                )
            }
            else -> {
                val serverName = outJson?.optString("mcp_server", "").orEmpty()
                val toolShort = outJson?.optString("tool", "").orEmpty()
                    .ifBlank { fnName.removePrefix("mcp_api_").removePrefix("mcp_") }
                val mcpOutput = outJson?.optString("output", "").orEmpty()
                    .ifBlank { outJson?.optString("error", "").orEmpty() }
                    .ifBlank { rawOutput }

                val prefixBadge = when {
                    categoryOverride == "API_MCP" -> "API MCP"
                    serverName.isNotBlank() -> serverName
                    else -> "MCP"
                }
                ToolExecutionRecord(
                    stepIndex = stepIndex,
                    toolName = fnName,
                    displayTitle = "$prefixBadge: $toolShort",
                    whatItDid = if (isDe) {
                        "MCP-Werkzeug '$toolShort' über $prefixBadge ausgeführt" +
                            if (rawArgs.isNotBlank() && rawArgs != "{}") " mit $rawArgs" else ""
                    } else {
                        "Executed MCP tool '$toolShort' via $prefixBadge" +
                            if (rawArgs.isNotBlank() && rawArgs != "{}") " with $rawArgs" else ""
                    },
                    resultSummary = mcpOutput.take(1500),
                    rawArgumentsJson = rawArgs,
                    rawResultJson = rawOutput,
                    durationMs = durationMs,
                    category = categoryOverride,
                    status = statusStr
                )
            }
        }
    }

    private fun formatToolDisplayLabel(fnName: String, args: JSONObject, isDe: Boolean): String {
        return when (fnName.trim().lowercase(Locale.ROOT)) {
            "set_flashlight" -> {
                val on = args.optBoolean("enabled", true)
                if (isDe) "Taschenlampe ${if (on) "EIN" else "AUS"}" else "Flashlight ${if (on) "ON" else "OFF"}"
            }
            "open_app" -> {
                val app = args.optString("app_name", "App")
                if (isDe) "Öffne $app..." else "Opening $app..."
            }
            "open_url_or_deep_link" -> {
                val target = args.optString("target", "Link")
                if (isDe) "Öffne $target..." else "Opening $target..."
            }
            "set_volume" -> {
                val pct = args.optInt("percent", 50)
                if (isDe) "Lautstärke auf $pct%..." else "Setting volume to $pct%..."
            }
            "set_alarm_or_timer" -> {
                if (isDe) "Stelle Wecker / Timer..." else "Setting alarm / timer..."
            }
            "get_device_status" -> {
                if (isDe) "Prüfe Gerätestatus..." else "Checking device status..."
            }
            "search_duckduckgo" -> {
                val queriesArr = args.optJSONArray("queries")
                val q = args.optString("query", "")
                if (queriesArr != null && queriesArr.length() > 1) {
                    if (isDe) "Tiefensuche (${queriesArr.length()} Anfragen)..." else "Deep search (${queriesArr.length()} queries)..."
                } else {
                    if (isDe) "DuckDuckGo-Suche: \"$q\"" else "Searching DuckDuckGo: \"$q\""
                }
            }
            "deep_research" -> {
                val queriesArr = args.optJSONArray("queries")
                val qCount = queriesArr?.length() ?: 2
                if (isDe) "Tiefenrecherche ($qCount Suchen & Webseiten)..." else "Deep research ($qCount searches & sites)..."
            }
            "read_website" -> {
                val urlsArr = args.optJSONArray("urls")
                val u = args.optString("url", "")
                if (urlsArr != null && urlsArr.length() > 1) {
                    if (isDe) "Lese ${urlsArr.length()} Webseiten parallel..." else "Reading ${urlsArr.length()} websites in parallel..."
                } else {
                    if (isDe) "Lese Webseite: $u" else "Reading website: $u"
                }
            }
            "save_memory" -> {
                val k = args.optString("key", "")
                if (isDe) "Speichere Erinnerung ($k)..." else "Saving memory ($k)..."
            }
            "recall_memories" -> {
                if (isDe) "Rufe Erinnerungen ab..." else "Recalling memories..."
            }
            else -> if (fnName.startsWith("mcp_")) {
                "MCP: ${fnName.removePrefix("mcp_api_").removePrefix("mcp_")}"
            } else {
                "MCP: $fnName"
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
