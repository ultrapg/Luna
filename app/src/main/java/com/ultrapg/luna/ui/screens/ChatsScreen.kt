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
        // Minimal Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                FilledTonalIconButton(
                    onClick = { showHistorySheet = true },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = "History",
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = sessions.firstOrNull { it.id == selectedSessionId }?.title
                            ?: "Luna",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${settings.openAiModel} • ${settings.hardwareAccelerator.shortLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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

                IconButton(
                    onClick = {
                        if (onRequestMicPermission()) {
                            LunaOverlayService.startOrTrigger(context, startListening = true)
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.SmartButton,
                        contentDescription = "Launch Pill"
                    )
                }

                FilledTonalIconButton(
                    onClick = {
                        selectedSessionId = null
                        LunaAssistantController.startNewSession()
                    },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "New Chat",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

        // Compact Status Pill when active
        AnimatedVisibility(visible = isBusy) {
            Surface(
                color = Color(0xFF151821),
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = when (uiState.phase) {
                                AssistantOverlayPhase.LISTENING -> if (isGerman) "Höre zu..." else "Listening..."
                                AssistantOverlayPhase.TRANSCRIBING -> if (isGerman) "Transkribiere..." else "Transcribing..."
                                AssistantOverlayPhase.THINKING -> uiState.statusBadge.ifBlank {
                                    if (isGerman) "Denke nach..." else "Thinking..."
                                }
                                AssistantOverlayPhase.SPEAKING -> if (isGerman) "Spricht..." else "Speaking..."
                                else -> ""
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (uiState.phase == AssistantOverlayPhase.LISTENING) {
                        TextButton(onClick = { LunaAssistantController.finishListeningAndProcess(context) }) {
                            Text(if (isGerman) "Fertig" else "Done")
                        }
                    } else {
                        IconButton(
                            onClick = { LunaAssistantController.cancelCurrentOperation(context) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
                        }
                    }
                }
            }
        }

        // Messages or Clean Empty State
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
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E2433)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Text(
                        text = "Luna",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    val suggestions = if (isGerman) {
                        listOf(
                            "Taschenlampe einschalten",
                            "Akkustand prüfen",
                            "KI-News im Web suchen"
                        )
                    } else {
                        listOf(
                            "Turn on the flashlight",
                            "Check battery status",
                            "Search web for AI news"
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        suggestions.forEach { prompt ->
                            SuggestionChip(
                                onClick = { LunaAssistantController.submitTextFromOverlay(context, prompt) },
                                label = { Text(prompt) },
                                shape = RoundedCornerShape(50)
                            )
                        }
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
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(items = messages, key = { it.id }) { msg ->
                    ChatMessageBubble(msg = msg)
                }
            }
        }

        // Clean Gemini-Style Bottom Pill Composer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = {
                    if (onRequestMicPermission()) {
                        if (uiState.phase == AssistantOverlayPhase.LISTENING) {
                            LunaAssistantController.finishListeningAndProcess(context)
                        } else {
                            LunaAssistantController.startVoiceListening(context)
                        }
                    }
                },
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(
                        if (uiState.phase == AssistantOverlayPhase.LISTENING) {
                            MaterialTheme.colorScheme.error
                        } else {
                            Color(0xFF2B5CAD)
                        }
                    )
            ) {
                Icon(
                    imageVector = if (uiState.phase == AssistantOverlayPhase.LISTENING) {
                        Icons.Default.Stop
                    } else {
                        Icons.Default.Mic
                    },
                    contentDescription = "Voice",
                    tint = Color.White
                )
            }

            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = {
                    Text(
                        text = if (isGerman) "Frage Luna..." else "Ask Luna...",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(50),
                singleLine = true
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
                modifier = Modifier.size(46.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send"
                )
            }
        }
    }

    if (showHistorySheet) {
        ModalBottomSheet(onDismissRequest = { showHistorySheet = false }) {
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
                        text = if (isGerman) "Chatverlauf" else "Chat History",
                        style = MaterialTheme.typography.titleMedium,
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
                            Text(if (isGerman) "Alle löschen" else "Clear All")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (sessions.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isGerman) "Keine Chats vorhanden" else "No saved chats",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp),
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
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (isUser) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (isUser) 20.dp else 6.dp,
                bottomEnd = if (isUser) 6.dp else 20.dp
            ),
            modifier = Modifier.widthIn(max = 310.dp)
        ) {
            Text(
                text = msg.content,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
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
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onSelect() },
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = dateFormat.format(Date(session.updatedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
