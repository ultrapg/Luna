package com.ultrapg.luna.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ultrapg.luna.assistant.AssistantOverlayPhase
import com.ultrapg.luna.assistant.LunaAssistantController
import com.ultrapg.luna.core.database.ChatMessageEntity
import com.ultrapg.luna.core.database.ChatSessionEntity
import com.ultrapg.luna.core.database.LunaDatabase
import com.ultrapg.luna.core.settings.LunaSettingsRepository
import com.ultrapg.luna.overlay.LunaOverlayService
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    onRequestMicPermission: () -> Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { LunaDatabase.getInstance(context) }
    val settings by LunaSettingsRepository.settingsFlow.collectAsState()
    val uiState by LunaAssistantController.liveState.collectAsState()

    val sessions by db.chatDao().observeAllSessions().collectAsState(initial = emptyList())
    var selectedSessionId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(uiState.activeSessionId, sessions) {
        if (uiState.activeSessionId != null) {
            selectedSessionId = uiState.activeSessionId
        } else if (selectedSessionId == null && sessions.isNotEmpty()) {
            selectedSessionId = sessions.first().id
            LunaAssistantController.selectSession(sessions.first().id)
        }
    }

    val messagesFlow = remember(selectedSessionId) {
        val id = selectedSessionId
        if (id != null) {
            db.chatDao().observeMessagesForSession(id)
        } else {
            flowOf(emptyList())
        }
    }
    val messages by messagesFlow.collectAsState(initial = emptyList())

    var showHistorySheet by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val isGerman = settings.appLanguage.isGerman

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val isBusy = uiState.phase == AssistantOverlayPhase.LISTENING ||
        uiState.phase == AssistantOverlayPhase.TRANSCRIBING ||
        uiState.phase == AssistantOverlayPhase.THINKING ||
        uiState.phase == AssistantOverlayPhase.SPEAKING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top Action Bar
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalIconButton(onClick = { showHistorySheet = true }) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = if (isGerman) "Chatverlauf" else "Chat History"
                        )
                    }
                    Column {
                        Text(
                            text = sessions.firstOrNull { it.id == selectedSessionId }?.title
                                ?: if (isGerman) "Neues Gespräch mit Luna" else "New Chat with Luna",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 180.dp)
                        )
                        Text(
                            text = "${settings.openAiModel} • ${settings.hardwareAccelerator.shortLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Toggle TTS output
                    IconButton(
                        onClick = {
                            LunaSettingsRepository.update(context) { it.copy(ttsEnabled = !it.ttsEnabled) }
                        }
                    ) {
                        Icon(
                            imageVector = if (settings.ttsEnabled) {
                                Icons.AutoMirrored.Filled.VolumeUp
                            } else {
                                Icons.AutoMirrored.Filled.VolumeOff
                            },
                            contentDescription = "TTS",
                            tint = if (settings.ttsEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }

                    // Spawn Floating Pill Overlay
                    FilledTonalIconButton(
                        onClick = {
                            if (onRequestMicPermission()) {
                                LunaOverlayService.startOrTrigger(context, startListening = true)
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.BubbleChart,
                            contentDescription = if (isGerman) "Seiten-Bubble starten" else "Launch Side Bubble"
                        )
                    }

                    // New Chat
                    FilledIconButton(
                        onClick = {
                            selectedSessionId = null
                            LunaAssistantController.startNewSession()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = if (isGerman) "Neuer Chat" else "New Chat"
                        )
                    }
                }
            }
        }

        // Live Assistant Status Banner when active
        AnimatedVisibility(visible = isBusy) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.5.dp
                        )
                        Column {
                            Text(
                                text = when (uiState.phase) {
                                    AssistantOverlayPhase.LISTENING -> if (isGerman) "Luna hört zu (EAR + VOZ)..." else "Luna is listening (EAR + VOZ)..."
                                    AssistantOverlayPhase.TRANSCRIBING -> if (isGerman) "Transkribiere auf NPU/Gerät..." else "Transcribing locally on device..."
                                    AssistantOverlayPhase.THINKING -> uiState.statusBadge.ifBlank {
                                        if (isGerman) "Luna denkt nach..." else "Luna is thinking..."
                                    }
                                    AssistantOverlayPhase.SPEAKING -> if (isGerman) "Luna spricht..." else "Luna is speaking..."
                                    else -> ""
                                },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (uiState.detectedLanguage.isNotBlank()) {
                                Text(
                                    text = "Language: ${uiState.detectedLanguage.uppercase()}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }

                    if (uiState.phase == AssistantOverlayPhase.LISTENING) {
                        FilledTonalButton(onClick = { LunaAssistantController.finishListeningAndProcess(context) }) {
                            Text(if (isGerman) "Fertig" else "Done")
                        }
                    } else {
                        IconButton(onClick = { LunaAssistantController.cancelCurrentOperation(context) }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel")
                        }
                    }
                }
            }
        }

        // Chat Messages List
        if (messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        Color(0xFF4285F4),
                                        Color(0xFF9B72CB),
                                        Color(0xFFD96570)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Text(
                        text = if (isGerman) "Hallo, ich bin Luna" else "Hi, I'm Luna",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isGerman) {
                            "Dein lokaler Sprachassistent (Deutsch & Englisch) mit NPU-Beschleunigung, Web-Suche, Android-Steuerung und Gemini-Seiten-Bubble."
                        } else {
                            "Your NPU-accelerated voice assistant (English & German) with Android hardware control, DuckDuckGo web grounding, MCP tools, and Gemini side-pill overlay."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    val suggestions = if (isGerman) {
                        listOf(
                            "Schalte die Taschenlampe ein",
                            "Wie ist mein Akkustand?",
                            "Suche im Web nach aktuellen KI-News",
                            "Merke dir, dass ich Kaffee ohne Zucker trinke"
                        )
                    } else {
                        listOf(
                            "Turn on the flashlight",
                            "Check my battery & NPU status",
                            "Search DuckDuckGo for latest Android news",
                            "Remember that I prefer dark mode and espresso"
                        )
                    }

                    suggestions.forEach { prompt ->
                        SuggestionChip(
                            onClick = { LunaAssistantController.submitTextFromOverlay(context, prompt) },
                            label = { Text(prompt) },
                            icon = {
                                Icon(
                                    Icons.Default.Bolt,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(items = messages, key = { it.id }) { msg ->
                    ChatMessageBubble(msg = msg)
                }
            }
        }

        // Bottom Voice & Text Composer
        Surface(
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Voice Input Button (Desert Ant EAR + VOZ)
                FloatingActionButton(
                    onClick = {
                        if (onRequestMicPermission()) {
                            if (uiState.phase == AssistantOverlayPhase.LISTENING) {
                                LunaAssistantController.finishListeningAndProcess(context)
                            } else {
                                LunaAssistantController.startVoiceListening(context)
                            }
                        }
                    },
                    containerColor = if (uiState.phase == AssistantOverlayPhase.LISTENING) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (uiState.phase == AssistantOverlayPhase.LISTENING) {
                            Icons.Default.Stop
                        } else {
                            Icons.Default.Mic
                        },
                        contentDescription = "Voice Input"
                    )
                }

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = {
                        Text(
                            if (isGerman) "Frage Luna oder sprich (DE/EN)..."
                            else "Ask Luna anything or speak (EN/DE)..."
                        )
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4
                )

                FilledIconButton(
                    onClick = {
                        val trimmed = inputText.trim()
                        if (trimmed.isNotEmpty()) {
                            inputText = ""
                            LunaAssistantController.submitTextFromOverlay(context, trimmed)
                        }
                    },
                    enabled = inputText.isNotBlank() && !isBusy,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send"
                    )
                }
            }
        }
    }

    // Chat History Modal Bottom Sheet (View, Continue, or Delete Previous Chats)
    if (showHistorySheet) {
        ModalBottomSheet(
            onDismissRequest = { showHistorySheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isGerman) "Vorherige Gespräche" else "Previous Chats",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    if (sessions.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    db.chatDao().deleteAllSessions()
                                    selectedSessionId = null
                                    LunaAssistantController.startNewSession()
                                }
                            }
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(if (isGerman) "Alle löschen" else "Delete All")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (sessions.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isGerman) "Noch keine gespeicherten Chats." else "No saved chats yet.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(items = sessions, key = { it.id }) { session ->
                            SessionHistoryCard(
                                session = session,
                                isSelected = session.id == selectedSessionId,
                                onSelect = {
                                    selectedSessionId = session.id
                                    LunaAssistantController.selectSession(session.id)
                                    showHistorySheet = false
                                },
                                onDelete = {
                                    LunaAssistantController.deleteSession(context, session.id)
                                    if (selectedSessionId == session.id) {
                                        selectedSessionId = null
                                    }
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ChatMessageBubble(msg: ChatMessageEntity) {
    val isUser = msg.role == "user"
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (msg.toolCallsSummary.isNotBlank()) {
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        text = msg.toolCallsSummary,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.BuildCircle,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                },
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        Surface(
            color = if (isUser) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (isUser) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp
            ),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = msg.content,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

@Composable
private fun SessionHistoryCard(
    session: ChatSessionEntity,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${dateFormat.format(Date(session.updatedAt))} • ${session.modelUsed}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete chat",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
