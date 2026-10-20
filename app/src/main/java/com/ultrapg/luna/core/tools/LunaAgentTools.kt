/*
 * Luna — Native Android Digital Assistant
 * Copyright (C) 2026 ultrapg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.ultrapg.luna.core.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import com.ultrapg.luna.assistant.LunaAssistantController
import com.ultrapg.luna.core.database.LunaDatabase
import com.ultrapg.luna.core.database.MemoryEntity
import com.ultrapg.luna.core.settings.LunaSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class AgentToolSpec(
    val name: String,
    val description: String,
    val parametersSchema: JSONObject
)

object LunaAgentTools {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Volatile
    var isFlashlightOn: Boolean = false
        private set

    private val builtInToolNames = setOf(
        "set_flashlight",
        "open_app",
        "open_url_or_deep_link",
        "set_volume",
        "set_alarm_or_timer",
        "get_device_status",
        "open_system_settings",
        "control_media",
        "clipboard_action",
        "search_duckduckgo",
        "read_website",
        "deep_research",
        "save_memory",
        "recall_memories",
        "forget_memory"
    )

    fun isBuiltInTool(name: String): Boolean {
        return builtInToolNames.contains(name.trim().lowercase(Locale.ROOT))
    }

    // Bilingual (EN/DE) common app aliases mapped to label/package keywords
    private val appSynonymKeywords: Map<String, List<String>> = mapOf(
        "kamera" to listOf("camera", "kamera", "cam", "opencamera", "gcam"),
        "camera" to listOf("camera", "kamera", "cam", "opencamera", "gcam"),
        "einstellungen" to listOf("settings", "einstellungen", "com.android.settings"),
        "settings" to listOf("settings", "einstellungen", "com.android.settings"),
        "uhr" to listOf("clock", "uhr", "deskclock", "alarm"),
        "wecker" to listOf("clock", "uhr", "deskclock", "alarm"),
        "clock" to listOf("clock", "uhr", "deskclock", "alarm"),
        "rechner" to listOf("calculator", "rechner", "taschenrechner", "calc"),
        "taschenrechner" to listOf("calculator", "rechner", "taschenrechner", "calc"),
        "calculator" to listOf("calculator", "rechner", "taschenrechner", "calc"),
        "kalender" to listOf("calendar", "kalender", "etar"),
        "calendar" to listOf("calendar", "kalender", "etar"),
        "telefon" to listOf("phone", "telefon", "dialer", "contacts", "anrufe"),
        "phone" to listOf("phone", "telefon", "dialer", "contacts"),
        "nachrichten" to listOf("messages", "nachrichten", "mms", "sms", "messaging"),
        "messages" to listOf("messages", "nachrichten", "mms", "sms", "messaging"),
        "galerie" to listOf("gallery", "galerie", "photos", "fotos", "aves"),
        "fotos" to listOf("photos", "fotos", "gallery", "galerie"),
        "photos" to listOf("photos", "fotos", "gallery", "galerie"),
        "musik" to listOf("music", "musik", "spotify", "ytmusic", "apple.android.music", "retro"),
        "music" to listOf("music", "musik", "spotify", "ytmusic", "apple.android.music"),
        "browser" to listOf("chrome", "browser", "firefox", "brave", "opera", "vivaldi", "duckduckgo"),
        "karten" to listOf("maps", "karten", "navigation", "osmand", "organicmaps"),
        "maps" to listOf("maps", "karten", "navigation", "osmand", "organicmaps"),
        "dateien" to listOf("files", "dateien", "documentsui", "filemanager"),
        "files" to listOf("files", "dateien", "documentsui", "filemanager"),
        "notizen" to listOf("notes", "notizen", "keep", "obsidian", "notion"),
        "notes" to listOf("notes", "notizen", "keep", "obsidian", "notion"),
        "mail" to listOf("gmail", "mail", "email", "outlook", "thunderbird", "k9"),
        "email" to listOf("gmail", "mail", "email", "outlook", "thunderbird", "k9"),
        "play store" to listOf("play store", "vending", "aurora", "fdroid"),
        "store" to listOf("play store", "vending", "aurora", "fdroid")
    )

    fun buildOpenAiToolSchemas(context: Context): JSONArray {
        val settings = LunaSettingsRepository.current(context)
        val toolsArray = JSONArray()

        fun addTool(name: String, description: String, properties: JSONObject, required: List<String> = emptyList()) {
            val reqArr = JSONArray()
            required.forEach { reqArr.put(it) }
            val params = JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", reqArr)

            val fn = JSONObject()
                .put("name", name)
                .put("description", description)
                .put("parameters", params)

            toolsArray.put(
                JSONObject()
                    .put("type", "function")
                    .put("function", fn)
            )
        }

        if (settings.androidActionsEnabled) {
            addTool(
                name = "set_flashlight",
                description = "Turn the Android phone's camera flashlight / torch on or off (Taschenlampe ein-/ausschalten).",
                properties = JSONObject().put(
                    "enabled",
                    JSONObject()
                        .put("type", "boolean")
                        .put("description", "true to turn on flashlight, false to turn off")
                ),
                required = listOf("enabled")
            )

            addTool(
                name = "open_app",
                description = "Launch an installed Android application by its display name, German/English alias, or package name (App öffnen). Supports fuzzy matching.",
                properties = JSONObject().put(
                    "app_name",
                    JSONObject()
                        .put("type", "string")
                        .put("description", "Name of the app to open, e.g. 'Spotify', 'YouTube', 'Camera'/'Kamera', 'Settings', 'WhatsApp'")
                ),
                required = listOf("app_name")
            )

            addTool(
                name = "open_url_or_deep_link",
                description = "Open a website URL, YouTube video/search, or Maps location/navigation directly on the user's phone screen.",
                properties = JSONObject()
                    .put(
                        "target",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "Full URL (https://...), search query, or address/place name")
                    )
                    .put(
                        "type",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "'url' (default), 'youtube' (search/play on YouTube), or 'maps' (open location in Maps)")
                    ),
                required = listOf("target")
            )

            addTool(
                name = "set_volume",
                description = "Set or adjust the Android phone's audio volume (music, ring, alarm, notification) to a percentage (0-100).",
                properties = JSONObject()
                    .put(
                        "stream",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "Stream type: 'music', 'ring', 'alarm', or 'notification'")
                    )
                    .put(
                        "percent",
                        JSONObject()
                            .put("type", "integer")
                            .put("description", "Volume percentage from 0 to 100")
                    ),
                required = listOf("percent")
            )

            addTool(
                name = "set_alarm_or_timer",
                description = "Set an Android system alarm (hour/minute) or countdown timer (seconds). (Wecker oder Timer stellen).",
                properties = JSONObject()
                    .put("mode", JSONObject().put("type", "string").put("description", "'alarm' or 'timer'"))
                    .put("hour", JSONObject().put("type", "integer").put("description", "Hour (0-23) for alarm"))
                    .put("minute", JSONObject().put("type", "integer").put("description", "Minute (0-59) for alarm"))
                    .put("seconds", JSONObject().put("type", "integer").put("description", "Duration in seconds for timer"))
                    .put("label", JSONObject().put("type", "string").put("description", "Optional label/message")),
                required = listOf("mode")
            )

            addTool(
                name = "get_device_status",
                description = "Get current Android phone status: battery percentage, charging state, current time/date, volume levels, and flashlight status.",
                properties = JSONObject()
            )

            addTool(
                name = "open_system_settings",
                description = "Open a specific Android system settings screen ('wifi', 'bluetooth', 'display', 'sound', 'battery', 'assistant', 'main').",
                properties = JSONObject().put(
                    "section",
                    JSONObject().put("type", "string").put("description", "Settings section to open")
                ),
                required = listOf("section")
            )

            addTool(
                name = "control_media",
                description = "Control active media playback on the phone ('play_pause', 'next', 'previous').",
                properties = JSONObject().put(
                    "action",
                    JSONObject().put("type", "string").put("description", "'play_pause', 'next', or 'previous'")
                ),
                required = listOf("action")
            )

            addTool(
                name = "clipboard_action",
                description = "Read from or copy text to the Android system clipboard.",
                properties = JSONObject()
                    .put("action", JSONObject().put("type", "string").put("description", "'read' or 'write'"))
                    .put("text", JSONObject().put("type", "string").put("description", "Text to copy when action is 'write'")),
                required = listOf("action")
            )
        }

        if (settings.webSearchEnabled) {
            addTool(
                name = "search_duckduckgo",
                description = "Fast short web & news research using DuckDuckGo, live News RSS, and Wikipedia. MUST be called whenever the user asks about up-to-date, current, recent, or researchable information (e.g., latest AI models, tech releases, news, current events, weather, prices, sports, or post-cutoff facts). Do NOT call this for phone hardware actions like turning on the flashlight or opening apps.",
                properties = JSONObject()
                    .put("query", JSONObject().put("type", "string").put("description", "Primary search query (include current year 2026 when searching for latest/current info)"))
                    .put(
                        "queries",
                        JSONObject()
                            .put("type", "array")
                            .put("items", JSONObject().put("type", "string"))
                            .put("description", "Optional extra search queries only if needed")
                    )
                    .put("max_results", JSONObject().put("type", "integer").put("description", "Max results per query (default 6)"))
                    .put("read_top_pages", JSONObject().put("type", "integer").put("description", "Optional number of top pages to read (default 0 for speed; set to 1 if snippet is insufficient)"))
            )
        }

        if (settings.webSearchEnabled && settings.webReaderEnabled) {
            addTool(
                name = "deep_research",
                description = "Deep multi-query search and multi-website extraction. ONLY use this tool when the user explicitly asks for a detailed/deep search, thorough research, or multi-source comparison. For normal questions, prefer `search_duckduckgo` for speed.",
                properties = JSONObject()
                    .put(
                        "queries",
                        JSONObject()
                            .put("type", "array")
                            .put("items", JSONObject().put("type", "string"))
                            .put("description", "2 to 4 distinct search queries covering different angles of the user's question")
                    )
                    .put(
                        "max_pages_to_read",
                        JSONObject()
                            .put("type", "integer")
                            .put("description", "Number of top unique websites to fetch and extract in parallel (default 2, max 4)")
                    ),
                required = listOf("queries")
            )
        }

        if (settings.webReaderEnabled) {
            addTool(
                name = "read_website",
                description = "Fetch and read the clean article/page text content of one or multiple website URLs in parallel for detailed grounding or summarization.",
                properties = JSONObject()
                    .put(
                        "url",
                        JSONObject().put("type", "string").put("description", "Single HTTP/HTTPS URL to read")
                    )
                    .put(
                        "urls",
                        JSONObject()
                            .put("type", "array")
                            .put("items", JSONObject().put("type", "string"))
                            .put("description", "Optional list of multiple HTTP/HTTPS URLs (up to 5) to fetch and read concurrently")
                    )
            )
        }

        if (settings.memoryEnabled) {
            addTool(
                name = "save_memory",
                description = "Save an important fact, user preference, name, or note into Luna's persistent on-device memory.",
                properties = JSONObject()
                    .put("key", JSONObject().put("type", "string").put("description", "Short identifier key, e.g. 'user_name', 'favorite_coffee'"))
                    .put("value", JSONObject().put("type", "string").put("description", "The information or fact to remember"))
                    .put("category", JSONObject().put("type", "string").put("description", "Category, e.g. 'personal', 'preference', 'note'")),
                required = listOf("key", "value")
            )

            addTool(
                name = "recall_memories",
                description = "Search or list facts stored in Luna's persistent memory.",
                properties = JSONObject().put(
                    "query",
                    JSONObject().put("type", "string").put("description", "Optional search keyword, or empty to list all memories")
                )
            )

            addTool(
                name = "forget_memory",
                description = "Delete a stored memory by its key.",
                properties = JSONObject().put(
                    "key",
                    JSONObject().put("type", "string").put("description", "Key of the memory to delete")
                ),
                required = listOf("key")
            )
        }

        return toolsArray
    }

    suspend fun executeBuiltInTool(
        context: Context,
        name: String,
        args: JSONObject
    ): String = withContext(Dispatchers.IO) {
        try {
            when (name.trim().lowercase(Locale.ROOT)) {
                "set_flashlight" -> {
                    val enabled = args.optBoolean("enabled", !isFlashlightOn)
                    setFlashlight(context, enabled)
                }
                "open_app" -> {
                    val appName = args.optString("app_name", "")
                        .ifBlank { args.optString("name", "") }
                        .ifBlank { args.optString("package_name", "") }
                    openInstalledApp(context, appName)
                }
                "open_url_or_deep_link" -> {
                    val target = args.optString("target", "").ifBlank { args.optString("url", "") }
                    val type = args.optString("type", "url")
                    openUrlOrDeepLink(context, target, type)
                }
                "set_volume" -> {
                    val stream = args.optString("stream", "music")
                    val percent = args.optInt("percent", 50)
                    setSystemVolume(context, stream, percent)
                }
                "set_alarm_or_timer" -> {
                    val mode = args.optString("mode", "alarm")
                    val hour = args.optInt("hour", 8)
                    val minute = args.optInt("minute", 0)
                    val seconds = args.optInt("seconds", 60)
                    val label = args.optString("label", "Luna")
                    setAlarmOrTimer(context, mode, hour, minute, seconds, label)
                }
                "get_device_status" -> {
                    getDeviceStatusJson(context)
                }
                "open_system_settings" -> {
                    val section = args.optString("section", "main")
                    openSystemSettings(context, section)
                }
                "control_media" -> {
                    val action = args.optString("action", "play_pause")
                    controlMediaPlayback(context, action)
                }
                "clipboard_action" -> {
                    val action = args.optString("action", "read")
                    val text = args.optString("text", "")
                    handleClipboard(context, action, text)
                }
                "search_duckduckgo" -> {
                    val singleQuery = args.optString("query", "").trim()
                    val queriesArr = args.optJSONArray("queries")
                    val queriesList = mutableListOf<String>()
                    if (singleQuery.isNotBlank()) queriesList.add(singleQuery)
                    if (queriesArr != null) {
                        for (i in 0 until queriesArr.length()) {
                            val q = queriesArr.optString(i, "").trim()
                            if (q.isNotBlank() && !queriesList.contains(q)) {
                                queriesList.add(q)
                            }
                        }
                    }
                    val maxResults = args.optInt("max_results", 5).coerceIn(1, 10)
                    val readTopPages = args.optInt("read_top_pages", 0).coerceIn(0, 4)
                    if (queriesList.size > 1 || readTopPages > 0) {
                        executeMultiQuerySearch(queriesList.take(4), maxResults, readTopPages)
                    } else {
                        searchDuckDuckGo(queriesList.firstOrNull().orEmpty(), maxResults)
                    }
                }
                "deep_research" -> {
                    val queriesArr = args.optJSONArray("queries")
                    val queriesList = mutableListOf<String>()
                    if (queriesArr != null) {
                        for (i in 0 until queriesArr.length()) {
                            val q = queriesArr.optString(i, "").trim()
                            if (q.isNotBlank() && !queriesList.contains(q)) {
                                queriesList.add(q)
                            }
                        }
                    }
                    val fallbackQuery = args.optString("query", "").trim()
                    if (queriesList.isEmpty() && fallbackQuery.isNotBlank()) {
                        queriesList.add(fallbackQuery)
                    }
                    val maxPages = args.optInt("max_pages_to_read", 3).coerceIn(1, 5)
                    executeMultiQuerySearch(queriesList.take(4), maxResultsPerQuery = 5, readTopPages = maxPages)
                }
                "read_website" -> {
                    val singleUrl = args.optString("url", "").trim()
                    val urlsArr = args.optJSONArray("urls")
                    val urlsList = mutableListOf<String>()
                    if (singleUrl.isNotBlank()) urlsList.add(singleUrl)
                    if (urlsArr != null) {
                        for (i in 0 until urlsArr.length()) {
                            val u = urlsArr.optString(i, "").trim()
                            if (u.isNotBlank() && !urlsList.contains(u)) {
                                urlsList.add(u)
                            }
                        }
                    }
                    if (urlsList.size > 1) {
                        readMultipleWebsites(urlsList.take(5))
                    } else {
                        readWebsiteContent(urlsList.firstOrNull().orEmpty())
                    }
                }
                "save_memory" -> {
                    val key = args.optString("key", "").trim()
                    val value = args.optString("value", "").trim()
                    val category = args.optString("category", "general").trim()
                    saveMemory(context, key, value, category)
                }
                "recall_memories" -> {
                    val query = args.optString("query", "").trim()
                    recallMemories(context, query)
                }
                "forget_memory" -> {
                    val key = args.optString("key", "").trim()
                    forgetMemory(context, key)
                }
                else -> JSONObject().put("ok", false).put("error", "Unknown built-in tool: $name").toString()
            }
        } catch (t: Throwable) {
            JSONObject().put("ok", false).put("error", t.message ?: "Tool execution failed").toString()
        }
    }

    fun setFlashlight(context: Context, enabled: Boolean): String {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return JSONObject().put("ok", false).put("error", "CameraManager unavailable").toString()

        val torchCameraId = cm.cameraIdList.firstOrNull { id ->
            val chars = cm.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: cm.cameraIdList.firstOrNull()
            ?: return JSONObject().put("ok", false).put("error", "No camera with flash found on device").toString()

        cm.setTorchMode(torchCameraId, enabled)
        isFlashlightOn = enabled
        return JSONObject()
            .put("ok", true)
            .put("flashlight_enabled", enabled)
            .put("message", if (enabled) "Flashlight turned ON" else "Flashlight turned OFF")
            .toString()
    }

    private fun openInstalledApp(context: Context, query: String): String {
        if (query.isBlank()) {
            return JSONObject().put("ok", false).put("error", "Empty app_name").toString()
        }
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
        val normQuery = query.trim().lowercase(Locale.ROOT)
        val queryTokens = normQuery.split(Regex("[\\s._-]+")).filter { it.length >= 2 }
        val synonymCandidates = appSynonymKeywords[normQuery].orEmpty()

        data class AppCandidate(
            val label: String,
            val normLabel: String,
            val pkg: String,
            val normPkg: String,
            val score: Int
        )

        val scored = resolveInfos.map { info ->
            val label = info.loadLabel(pm).toString().trim()
            val normLabel = label.lowercase(Locale.ROOT)
            val pkg = info.activityInfo.packageName
            val normPkg = pkg.lowercase(Locale.ROOT)

            var score = 0
            when {
                normLabel == normQuery || normPkg == normQuery -> score += 100
                normLabel.startsWith(normQuery) -> score += 85
                normLabel.contains(normQuery) -> score += 70
                normPkg.endsWith(".$normQuery") -> score += 75
                normPkg.contains(normQuery) -> score += 55
            }

            for (syn in synonymCandidates) {
                when {
                    normLabel == syn -> score = maxOf(score, 92)
                    normLabel.contains(syn) -> score = maxOf(score, 78)
                    normPkg.contains(syn) -> score = maxOf(score, 68)
                }
            }

            if (score == 0 && queryTokens.isNotEmpty()) {
                val tokenHits = queryTokens.count { tok -> normLabel.contains(tok) || normPkg.contains(tok) }
                if (tokenHits > 0) {
                    score += tokenHits * 30
                }
            }

            AppCandidate(label, normLabel, pkg, normPkg, score)
        }.distinctBy { it.pkg }.sortedByDescending { it.score }

        val best = scored.firstOrNull { it.score >= 30 }
        if (best == null) {
            val sampleApps = scored.take(25).map { it.label }.distinct().sorted().joinToString(", ")
            return JSONObject()
                .put("ok", false)
                .put("error", "No installed app matching '$query' was found.")
                .put("installed_apps_sample", sampleApps)
                .toString()
        }

        val launchIntent = pm.getLaunchIntentForPackage(best.pkg)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (launchIntent != null) {
            LunaAssistantController.notifyExternalActivityLaunched(context)
            context.startActivity(launchIntent)
            LunaAssistantController.notifyExternalActivityLaunched(context)
            return JSONObject()
                .put("ok", true)
                .put("requested", query)
                .put("launched_app", best.label)
                .put("package_name", best.pkg)
                .toString()
        }
        return JSONObject().put("ok", false).put("error", "Could not create launch intent for ${best.label}").toString()
    }

    private fun openUrlOrDeepLink(context: Context, target: String, type: String): String {
        val cleanTarget = target.trim()
        if (cleanTarget.isEmpty()) {
            return JSONObject().put("ok", false).put("error", "Empty target").toString()
        }

        val uri = when (type.trim().lowercase(Locale.ROOT)) {
            "youtube", "video" -> {
                if (cleanTarget.startsWith("http://") || cleanTarget.startsWith("https://")) {
                    Uri.parse(cleanTarget)
                } else {
                    Uri.parse("https://www.youtube.com/results?search_query=${URLEncoder.encode(cleanTarget, "UTF-8")}")
                }
            }
            "maps", "map", "navigation" -> {
                if (cleanTarget.startsWith("http://") || cleanTarget.startsWith("https://") || cleanTarget.startsWith("geo:")) {
                    Uri.parse(cleanTarget)
                } else {
                    Uri.parse("geo:0,0?q=${URLEncoder.encode(cleanTarget, "UTF-8")}")
                }
            }
            else -> {
                if (cleanTarget.startsWith("http://") || cleanTarget.startsWith("https://") || cleanTarget.contains("://")) {
                    Uri.parse(cleanTarget)
                } else {
                    Uri.parse("https://$cleanTarget")
                }
            }
        }

        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        LunaAssistantController.notifyExternalActivityLaunched(context)
        context.startActivity(intent)
        LunaAssistantController.notifyExternalActivityLaunched(context)

        return JSONObject()
            .put("ok", true)
            .put("type", type)
            .put("target", cleanTarget)
            .put("opened_uri", uri.toString())
            .toString()
    }

    private fun setSystemVolume(context: Context, streamName: String, percent: Int): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return JSONObject().put("ok", false).put("error", "AudioManager unavailable").toString()

        val streamType = when (streamName.lowercase(Locale.ROOT)) {
            "ring", "ringtone" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "assistant", "ai", "voice", "accessibility", "luna" ->
                com.ultrapg.luna.core.speech.LunaTtsEngine.getInstance(context).getPreferredVolumeControlStream()
            else -> AudioManager.STREAM_MUSIC
        }

        val clampedPct = percent.coerceIn(0, 100)
        val maxVol = am.getStreamMaxVolume(streamType).coerceAtLeast(1)
        val targetVol = ((clampedPct / 100.0) * maxVol).toInt().coerceIn(0, maxVol)
        am.setStreamVolume(streamType, targetVol, AudioManager.FLAG_SHOW_UI)

        return JSONObject()
            .put("ok", true)
            .put("stream", streamName)
            .put("percent", clampedPct)
            .put("level", "$targetVol/$maxVol")
            .toString()
    }

    private fun setAlarmOrTimer(
        context: Context,
        mode: String,
        hour: Int,
        minute: Int,
        seconds: Int,
        label: String
    ): String {
        return if (mode.equals("timer", ignoreCase = true)) {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceAtLeast(1))
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            LunaAssistantController.notifyExternalActivityLaunched(context)
            context.startActivity(intent)
            LunaAssistantController.notifyExternalActivityLaunched(context)
            JSONObject()
                .put("ok", true)
                .put("mode", "timer")
                .put("seconds", seconds)
                .put("label", label)
                .toString()
        } else {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
                putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            LunaAssistantController.notifyExternalActivityLaunched(context)
            context.startActivity(intent)
            LunaAssistantController.notifyExternalActivityLaunched(context)
            JSONObject()
                .put("ok", true)
                .put("mode", "alarm")
                .put("time", String.format(Locale.US, "%02d:%02d", hour.coerceIn(0, 23), minute.coerceIn(0, 59)))
                .put("label", label)
                .toString()
        }
    }

    private fun getDeviceStatusJson(context: Context): String {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else -1

        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val musicVol = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        val musicMax = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15
        val musicPct = (musicVol * 100) / musicMax

        val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z (EEEE)", Locale.getDefault()).format(Date())

        return JSONObject()
            .put("ok", true)
            .put("current_time", nowStr)
            .put("battery_percent", batteryPct)
            .put("is_charging", isCharging)
            .put("music_volume_percent", musicPct)
            .put("flashlight_on", isFlashlightOn)
            .toString()
    }

    private fun openSystemSettings(context: Context, section: String): String {
        val action = when (section.lowercase(Locale.ROOT)) {
            "wifi", "wlan" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth", "bt" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display", "screen", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "audio", "volume" -> Settings.ACTION_SOUND_SETTINGS
            "battery", "power" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "assistant", "voice" -> Settings.ACTION_VOICE_INPUT_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        val intent = Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        LunaAssistantController.notifyExternalActivityLaunched(context)
        context.startActivity(intent)
        LunaAssistantController.notifyExternalActivityLaunched(context)
        return JSONObject().put("ok", true).put("opened_settings", section).toString()
    }

    private fun controlMediaPlayback(context: Context, action: String): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return JSONObject().put("ok", false).put("error", "AudioManager unavailable").toString()

        val keyCode = when (action.lowercase(Locale.ROOT)) {
            "next", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return JSONObject().put("ok", true).put("media_action", action).toString()
    }

    private suspend fun handleClipboard(context: Context, action: String, text: String): String = withContext(Dispatchers.Main) {
        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return@withContext JSONObject().put("ok", false).put("error", "Clipboard unavailable").toString()

        if (action.equals("write", ignoreCase = true)) {
            cb.setPrimaryClip(ClipData.newPlainText("Luna", text))
            JSONObject().put("ok", true).put("copied_length", text.length).toString()
        } else {
            val item = cb.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
            JSONObject().put("ok", true).put("clipboard_text", item).toString()
        }
    }

    /**
     * Searches DuckDuckGo HTML Lite + Instant Answer API + Live Google News RSS + Wikipedia Search API
     * for fast, reliable single-step web and current-events grounding.
     */
    fun searchDuckDuckGo(query: String, maxResults: Int = 6): String {
        if (query.isBlank()) {
            return JSONObject().put("ok", false).put("error", "Empty search query").toString()
        }
        val cleanQuery = query.trim()
        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val url = "https://html.duckduckgo.com/html/?q=$encoded"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
            .header("Accept-Language", "en-US,en;q=0.9,de-DE;q=0.8,de;q=0.7")
            .get()
            .build()

        val resultsArr = JSONArray()
        val seenTitles = mutableSetOf<String>()
        var instantSummary = ""

        fun addResultIfUnique(title: String, resultUrl: String, domain: String, snippet: String) {
            val cleanTitle = title.trim()
            if (cleanTitle.isEmpty()) return
            val normKey = cleanTitle.lowercase(Locale.ROOT).take(60)
            if (!seenTitles.add(normKey)) return
            resultsArr.put(
                JSONObject()
                    .put("rank", resultsArr.length() + 1)
                    .put("title", cleanTitle)
                    .put("url", resultUrl)
                    .put("domain", domain)
                    .put("snippet", snippet.trim())
            )
        }

        val iaUrl = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
        runCatching {
            val iaReq = Request.Builder().url(iaUrl).get().build()
            httpClient.newCall(iaReq).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val json = JSONObject(body)
                val abstractText = json.optString("AbstractText", "").trim()
                val answerText = json.optString("Answer", "").trim()
                instantSummary = listOf(answerText, abstractText).filter { it.isNotBlank() }.joinToString(" — ")
            }
        }

        // 1. DuckDuckGo Organic HTML results
        runCatching {
            httpClient.newCall(request).execute().use { response ->
                val html = response.body?.string().orEmpty()
                val linkRegex = Regex(
                    """<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a>""",
                    RegexOption.IGNORE_CASE
                )
                val snippetRegex = Regex(
                    """<a[^>]*class="[^"]*result__snippet[^"]*"[^>]*>([\s\S]*?)</a>""",
                    RegexOption.IGNORE_CASE
                )

                val links = linkRegex.findAll(html).toList()
                val snippets = snippetRegex.findAll(html).toList()

                for (i in 0 until minOf(links.size, maxResults)) {
                    val rawHref = links[i].groupValues[1]
                    val resolvedUrl = extractDuckDuckGoRedirectUrl(rawHref)
                    val title = stripHtmlTags(links[i].groupValues[2])
                    val snippet = if (i < snippets.size) stripHtmlTags(snippets[i].groupValues[1]) else ""
                    val domain = runCatching { Uri.parse(resolvedUrl).host?.removePrefix("www.") ?: "" }.getOrDefault("")
                    if (title.isNotBlank() && resolvedUrl.isNotBlank()) {
                        addResultIfUnique(title, resolvedUrl, domain, snippet)
                    }
                }
            }
        }

        // 2. Live Google News RSS feed for up-to-date news, model releases, and dated events
        runCatching {
            val isGermanQuery = cleanQuery.any { it == 'ä' || it == 'ö' || it == 'ü' || it == 'ß' } ||
                Regex("\\b(was|wie|wer|wo|wann|aktuell|neueste|nachrichten|wetter|ki|heute)\\b", RegexOption.IGNORE_CASE)
                    .containsMatchIn(cleanQuery)
            val rssUrl = if (isGermanQuery) {
                "https://news.google.com/rss/search?q=$encoded&hl=de&gl=DE&ceid=DE:de"
            } else {
                "https://news.google.com/rss/search?q=$encoded&hl=en-US&gl=US&ceid=US:en"
            }
            val rssReq = Request.Builder()
                .url(rssUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
                .get()
                .build()
            httpClient.newCall(rssReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val xml = resp.body?.string().orEmpty()
                    val itemMatches = Regex("<item>([\\s\\S]*?)</item>", RegexOption.IGNORE_CASE).findAll(xml).take(4)
                    for (m in itemMatches) {
                        if (resultsArr.length() >= maxResults + 2) break
                        val itemXml = m.groupValues[1]
                        val rawTitle = Regex("<title>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
                            .find(itemXml)?.groupValues?.get(1).orEmpty()
                        val link = Regex("<link>([\\s\\S]*?)</link>", RegexOption.IGNORE_CASE)
                            .find(itemXml)?.groupValues?.get(1)?.trim().orEmpty()
                        val pubDate = Regex("<pubDate>([\\s\\S]*?)</pubDate>", RegexOption.IGNORE_CASE)
                            .find(itemXml)?.groupValues?.get(1)?.trim().orEmpty()
                        val source = Regex("<source[^>]*>([\\s\\S]*?)</source>", RegexOption.IGNORE_CASE)
                            .find(itemXml)?.groupValues?.get(1)?.let { stripHtmlTags(it) }.orEmpty()
                        val desc = Regex("<description>([\\s\\S]*?)</description>", RegexOption.IGNORE_CASE)
                            .find(itemXml)?.groupValues?.get(1)?.let { stripHtmlTags(it) }.orEmpty()

                        val cleanTitle = stripHtmlTags(rawTitle)
                        val snippetParts = listOfNotNull(
                            source.takeIf { it.isNotBlank() },
                            pubDate.takeIf { it.isNotBlank() },
                            desc.takeIf { it.isNotBlank() && !it.equals(cleanTitle, ignoreCase = true) }
                        )
                        addResultIfUnique(
                            title = cleanTitle,
                            resultUrl = link,
                            domain = source.ifBlank { "news.google.com" },
                            snippet = snippetParts.joinToString(" — ")
                        )
                    }
                }
            }
        }

        // 3. Wikipedia Search API for encyclopedic & entity grounding
        if (resultsArr.length() < maxResults) {
            runCatching {
                val isGermanQuery = cleanQuery.any { it == 'ä' || it == 'ö' || it == 'ü' || it == 'ß' } ||
                    Regex("\\b(was|wie|wer|wo|wann|aktuell|neueste|nachrichten|wetter|ki)\\b", RegexOption.IGNORE_CASE)
                        .containsMatchIn(cleanQuery)
                val wikiHost = if (isGermanQuery) "de.wikipedia.org" else "en.wikipedia.org"
                val wikiUrl = "https://$wikiHost/w/api.php?action=query&list=search&srsearch=$encoded&utf8=&format=json&srlimit=3"
                val wikiReq = Request.Builder()
                    .url(wikiUrl)
                    .header("User-Agent", "LunaAndroidAssistant/1.0")
                    .get()
                    .build()
                httpClient.newCall(wikiReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string().orEmpty())
                        val searchArr = json.optJSONObject("query")?.optJSONArray("search")
                        if (searchArr != null) {
                            for (i in 0 until searchArr.length()) {
                                if (resultsArr.length() >= maxResults + 2) break
                                val item = searchArr.optJSONObject(i) ?: continue
                                val title = item.optString("title", "").trim()
                                val snippet = stripHtmlTags(item.optString("snippet", ""))
                                if (title.isNotBlank() && snippet.isNotBlank()) {
                                    val articleUrl = "https://$wikiHost/wiki/${URLEncoder.encode(title.replace(' ', '_'), "UTF-8")}"
                                    addResultIfUnique(title, articleUrl, wikiHost, snippet)
                                }
                            }
                        }
                    }
                }
            }
        }

        return JSONObject()
            .put("ok", true)
            .put("query", cleanQuery)
            .put("instant_answer", instantSummary)
            .put("results_count", resultsArr.length())
            .put("results", resultsArr)
            .toString()
    }

    /**
     * Fetches a webpage and extracts clean, readable article/page text for LLM grounding.
     */
    fun readWebsiteContent(rawUrl: String): String {
        val url = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            rawUrl.trim()
        } else {
            "https://${rawUrl.trim()}"
        }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val rawHtml = response.body?.string().orEmpty()
            val titleMatch = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(rawHtml)
            val title = titleMatch?.groupValues?.get(1)?.let { stripHtmlTags(it) } ?: url
            val domain = runCatching { Uri.parse(url).host?.removePrefix("www.") ?: "" }.getOrDefault("")

            val cleanedText = htmlToReadableText(rawHtml).take(10_000)
            return JSONObject()
                .put("ok", response.isSuccessful)
                .put("status_code", response.code)
                .put("url", url)
                .put("domain", domain)
                .put("title", title)
                .put("content", cleanedText)
                .toString()
        }
    }

    /**
     * Fetches and extracts readable text from multiple website URLs concurrently.
     */
    suspend fun readMultipleWebsites(urls: List<String>): String = coroutineScope {
        val cleanUrls = urls.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(5)
        if (cleanUrls.isEmpty()) {
            return@coroutineScope JSONObject().put("ok", false).put("error", "Empty urls list").toString()
        }

        val fetchedPages = cleanUrls.map { u ->
            async(Dispatchers.IO) {
                runCatching {
                    val raw = readWebsiteContent(u)
                    JSONObject(raw)
                }.getOrElse { err ->
                    JSONObject()
                        .put("ok", false)
                        .put("url", u)
                        .put("title", u)
                        .put("error", err.message ?: "Failed to fetch")
                }
            }
        }.awaitAll()

        val pagesArr = JSONArray()
        fetchedPages.forEach { pageJson ->
            val trimmedContent = pageJson.optString("content", "").take(4500)
            pageJson.put("content", trimmedContent)
            pagesArr.put(pageJson)
        }

        val firstPage = fetchedPages.firstOrNull()
        JSONObject()
            .put("ok", true)
            .put("pages_count", pagesArr.length())
            .put("pages", pagesArr)
            .put("title", "${pagesArr.length()} Websites Read")
            .put("url", firstPage?.optString("url", "").orEmpty())
            .put(
                "content",
                fetchedPages.mapIndexed { idx, p ->
                    val t = p.optString("title", p.optString("url", "Source ${idx + 1}"))
                    val u = p.optString("url", "")
                    val c = p.optString("content", p.optString("error", ""))
                    "### [${idx + 1}] $t ($u)\n$c"
                }.joinToString("\n\n")
            )
            .toString()
    }

    /**
     * Deep multi-query search & optional parallel multi-website content extraction.
     * Runs all search queries concurrently, merges and deduplicates top organic results,
     * and if `readTopPages > 0`, pulls the full readable text from the top unique URLs in parallel.
     */
    suspend fun executeMultiQuerySearch(
        queries: List<String>,
        maxResultsPerQuery: Int = 5,
        readTopPages: Int = 0
    ): String = coroutineScope {
        val cleanQueries = queries.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(4)
        if (cleanQueries.isEmpty()) {
            return@coroutineScope JSONObject().put("ok", false).put("error", "No search queries provided").toString()
        }

        val searchJsons = cleanQueries.map { q ->
            async(Dispatchers.IO) {
                q to runCatching { JSONObject(searchDuckDuckGo(q, maxResultsPerQuery)) }.getOrNull()
            }
        }.awaitAll()

        val mergedResults = JSONArray()
        val seenUrls = mutableSetOf<String>()
        val instantAnswers = mutableListOf<String>()
        val queriesExecutedArr = JSONArray()

        for ((q, json) in searchJsons) {
            queriesExecutedArr.put(q)
            if (json == null) continue
            val ia = json.optString("instant_answer", "").trim()
            if (ia.isNotBlank()) {
                instantAnswers.add("[$q]: $ia")
            }
            val arr = json.optJSONArray("results") ?: continue
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val url = item.optString("url", "").trim()
                if (url.isNotBlank() && seenUrls.add(url)) {
                    mergedResults.put(
                        JSONObject(item.toString())
                            .put("rank", mergedResults.length() + 1)
                            .put("matched_query", q)
                    )
                }
            }
        }

        val pagesArr = JSONArray()
        if (readTopPages > 0 && mergedResults.length() > 0) {
            val topUrls = mutableListOf<String>()
            for (i in 0 until mergedResults.length()) {
                val u = mergedResults.optJSONObject(i)?.optString("url", "").orEmpty()
                if (u.startsWith("http") && !u.endsWith(".pdf", ignoreCase = true)) {
                    topUrls.add(u)
                }
                if (topUrls.size >= readTopPages) break
            }

            val pulledPages = topUrls.map { targetUrl ->
                async(Dispatchers.IO) {
                    runCatching {
                        val pageObj = JSONObject(readWebsiteContent(targetUrl))
                        pageObj.put("content", pageObj.optString("content", "").take(3800))
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()

            pulledPages.forEach { pagesArr.put(it) }
        }

        JSONObject()
            .put("ok", true)
            .put("query", cleanQueries.joinToString(" | "))
            .put("queries", queriesExecutedArr)
            .put("instant_answer", instantAnswers.distinct().joinToString("\n"))
            .put("results_count", mergedResults.length())
            .put("results", mergedResults)
            .put("pages_read_count", pagesArr.length())
            .put("pages", pagesArr)
            .toString()
    }

    private suspend fun saveMemory(context: Context, key: String, value: String, category: String): String {
        if (key.isBlank() || value.isBlank()) {
            return JSONObject().put("ok", false).put("error", "Both key and value are required").toString()
        }
        val dao = LunaDatabase.getInstance(context).memoryDao()
        val existing = dao.findByKey(key)
        val entity = if (existing != null) {
            existing.copy(value = value, category = category.ifBlank { existing.category }, updatedAt = System.currentTimeMillis())
        } else {
            MemoryEntity(key = key, value = value, category = category.ifBlank { "general" })
        }
        dao.insertMemory(entity)
        return JSONObject()
            .put("ok", true)
            .put("saved_key", key)
            .put("saved_value", value)
            .put("category", entity.category)
            .toString()
    }

    private suspend fun recallMemories(context: Context, query: String): String {
        val dao = LunaDatabase.getInstance(context).memoryDao()
        val list = if (query.isBlank()) dao.getAllMemories() else dao.searchMemories(query)
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(
                JSONObject()
                    .put("key", m.key)
                    .put("value", m.value)
                    .put("category", m.category)
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("count", arr.length())
            .put("memories", arr)
            .toString()
    }

    private suspend fun forgetMemory(context: Context, key: String): String {
        val dao = LunaDatabase.getInstance(context).memoryDao()
        dao.deleteMemoryByKey(key)
        return JSONObject().put("ok", true).put("deleted_key", key).toString()
    }

    private fun extractDuckDuckGoRedirectUrl(href: String): String {
        return try {
            val uddgIdx = href.indexOf("uddg=")
            if (uddgIdx >= 0) {
                val sub = href.substring(uddgIdx + 5).substringBefore("&")
                URLDecoder.decode(sub, "UTF-8")
            } else if (href.startsWith("//")) {
                "https:$href"
            } else {
                href
            }
        } catch (_: Exception) {
            href
        }
    }

    private fun stripHtmlTags(input: String): String {
        return input
            .replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun htmlToReadableText(html: String): String {
        val withoutScripts = html
            .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<noscript[^>]*>[\\s\\S]*?</noscript>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<header[^>]*>[\\s\\S]*?</header>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<nav[^>]*>[\\s\\S]*?</nav>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<aside[^>]*>[\\s\\S]*?</aside>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<footer[^>]*>[\\s\\S]*?</footer>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<svg[^>]*>[\\s\\S]*?</svg>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("</(p|div|h1|h2|h3|h4|li|tr|br)>", RegexOption.IGNORE_CASE), "\n")
        return stripHtmlTags(withoutScripts)
    }
}
