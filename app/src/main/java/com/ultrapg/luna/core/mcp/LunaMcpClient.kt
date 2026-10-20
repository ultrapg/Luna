/*
 * Luna — Native Android Digital Assistant
 * Copyright (C) 2026 ultrapg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.ultrapg.luna.core.mcp

import android.net.Uri
import android.util.Log
import com.ultrapg.luna.core.settings.McpServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class McpRemoteTool(
    val serverId: String,
    val serverName: String,
    val endpointUrl: String,
    val apiKey: String,
    val toolName: String,
    val qualifiedName: String,
    val description: String,
    val inputSchema: JSONObject,
    val isApiBuiltIn: Boolean = false
)

/**
 * Model Context Protocol (MCP) Client for Luna.
 *
 * Supports:
 * 1. User-configured external MCP servers via Streamable HTTP JSON-RPC (`tools/list`, `tools/call`, `initialize`).
 * 2. API Built-In MCP discovery & execution for OpenAI-compatible gateways/providers that expose
 *    server-hosted MCP endpoints (`<baseUrl>/mcp`, `<origin>/mcp`, `<baseUrl>/mcp/tools`) or let models
 *    invoke MCP tools by either their qualified (`mcp_<srv>_<tool>`) or native unprefixed (`<tool>`) name.
 */
object LunaMcpClient {
    private const val TAG = "LunaMcpClient"
    private const val API_BUILTIN_SERVER_ID = "api_builtin"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val requestIdCounter = AtomicInteger(1)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // Fast probe client so checking whether an API endpoint hosts built-in MCPs never slows down voice turns
    private val fastProbeClient = OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(3500, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()

    private val cachedServerTools = ConcurrentHashMap<String, List<McpRemoteTool>>()
    private val cachedApiBuiltInByBaseUrl = ConcurrentHashMap<String, Pair<Long, List<McpRemoteTool>>>()

    suspend fun discoverAllTools(
        servers: List<McpServerConfig>,
        apiBaseUrl: String = "",
        apiKey: String = "",
        enableApiBuiltInMcp: Boolean = true
    ): List<McpRemoteTool> = withContext(Dispatchers.IO) {
        val result = mutableListOf<McpRemoteTool>()

        if (enableApiBuiltInMcp && apiBaseUrl.isNotBlank()) {
            val apiTools = runCatching { discoverApiBuiltInMcpTools(apiBaseUrl, apiKey) }.getOrDefault(emptyList())
            if (apiTools.isNotEmpty()) {
                cachedServerTools[API_BUILTIN_SERVER_ID] = apiTools
                result.addAll(apiTools)
            }
        }

        val enabledServers = servers.filter { it.enabled && it.url.isNotBlank() }
        for (srv in enabledServers) {
            val tools = runCatching { fetchToolsForServer(srv) }.getOrElse {
                cachedServerTools[srv.id].orEmpty()
            }
            cachedServerTools[srv.id] = tools
            result.addAll(tools)
        }
        result
    }

    /**
     * Probes the active OpenAI-compatible API endpoint for built-in MCP servers/tools
     * (`<baseUrl>/mcp`, `<origin>/mcp`, `<baseUrl>/mcp/tools`). Results are cached for 5 minutes per baseUrl.
     */
    suspend fun discoverApiBuiltInMcpTools(
        rawBaseUrl: String,
        apiKey: String
    ): List<McpRemoteTool> = withContext(Dispatchers.IO) {
        val cleanBase = rawBaseUrl.trim().trimEnd('/').removeSuffix("/chat/completions").trimEnd('/')
        if (cleanBase.isBlank()) return@withContext emptyList()

        val now = System.currentTimeMillis()
        val cached = cachedApiBuiltInByBaseUrl[cleanBase]
        if (cached != null && (now - cached.first) < 300_000L) {
            return@withContext cached.second
        }

        val candidateEndpoints = buildList {
            add("$cleanBase/mcp")
            runCatching {
                val uri = Uri.parse(cleanBase)
                val scheme = uri.scheme
                val authority = uri.authority
                if (!scheme.isNullOrBlank() && !authority.isNullOrBlank()) {
                    val rootMcp = "$scheme://$authority/mcp"
                    if (rootMcp != "$cleanBase/mcp") add(rootMcp)
                }
            }
        }

        // 1. Try standard MCP JSON-RPC `tools/list` on candidate endpoints
        for (endpoint in candidateEndpoints) {
            val serverCfg = McpServerConfig(
                id = API_BUILTIN_SERVER_ID,
                name = "API Built-In MCP",
                url = endpoint,
                apiKey = apiKey,
                enabled = true
            )
            val discovered = runCatching {
                fetchToolsForServerInternal(
                    server = serverCfg,
                    client = fastProbeClient,
                    isApiBuiltIn = true
                )
            }.getOrDefault(emptyList())

            if (discovered.isNotEmpty()) {
                cachedApiBuiltInByBaseUrl[cleanBase] = now to discovered
                cachedServerTools[API_BUILTIN_SERVER_ID] = discovered
                return@withContext discovered
            }
        }

        // 2. Try REST `GET <cleanBase>/mcp/tools` or `GET <cleanBase>/tools` exposed by some gateways
        for (restUrl in listOf("$cleanBase/mcp/tools", "$cleanBase/tools")) {
            val discovered = runCatching {
                val reqBuilder = Request.Builder()
                    .url(restUrl)
                    .header("Accept", "application/json")
                    .get()
                if (apiKey.isNotBlank()) {
                    reqBuilder.header("Authorization", "Bearer ${apiKey.trim()}")
                }
                fastProbeClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList<McpRemoteTool>()
                    val body = resp.body?.string().orEmpty().trim()
                    if (!body.startsWith("{") && !body.startsWith("[")) return@use emptyList<McpRemoteTool>()
                    val toolsArr = if (body.startsWith("[")) {
                        JSONArray(body)
                    } else {
                        val json = JSONObject(body)
                        json.optJSONArray("tools") ?: json.optJSONArray("data") ?: JSONArray()
                    }
                    parseMcpToolsArray(
                        toolsArr = toolsArr,
                        serverId = API_BUILTIN_SERVER_ID,
                        serverName = "API Built-In MCP",
                        endpointUrl = "$cleanBase/mcp",
                        apiKey = apiKey,
                        isApiBuiltIn = true
                    )
                }
            }.getOrDefault(emptyList())

            if (discovered.isNotEmpty()) {
                cachedApiBuiltInByBaseUrl[cleanBase] = now to discovered
                cachedServerTools[API_BUILTIN_SERVER_ID] = discovered
                return@withContext discovered
            }
        }

        cachedApiBuiltInByBaseUrl[cleanBase] = now to emptyList()
        emptyList()
    }

    suspend fun fetchToolsForServer(server: McpServerConfig): List<McpRemoteTool> = withContext(Dispatchers.IO) {
        val list = fetchToolsForServerInternal(
            server = server,
            client = httpClient,
            isApiBuiltIn = false
        )
        cachedServerTools[server.id] = list
        list
    }

    private fun fetchToolsForServerInternal(
        server: McpServerConfig,
        client: OkHttpClient,
        isApiBuiltIn: Boolean
    ): List<McpRemoteTool> {
        runCatching {
            val initPayload = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", requestIdCounter.getAndIncrement())
                .put("method", "initialize")
                .put(
                    "params",
                    JSONObject()
                        .put("protocolVersion", "2024-11-05")
                        .put("capabilities", JSONObject())
                        .put("clientInfo", JSONObject().put("name", "Luna-Android").put("version", "0.6.0"))
                )
            postJsonRpc(server.url, server.apiKey, initPayload, client)
        }

        val listPayload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", requestIdCounter.getAndIncrement())
            .put("method", "tools/list")
            .put("params", JSONObject())

        val respJson = postJsonRpc(server.url, server.apiKey, listPayload, client)
        val toolsArr = respJson.optJSONObject("result")?.optJSONArray("tools")
            ?: respJson.optJSONArray("tools")
            ?: JSONArray()

        return parseMcpToolsArray(
            toolsArr = toolsArr,
            serverId = server.id,
            serverName = server.name,
            endpointUrl = server.url,
            apiKey = server.apiKey,
            isApiBuiltIn = isApiBuiltIn
        )
    }

    private fun parseMcpToolsArray(
        toolsArr: JSONArray,
        serverId: String,
        serverName: String,
        endpointUrl: String,
        apiKey: String,
        isApiBuiltIn: Boolean
    ): List<McpRemoteTool> {
        val list = mutableListOf<McpRemoteTool>()
        for (i in 0 until toolsArr.length()) {
            val obj = toolsArr.optJSONObject(i) ?: continue
            val fnObj = obj.optJSONObject("function") ?: obj
            val rawName = fnObj.optString("name", "").trim()
            if (rawName.isEmpty()) continue
            val desc = fnObj.optString("description", "MCP tool from $serverName")
            val schema = fnObj.optJSONObject("inputSchema")
                ?: fnObj.optJSONObject("parameters")
                ?: JSONObject().put("type", "object").put("properties", JSONObject())

            val safeServerPrefix = serverId.replace(Regex("[^a-zA-Z0-9_]"), "_")
            val safeToolName = rawName.replace(Regex("[^a-zA-Z0-9_]"), "_")
            val qualified = if (isApiBuiltIn) {
                "mcp_api_$safeToolName"
            } else {
                "mcp_${safeServerPrefix}_$safeToolName"
            }

            list.add(
                McpRemoteTool(
                    serverId = serverId,
                    serverName = serverName,
                    endpointUrl = endpointUrl,
                    apiKey = apiKey,
                    toolName = rawName,
                    qualifiedName = qualified,
                    description = if (isApiBuiltIn) "[API MCP] $desc" else "[MCP: $serverName] $desc",
                    inputSchema = schema,
                    isApiBuiltIn = isApiBuiltIn
                )
            )
        }
        return list
    }

    /**
     * Checks whether a tool name invoked by the LLM matches any discovered MCP tool
     * (either by its qualified `mcp_*` name or its raw unprefixed tool name).
     */
    fun findMatchingMcpTool(requestedName: String): McpRemoteTool? {
        val norm = requestedName.trim()
        if (norm.isEmpty()) return null
        val allCached = cachedServerTools.values.flatten()
        return allCached.firstOrNull {
            it.qualifiedName.equals(norm, ignoreCase = true) ||
                it.toolName.equals(norm, ignoreCase = true) ||
                norm.removePrefix("mcp_").equals(it.toolName, ignoreCase = true)
        }
    }

    suspend fun callMcpTool(
        servers: List<McpServerConfig>,
        requestedToolName: String,
        arguments: JSONObject,
        apiBaseUrl: String = "",
        apiKey: String = "",
        enableApiBuiltInMcp: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        val matched = findMatchingMcpTool(requestedToolName)
            ?: run {
                discoverAllTools(servers, apiBaseUrl, apiKey, enableApiBuiltInMcp)
                findMatchingMcpTool(requestedToolName)
            }

        // Fallback: if the model called an uncached `mcp_*` or server tool when `enableApiBuiltInMcp` is active,
        // try invoking `<apiBaseUrl>/mcp` directly with the raw tool name
        val targetEndpoint: String
        val targetApiKey: String
        val targetServerName: String
        val rawToolName: String

        if (matched != null) {
            targetEndpoint = matched.endpointUrl
            targetApiKey = matched.apiKey
            targetServerName = matched.serverName
            rawToolName = matched.toolName
        } else if (enableApiBuiltInMcp && apiBaseUrl.isNotBlank()) {
            val cleanBase = apiBaseUrl.trim().trimEnd('/').removeSuffix("/chat/completions").trimEnd('/')
            targetEndpoint = "$cleanBase/mcp"
            targetApiKey = apiKey
            targetServerName = "API Built-In MCP"
            rawToolName = requestedToolName.removePrefix("mcp_api_").removePrefix("mcp_")
        } else {
            return@withContext JSONObject().put("error", "MCP tool not found: $requestedToolName").toString()
        }

        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", requestIdCounter.getAndIncrement())
            .put("method", "tools/call")
            .put(
                "params",
                JSONObject()
                    .put("name", rawToolName)
                    .put("arguments", arguments)
            )

        return@withContext try {
            val resp = postJsonRpc(targetEndpoint, targetApiKey, payload, httpClient)
            val resultObj = resp.optJSONObject("result")
            if (resultObj != null) {
                val contentArr = resultObj.optJSONArray("content")
                if (contentArr != null && contentArr.length() > 0) {
                    val texts = mutableListOf<String>()
                    for (i in 0 until contentArr.length()) {
                        val item = contentArr.optJSONObject(i)
                        val t = item?.optString("text", "")
                            ?.ifBlank { item.optString("content", "") }
                        if (!t.isNullOrBlank()) texts.add(t)
                    }
                    if (texts.isNotEmpty()) {
                        return@withContext JSONObject()
                            .put("ok", true)
                            .put("mcp_server", targetServerName)
                            .put("tool", rawToolName)
                            .put("output", texts.joinToString("\n"))
                            .toString()
                    }
                }
                JSONObject()
                    .put("ok", true)
                    .put("mcp_server", targetServerName)
                    .put("tool", rawToolName)
                    .put("output", resultObj.toString())
                    .toString()
            } else if (resp.has("error")) {
                val errObj = resp.optJSONObject("error")
                val msg = errObj?.optString("message") ?: resp.optString("error")
                JSONObject()
                    .put("ok", false)
                    .put("mcp_server", targetServerName)
                    .put("tool", rawToolName)
                    .put("error", msg)
                    .toString()
            } else {
                resp.toString()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "MCP tool call failed ($requestedToolName): ${t.message}")
            JSONObject()
                .put("ok", false)
                .put("mcp_server", targetServerName)
                .put("tool", rawToolName)
                .put("error", "MCP call error: ${t.message}")
                .toString()
        }
    }

    fun appendMcpToolsToOpenAiArray(toolsArray: JSONArray, mcpTools: List<McpRemoteTool>) {
        val existingNames = mutableSetOf<String>()
        for (i in 0 until toolsArray.length()) {
            val name = toolsArray.optJSONObject(i)?.optJSONObject("function")?.optString("name", "").orEmpty()
            if (name.isNotBlank()) existingNames.add(name.lowercase())
        }

        for (tool in mcpTools) {
            val exposedName = if (!existingNames.contains(tool.toolName.lowercase())) {
                // Expose with its clean native tool name when there is no collision so models trained
                // on standard MCP tool names can invoke it effortlessly!
                tool.toolName
            } else {
                tool.qualifiedName
            }
            if (existingNames.add(exposedName.lowercase())) {
                val fn = JSONObject()
                    .put("name", exposedName)
                    .put("description", tool.description)
                    .put("parameters", tool.inputSchema)
                toolsArray.put(
                    JSONObject()
                        .put("type", "function")
                        .put("function", fn)
                )
            }
        }
    }

    private fun postJsonRpc(
        url: String,
        apiKey: String,
        payload: JSONObject,
        client: OkHttpClient
    ): JSONObject {
        val cleanUrl = url.trim()
        val reqBuilder = Request.Builder()
            .url(cleanUrl)
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(jsonMediaType))

        if (apiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${apiKey.trim()}")
        }

        client.newCall(reqBuilder.build()).execute().use { response ->
            val rawBody = response.body?.string().orEmpty().trim()
            if (rawBody.startsWith("data:")) {
                val dataLine = rawBody.lines()
                    .firstOrNull { it.startsWith("data:") && it.removePrefix("data:").trim().startsWith("{") }
                    ?.removePrefix("data:")
                    ?.trim()
                if (!dataLine.isNullOrBlank()) {
                    return JSONObject(dataLine)
                }
            }
            return if (rawBody.startsWith("{")) {
                JSONObject(rawBody)
            } else {
                JSONObject().put("raw", rawBody).put("status", response.code)
            }
        }
    }
}
