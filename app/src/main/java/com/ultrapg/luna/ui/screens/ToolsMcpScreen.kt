package com.ultrapg.luna.ui.screens

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
import androidx.compose.ui.text.style.TextOverflow
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

    var newMemoryKey by remember { mutableStateOf("") }
    var newMemoryValue by remember { mutableStateOf("") }

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
        // 1. Clean Capabilities Switches
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
                    text = if (isGerman) "Assistenten-Funktionen" else "Assistant Capabilities",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                ToggleToolRow(
                    title = if (isGerman) "Android-Gerätesteuerung" else "Android Device Control",
                    subtitle = if (isGerman) "Taschenlampe, Apps, Lautstärke, Wecker, Akku" else "Flashlight, apps, volume, alarms, battery",
                    checked = settings.androidActionsEnabled,
                    onCheckedChange = { v ->
                        LunaSettingsRepository.update(context) { it.copy(androidActionsEnabled = v) }
                    }
                )

                ToggleToolRow(
                    title = if (isGerman) "DuckDuckGo Web-Suche" else "DuckDuckGo Web Search",
                    subtitle = if (isGerman) "Live-Internetsuche für aktuelle Fragen" else "Live web grounding for real-time answers",
                    checked = settings.webSearchEnabled,
                    onCheckedChange = { v ->
                        LunaSettingsRepository.update(context) { it.copy(webSearchEnabled = v) }
                    }
                )

                ToggleToolRow(
                    title = if (isGerman) "Webseiten-Leser" else "Website Reader",
                    subtitle = if (isGerman) "Liest und fasst Webseiten zusammen" else "Reads and summarizes full web pages",
                    checked = settings.webReaderEnabled,
                    onCheckedChange = { v ->
                        LunaSettingsRepository.update(context) { it.copy(webReaderEnabled = v) }
                    }
                )

                ToggleToolRow(
                    title = if (isGerman) "Erinnerungen (Memory)" else "Assistant Memory",
                    subtitle = if (isGerman) "Merkt sich Fakten & Vorlieben" else "Remembers facts and preferences",
                    checked = settings.memoryEnabled,
                    onCheckedChange = { v ->
                        LunaSettingsRepository.update(context) { it.copy(memoryEnabled = v) }
                    }
                )
            }
        }

        // 2. Clean Memory Manager (Full-width fields so labels never wrap vertically!)
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isGerman) "Erinnerungen (${memories.size})" else "Memories (${memories.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (memories.isNotEmpty()) {
                        TextButton(
                            onClick = { scope.launch { db.memoryDao().clearAllMemories() } }
                        ) {
                            Text(if (isGerman) "Alle löschen" else "Clear All")
                        }
                    }
                }

                OutlinedTextField(
                    value = newMemoryKey,
                    onValueChange = { newMemoryKey = it },
                    label = { Text(if (isGerman) "Thema" else "Topic", maxLines = 1) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = newMemoryValue,
                    onValueChange = { newMemoryValue = it },
                    label = { Text(if (isGerman) "Notiz / Vorliebe" else "Fact / Preference", maxLines = 1) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                FilledTonalButton(
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
                    },
                    enabled = newMemoryKey.isNotBlank() && newMemoryValue.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isGerman) "Erinnerung speichern" else "Save Memory", maxLines = 1)
                }

                memories.forEach { mem ->
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = mem.key,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = mem.value,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            IconButton(
                                onClick = { scope.launch { db.memoryDao().deleteMemoryById(mem.id) } },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Clean MCP Servers Section
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "MCP Servers",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                OutlinedTextField(
                    value = mcpName,
                    onValueChange = { mcpName = it },
                    label = { Text("Name", maxLines = 1) },
                    placeholder = { Text("HomeAssistant / GitHub") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = mcpUrl,
                    onValueChange = { mcpUrl = it },
                    label = { Text("Endpoint URL", maxLines = 1) },
                    placeholder = { Text("https://example.com/mcp") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = mcpApiKey,
                    onValueChange = { mcpApiKey = it },
                    label = { Text(if (isGerman) "Token (Optional)" else "Token (Optional)", maxLines = 1) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                FilledTonalButton(
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
                    enabled = mcpName.isNotBlank() && mcpUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.AddLink, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isGerman) "Server hinzufügen" else "Add MCP Server", maxLines = 1)
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
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = server.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = server.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            mcpTestStatus = "..."
                                            val tools = LunaMcpClient.discoverAllTools(listOf(server))
                                            mcpTestStatus = "${server.name}: ${tools.size} tools"
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Science, contentDescription = "Test")
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
                                        contentDescription = "Remove",
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
