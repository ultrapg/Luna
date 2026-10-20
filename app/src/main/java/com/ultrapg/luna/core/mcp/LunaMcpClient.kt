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
    val toolName: String,
    val qualifiedName: String,
    val description: String,
    val inputSchema: JSONObject
)

/**
 * Model Context Protocol (MCP) Client for Luna.
 *
 * Supports MCP Streamable HTTP JSON-RPC (`tools/list`, `tools/call`, `initialize`)
 * so users can connect external MCP servers for grounding, home automation, search,
 * or custom quick-ask tools.
 */
object LunaMcpClient {
    private const val TAG = "LunaMcpClient"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val requestIdCounter = AtomicInteger(1)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val cachedServerTools = ConcurrentHashMap<String, List<McpRemoteTool>>()

    suspend fun discoverAllTools(servers: List<McpServerConfig>): List<McpRemoteTool> = withContext(Dispatchers.IO) {
        val enabledServers = servers.filter { it.enabled && it.url.isNotBlank() }
        val result = mutableListOf<McpRemoteTool>()
        for (srv in enabledServers) {
            val tools = runCatching { fetchToolsForServer(srv) }.getOrElse {
                cachedServerTools[srv.id].orEmpty()
            }
            cachedServerTools[srv.id] = tools
            result.addAll(tools)
        }
        result
    }

    suspend fun fetchToolsForServer(server: McpServerConfig): List<McpRemoteTool> = withContext(Dispatchers.IO) {
        // Optional MCP initialize handshake
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
                        .put("clientInfo", JSONObject().put("name", "Luna-Android").put("version", "0.1.0"))
                )
            postJsonRpc(server, initPayload)
        }

        val listPayload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", requestIdCounter.getAndIncrement())
            .put("method", "tools/list")
            .put("params", JSONObject())

        val respJson = postJsonRpc(server, listPayload)
        val toolsArr = respJson.optJSONObject("result")?.optJSONArray("tools")
            ?: respJson.optJSONArray("tools")
            ?: JSONArray()

        val list = mutableListOf<McpRemoteTool>()
        for (i in 0 until toolsArr.length()) {
            val obj = toolsArr.optJSONObject(i) ?: continue
            val rawName = obj.optString("name", "").trim()
            if (rawName.isEmpty()) continue
            val desc = obj.optString("description", "MCP tool from ${server.name}")
            val schema = obj.optJSONObject("inputSchema")
                ?: obj.optJSONObject("parameters")
                ?: JSONObject().put("type", "object").put("properties", JSONObject())

            val safeServerPrefix = server.id.replace(Regex("[^a-zA-Z0-9_]"), "_")
            val safeToolName = rawName.replace(Regex("[^a-zA-Z0-9_]"), "_")
            val qualified = "mcp_${safeServerPrefix}_$safeToolName"

            list.add(
                McpRemoteTool(
                    serverId = server.id,
                    serverName = server.name,
                    toolName = rawName,
                    qualifiedName = qualified,
                    description = "[MCP: ${server.name}] $desc",
                    inputSchema = schema
                )
            )
        }
        cachedServerTools[server.id] = list
        list
    }

    suspend fun callMcpTool(
        servers: List<McpServerConfig>,
        qualifiedToolName: String,
        arguments: JSONObject
    ): String = withContext(Dispatchers.IO) {
        val allCached = cachedServerTools.values.flatten()
        val matched = allCached.firstOrNull { it.qualifiedName == qualifiedToolName }
            ?: discoverAllTools(servers).firstOrNull { it.qualifiedName == qualifiedToolName }
            ?: return@withContext JSONObject().put("error", "MCP tool not found: $qualifiedToolName").toString()

        val server = servers.firstOrNull { it.id == matched.serverId }
            ?: return@withContext JSONObject().put("error", "MCP server not found: ${matched.serverId}").toString()

        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", requestIdCounter.getAndIncrement())
            .put("method", "tools/call")
            .put(
                "params",
                JSONObject()
                    .put("name", matched.toolName)
                    .put("arguments", arguments)
            )

        return@withContext try {
            val resp = postJsonRpc(server, payload)
            val resultObj = resp.optJSONObject("result")
            if (resultObj != null) {
                val contentArr = resultObj.optJSONArray("content")
                if (contentArr != null && contentArr.length() > 0) {
                    val texts = mutableListOf<String>()
                    for (i in 0 until contentArr.length()) {
                        val item = contentArr.optJSONObject(i)
                        val t = item?.optString("text", "")
                        if (!t.isNullOrBlank()) texts.add(t)
                    }
                    if (texts.isNotEmpty()) {
                        return@withContext JSONObject()
                            .put("ok", true)
                            .put("mcp_server", server.name)
                            .put("tool", matched.toolName)
                            .put("output", texts.joinToString("\n"))
                            .toString()
                    }
                }
                resultObj.toString()
            } else {
                resp.toString()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "MCP tool call failed: ${t.message}")
            JSONObject().put("error", "MCP call error: ${t.message}").toString()
        }
    }

    fun appendMcpToolsToOpenAiArray(toolsArray: JSONArray, mcpTools: List<McpRemoteTool>) {
        for (tool in mcpTools) {
            val fn = JSONObject()
                .put("name", tool.qualifiedName)
                .put("description", tool.description)
                .put("parameters", tool.inputSchema)
            toolsArray.put(
                JSONObject()
                    .put("type", "function")
                    .put("function", fn)
            )
        }
    }

    private fun postJsonRpc(server: McpServerConfig, payload: JSONObject): JSONObject {
        val url = server.url.trim()
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(jsonMediaType))

        if (server.apiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${server.apiKey.trim()}")
        }

        httpClient.newCall(reqBuilder.build()).execute().use { response ->
            val rawBody = response.body?.string().orEmpty().trim()
            if (rawBody.startsWith("data:")) {
                // Parse SSE event stream payload if the MCP server responds with text/event-stream
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
