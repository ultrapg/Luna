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
import androidx.compose.ui.unit.dp
import com.ultrapg.luna.core.database.LunaDatabase
import com.ultrapg.luna.core.database.MemoryEntity
import com.ultrapg.luna.core.mcp.LunaMcpClient
import com.ultrapg.luna.core.settings.LunaSettingsRepository
import com.ultrapg.luna.core.settings.McpServerConfig
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun ToolsMcpScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by LunaSettingsRepository.settingsFlow.collectAsState()
    val db = remember { LunaDatabase.getInstance(context) }
    val memories by db.memoryDao().observeAllMemories().collectAsState(initial = emptyList())
    val scrollState = rememberScrollState()

    val isGerman = settings.appLanguage.isGerman

    // New Memory State
    var newMemoryKey by remember { mutableStateOf("") }
    var newMemoryValue by remember { mutableStateOf("") }

    // New MCP Server State
    var mcpName by remember { mutableStateOf("") }
    var mcpUrl by remember { mutableStateOf("") }
    var mcpApiKey by remember { mutableStateOf("") }
    var mcpTestStatus by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section 1: Built-in Agent Capabilities (Android System Control + Web Grounding)
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
                        imageVector = Icons.Default.Construction,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = if (isGerman) {
                            "Agent-Werkzeuge, Web-Grounding & Android-Steuerung"
                        } else {
                            "Agent Capabilities, Web Grounding & Android Actions"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                ToggleToolRow(
                    title = if (isGerman) "Android-Gerätesteuerung" else "Android Hardware & System Actions",
                    subtitle = if (isGerman) {
                        "Taschenlampe ein/aus, Apps starten, Lautstärke, Wecker/Timer, Akku-Status, Mediensteuerung, Zwischenablage"
                    } else {
                        "Flashlight on/off, launch apps, volume control, alarms/timers, battery/RAM status, media playback, clipboard"
                    },
                    checked = settings.androidActionsEnabled,
                    onCheckedChange = { v ->
                        LunaSettingsRepository.update(context) { it.copy(androidActionsEnabled = v) }
                    }
                )

                ToggleToolRow(
                    title = if (isGerman) "DuckDuckGo Web-Suche (Grounding)" else "DuckDuckGo Web Search Grounding",
                    subtitle = if (isGerman) {
                        "Ermöglicht Luna Live-Recherchen im Internet wie ein autonomer Agent"
                    } else {
                        "Allows Luna to search DuckDuckGo for real-time facts, news, and grounding"
                    },
                    checked = settings.webSearchEnabled,
                    onCheckedChange = { v ->
                        LunaSettingsRepository.update(context) { it.copy(webSearchEnabled = v) }
                    }
                )

                ToggleToolRow(
                    title = if (isGerman) "Webseiten-Leser (Full Page Reader)" else "Website Reader & Extractor",
                    subtitle = if (isGerman) {
                        "Liest und fasst beliebige Webseiten und Dokumentation direkt zusammen"
                    } else {
                        "Fetches and parses full web pages so Luna can read articles and docs"
                    },
                    checked = settings.webReaderEnabled,
                    onCheckedChange = { v ->
                        LunaSettingsRepository.update(context) { it.copy(webReaderEnabled = v) }
                    }
                )

                ToggleToolRow(
                    title = if (isGerman) "Langzeit-Gedächtnis (Memory)" else "Persistent Assistant Memory",
                    subtitle = if (isGerman) {
                        "Luna merkt sich wichtige Fakten, Vorlieben und Notizen über Sitzungen hinweg"
                    } else {
                        "Luna remembers personal preferences, facts, and notes across conversations"
                    },
                    checked = settings.memoryEnabled,
                    onCheckedChange = { v ->
                        LunaSettingsRepository.update(context) { it.copy(memoryEnabled = v) }
                    }
                )
            }
        }

        // Section 2: Persistent Memory Manager
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bookmarks,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = if (isGerman) {
                                "Gespeicherte Erinnerungen (${memories.size})"
                            } else {
                                "Saved Memories (${memories.size})"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (memories.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                scope.launch { db.memoryDao().clearAllMemories() }
                            }
                        ) {
                            Text(if (isGerman) "Alle löschen" else "Clear All")
                        }
                    }
                }

                // Add Memory Form
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newMemoryKey,
                        onValueChange = { newMemoryKey = it },
                        label = { Text(if (isGerman) "Thema/Schlüssel" else "Topic/Key") },
                        singleLine = true,
                        modifier = Modifier.weight(0.4f)
                    )
                    OutlinedTextField(
                        value = newMemoryValue,
                        onValueChange = { newMemoryValue = it },
                        label = { Text(if (isGerman) "Inhalt/Notiz" else "Fact/Preference") },
                        singleLine = true,
                        modifier = Modifier.weight(0.6f)
                    )
                    FilledIconButton(
                        onClick = {
                            if (newMemoryKey.isNotBlank() && newMemoryValue.isNotBlank()) {
                                val k = newMemoryKey.trim()
                                val v = newMemoryValue.trim()
                                newMemoryKey = ""
                                newMemoryValue = ""
                                scope.launch {
                                    db.memoryDao().insertMemory(MemoryEntity(key = k, value = v))
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add Memory")
                    }
                }

                if (memories.isEmpty()) {
                    Text(
                        text = if (isGerman) {
                            "Noch keine Erinnerungen gespeichert. Sage z.B. \"Merke dir, dass ich gerne Espresso trinke\"."
                        } else {
                            "No memories saved yet. Ask Luna e.g. \"Remember that my favorite drink is espresso\"."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    memories.forEach { mem ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = mem.key,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = mem.value,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        scope.launch { db.memoryDao().deleteMemoryById(mem.id) }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Delete Memory",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section 3: MCP (Model Context Protocol) Servers
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
                        imageVector = Icons.Default.Hub,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = if (isGerman) {
                            "MCP-Server (Model Context Protocol)"
                        } else {
                            "MCP Servers (Model Context Protocol)"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = if (isGerman) {
                        "Verbinde externe MCP-Server (JSON-RPC über HTTP/SSE), um Luna mit zusätzlichen Quick-Ask- und Smart-Home-/Entwickler-Tools zu erweitern."
                    } else {
                        "Connect external MCP servers (JSON-RPC over HTTP/SSE) so Luna can discover and invoke remote tools automatically."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = mcpName,
                    onValueChange = { mcpName = it },
                    label = { Text(if (isGerman) "Servername" else "Server Name") },
                    placeholder = { Text("HomeAssistant / Brave / GitHub MCP") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = mcpUrl,
                    onValueChange = { mcpUrl = it },
                    label = { Text("MCP HTTP/JSON-RPC Endpoint URL") },
                    placeholder = { Text("https://my-mcp.example.com/mcp") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = mcpApiKey,
                    onValueChange = { mcpApiKey = it },
                    label = { Text(if (isGerman) "API Key / Bearer Token (Optional)" else "API Key / Bearer Token (Optional)") },
                    placeholder = { Text("eyJ...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        if (mcpName.isNotBlank() && mcpUrl.isNotBlank()) {
                            val newServer = McpServerConfig(
                                id = UUID.randomUUID().toString().take(8),
                                name = mcpName.trim(),
                                url = mcpUrl.trim(),
                                apiKey = mcpApiKey.trim(),
                                enabled = true
                            )
                            LunaSettingsRepository.update(context) {
                                it.copy(mcpServers = it.mcpServers + newServer)
                            }
                            mcpName = ""
                            mcpUrl = ""
                            mcpApiKey = ""
                        }
                    },
                    enabled = mcpName.isNotBlank() && mcpUrl.isNotBlank()
                ) {
                    Icon(Icons.Default.AddLink, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (isGerman) "MCP-Server hinzufügen" else "Add MCP Server")
                }

                if (mcpTestStatus != null) {
                    Text(
                        text = mcpTestStatus!!,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                settings.mcpServers.forEach { server ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = server.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = server.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            mcpTestStatus = if (isGerman) {
                                                "Prüfe ${server.name}..."
                                            } else {
                                                "Testing ${server.name}..."
                                            }
                                            val tools = LunaMcpClient.discoverAllTools(listOf(server))
                                            mcpTestStatus = if (isGerman) {
                                                "${server.name}: ${tools.size} MCP-Tools gefunden"
                                            } else {
                                                "${server.name}: Discovered ${tools.size} MCP tools"
                                            }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Science, contentDescription = "Test MCP")
                                }

                                Switch(
                                    checked = server.enabled,
                                    onCheckedChange = { enabled ->
                                        LunaSettingsRepository.update(context) { s ->
                                            s.copy(
                                                mcpServers = s.mcpServers.map {
                                                    if (it.id == server.id) it.copy(enabled = enabled) else it
                                                }
                                            )
                                        }
                                    }
                                )

                                IconButton(
                                    onClick = {
                                        LunaSettingsRepository.update(context) { s ->
                                            s.copy(mcpServers = s.mcpServers.filterNot { it.id == server.id })
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Remove MCP",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleToolRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
