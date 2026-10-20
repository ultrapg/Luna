package com.ultrapg.luna.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ultrapg.luna.assistant.AssistantLiveState
import com.ultrapg.luna.assistant.AssistantOverlayPhase
import com.ultrapg.luna.assistant.LunaAssistantController
import com.ultrapg.luna.core.database.ChatMessageEntity
import com.ultrapg.luna.core.database.ChatSessionEntity
import com.ultrapg.luna.core.database.LunaDatabase
import com.ultrapg.luna.core.llm.ToolExecutionCodec
import com.ultrapg.luna.core.llm.ToolExecutionRecord
import com.ultrapg.luna.core.settings.LunaSettingsRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    onRequestMicPermission: () -> Boolean
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val db = remember { LunaDatabase.getInstance(context) }
    val settings by LunaSettingsRepository.settingsFlow.collectAsState()
    val uiState by LunaAssistantController.liveState.collectAsState()

    val sessions by db.chatDao().observeAllSessions().collectAsState(initial = emptyList())
    var didInitialAutoSelect by rememberSaveable { mutableStateOf(false) }

    // Only auto-select the latest session once on cold launch if no session was explicitly chosen
    LaunchedEffect(sessions) {
        if (!didInitialAutoSelect && sessions.isNotEmpty()) {
            didInitialAutoSelect = true
            if (uiState.activeSessionId == null) {
                LunaAssistantController.selectSession(sessions.first().id)
            }
        }
    }

    val selectedSessionId = uiState.activeSessionId
    val currentSession = sessions.firstOrNull { it.id == selectedSessionId }

    val messagesFlow = remember(selectedSessionId) {
        if (selectedSessionId != null) {
            db.chatDao().observeMessagesForSession(selectedSessionId)
        } else {
            flowOf(emptyList())
        }
    }
    val messages by messagesFlow.collectAsState(initial = emptyList())

    var showHistorySheet by remember { mutableStateOf(false) }
    var inputText by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val isGerman = settings.appLanguage.isGerman

    LaunchedEffect(messages.size, selectedSessionId, uiState.activeTurnSteps.size) {
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
                        text = currentSession?.title
                            ?: if (isGerman) "Neuer Chat" else "New Chat",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val modelLabel = settings.openAiModel.ifBlank {
                        if (isGerman) "Kein Modell" else "No model"
                    }
                    Text(
                        text = "$modelLabel • ${settings.hardwareAccelerator.shortLabel}",
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

                if (selectedSessionId != null) {
                    IconButton(
                        onClick = {
                            LunaAssistantController.deleteSession(context, selectedSessionId)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete Current Chat",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                FilledTonalIconButton(
                    onClick = {
                        didInitialAutoSelect = true
                        inputText = ""
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

        // Quick Horizontal Session Switcher Bar
        if (sessions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = selectedSessionId == null,
                    onClick = {
                        didInitialAutoSelect = true
                        inputText = ""
                        LunaAssistantController.startNewSession()
                    },
                    label = {
                        Text(
                            text = if (isGerman) "+ Neuer Chat" else "+ New Chat",
                            maxLines = 1
                        )
                    },
                    shape = RoundedCornerShape(50)
                )

                sessions.take(10).forEach { session ->
                    FilterChip(
                        selected = session.id == selectedSessionId,
                        onClick = {
                            didInitialAutoSelect = true
                            LunaAssistantController.selectSession(session.id)
                        },
                        label = {
                            Text(
                                text = session.title.take(24),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        shape = RoundedCornerShape(50)
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

        // Redesigned Live Multi-Step Agent Execution Card when active
        AnimatedVisibility(
            visible = isBusy,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            LiveAgentExecutionBanner(
                uiState = uiState,
                isGerman = isGerman,
                onSendListening = { LunaAssistantController.finishListeningAndProcess(context) },
                onCancel = { LunaAssistantController.cancelCurrentOperation(context) }
            )
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
                        text = if (isGerman) "Neuer Chat mit Luna" else "New Chat with Luna",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )

                    val suggestions = if (isGerman) {
                        listOf(
                            "Taschenlampe einschalten & Akku prüfen",
                            "Suche aktuelle KI-News im Web",
                            "Öffne Einstellungen"
                        )
                    } else {
                        listOf(
                            "Turn on flashlight & check battery",
                            "Search the web for latest AI news",
                            "Open system settings"
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        suggestions.forEach { prompt ->
                            SuggestionChip(
                                onClick = {
                                    didInitialAutoSelect = true
                                    LunaAssistantController.submitTextFromChat(context, prompt)
                                },
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
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(items = messages, key = { it.id }) { msg ->
                    ChatMessageBubble(
                        msg = msg,
                        isGerman = isGerman
                    )
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
                        didInitialAutoSelect = true
                        if (uiState.phase == AssistantOverlayPhase.LISTENING) {
                            LunaAssistantController.finishListeningAndProcess(context)
                        } else {
                            LunaAssistantController.startVoiceListening(context, showOverlay = false)
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
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        val trimmed = inputText.trim()
                        if (trimmed.isNotEmpty() && !isBusy) {
                            didInitialAutoSelect = true
                            inputText = ""
                            focusManager.clearFocus()
                            LunaAssistantController.submitTextFromChat(context, trimmed)
                        }
                    }
                )
            )

            FilledIconButton(
                onClick = {
                    val trimmed = inputText.trim()
                    if (trimmed.isNotEmpty()) {
                        didInitialAutoSelect = true
                        inputText = ""
                        focusManager.clearFocus()
                        LunaAssistantController.submitTextFromChat(context, trimmed)
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                didInitialAutoSelect = true
                                inputText = ""
                                LunaAssistantController.startNewSession()
                                showHistorySheet = false
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(50)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (isGerman) "Neu" else "New", maxLines = 1)
                        }
                        if (sessions.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        didInitialAutoSelect = true
                                        db.chatDao().deleteAllSessions()
                                        LunaAssistantController.startNewSession()
                                        showHistorySheet = false
                                    }
                                }
                            ) {
                                Text(if (isGerman) "Alle löschen" else "Clear All")
                            }
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
                                    didInitialAutoSelect = true
                                    LunaAssistantController.selectSession(session.id)
                                    showHistorySheet = false
                                },
                                onDelete = {
                                    LunaAssistantController.deleteSession(context, session.id)
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

/**
 * Live Multi-Step Agent Execution Card shown in the chat while Luna is listening, transcribing,
 * thinking, or executing multi-step tools.
 */
@Composable
private fun LiveAgentExecutionBanner(
    uiState: AssistantLiveState,
    isGerman: Boolean,
    onSendListening: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        color = Color(0xFF131722),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .animateContentSize()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.2.dp,
                        color = when (uiState.phase) {
                            AssistantOverlayPhase.SPEAKING -> Color(0xFF64B5F6)
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when (uiState.phase) {
                                AssistantOverlayPhase.LISTENING -> if (isGerman) "Höre zu..." else "Listening..."
                                AssistantOverlayPhase.TRANSCRIBING -> if (isGerman) "Transkribiere lokal..." else "Transcribing locally..."
                                AssistantOverlayPhase.THINKING -> uiState.statusBadge.ifBlank {
                                    if (isGerman) "Luna denkt nach & plant Schritte..." else "Luna is thinking & planning steps..."
                                }
                                AssistantOverlayPhase.SPEAKING -> if (isGerman) "Luna spricht..." else "Luna is speaking..."
                                else -> ""
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (uiState.activeTurnSteps.isNotEmpty()) {
                            val doneCount = uiState.activeTurnSteps.count { it.status == "DONE" }
                            val totalCount = uiState.activeTurnSteps.size
                            Text(
                                text = if (isGerman) {
                                    "Mehrstufige Ausführung: $doneCount/$totalCount Schritte"
                                } else {
                                    "Multi-step execution: $doneCount/$totalCount steps"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF90CAF9)
                            )
                        }
                    }
                }

                if (uiState.phase == AssistantOverlayPhase.LISTENING) {
                    FilledTonalButton(
                        onClick = onSendListening,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(if (isGerman) "Senden" else "Send", style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White.copy(alpha = 0.8f))
                    }
                }
            }

            // Live Thinking Stream Preview
            if (uiState.activeThinkingText.isNotBlank()) {
                Surface(
                    color = Color(0xFF1D2233),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFFB388FF).copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Psychology,
                            contentDescription = null,
                            tint = Color(0xFFB388FF),
                            modifier = Modifier.size(16.dp).padding(top = 2.dp)
                        )
                        Text(
                            text = uiState.activeThinkingText.lines().lastOrNull { it.isNotBlank() }
                                ?: uiState.activeThinkingText.takeLast(140),
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            color = Color(0xFFE1BEE7),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Live Step-by-Step Progress List
            if (uiState.activeTurnSteps.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    uiState.activeTurnSteps.forEach { stepRec ->
                        val isRunning = stepRec.status == "RUNNING"
                        val catColor = accentColorForCategory(stepRec.category)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF1B2030))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isRunning) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(13.dp),
                                        strokeWidth = 1.8.dp,
                                        color = catColor
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (stepRec.status == "ERROR") Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = if (stepRec.status == "ERROR") MaterialTheme.colorScheme.error else catColor,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                                Text(
                                    text = stepRec.displayTitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                CategoryBadgePill(category = stepRec.category)
                                if (!isRunning && stepRec.durationMs > 0L) {
                                    Text(
                                        text = "${stepRec.durationMs} ms",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.6f)
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

/**
 * Redesigned Chat Message Bubble with:
 * 1. Dedicated Collapsible Thinking / Reasoning Card (`ThinkingTraceCard`)
 * 2. Multi-Step Tool Execution Bar + Interactive Timeline Inspector (`ToolExecutionSection`)
 * 3. Spoken Response Bubble
 */
@Composable
private fun ChatMessageBubble(
    msg: ChatMessageEntity,
    isGerman: Boolean
) {
    val isUser = msg.role == "user"
    val allRecords = remember(msg.toolCallsSummary, isGerman) {
        ToolExecutionCodec.decode(msg.toolCallsSummary, isGerman)
    }

    val thinkingRecord = remember(allRecords) {
        allRecords.firstOrNull {
            it.category == "THINKING" ||
                it.toolName.equals("thinking", ignoreCase = true) ||
                it.toolName.equals("thought", ignoreCase = true) ||
                it.toolName.equals("gedacht", ignoreCase = true)
        }
    }
    val actionToolRecords = remember(allRecords, thinkingRecord) {
        allRecords.filter { it != thinkingRecord }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 1. Dedicated Collapsible Thinking / Reasoning Card
        if (thinkingRecord != null) {
            ThinkingTraceCard(
                record = thinkingRecord,
                isGerman = isGerman
            )
        }

        // 2. Redesigned Multi-Step Tool Execution Bar & Inspector
        if (actionToolRecords.isNotEmpty()) {
            ToolExecutionSection(
                records = actionToolRecords,
                isGerman = isGerman
            )
        }

        // 3. Main Message Bubble
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
            modifier = Modifier.widthIn(max = 335.dp)
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

/**
 * Dedicated Material 3 Collapsible Reasoning / Thinking Card for models that emit `<think>` or
 * `reasoning_content`.
 */
@Composable
private fun ThinkingTraceCard(
    record: ToolExecutionRecord,
    isGerman: Boolean
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val accentPurple = Color(0xFFB388FF)

    Surface(
        color = Color(0xFF1A1726),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, accentPurple.copy(alpha = if (expanded) 0.55f else 0.28f)),
        modifier = Modifier
            .widthIn(max = 345.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable { expanded = !expanded }
            .animateContentSize()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(accentPurple.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Psychology,
                            contentDescription = null,
                            tint = accentPurple,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = if (isGerman) "Gedankengang" else "Model Reasoning",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFEDE7F6)
                            )
                            if (record.durationMs > 0L) {
                                Text(
                                    text = "• ${formatDurationShort(record.durationMs)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = accentPurple.copy(alpha = 0.85f)
                                )
                            }
                        }
                        if (!expanded && record.resultSummary.isNotBlank()) {
                            Text(
                                text = record.resultSummary.replace("\n", " ").trim(),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFD1C4E9).copy(alpha = 0.78f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = accentPurple,
                    modifier = Modifier.size(18.dp)
                )
            }

            if (expanded) {
                HorizontalDivider(color = accentPurple.copy(alpha = 0.22f))
                if (record.whatItDid.isNotBlank()) {
                    Text(
                        text = record.whatItDid,
                        style = MaterialTheme.typography.labelSmall,
                        color = accentPurple
                    )
                }
                Text(
                    text = record.resultSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFEDE7F6),
                    lineHeight = 18.sp
                )
            }
        }
    }
}

/**
 * Redesigned Multi-Step Tool Execution Bar & Interactive Timeline Inspector.
 * Users can tap any individual tool step chip OR tap the Multi-Step summary badge to inspect
 * all steps in a vertical timeline with custom result cards.
 */
@Composable
private fun ToolExecutionSection(
    records: List<ToolExecutionRecord>,
    isGerman: Boolean
) {
    var selectedRecord by remember { mutableStateOf<ToolExecutionRecord?>(null) }
    var showFullTimeline by remember { mutableStateOf(false) }

    val totalDurationMs = remember(records) { records.sumOf { it.durationMs } }

    Column(
        modifier = Modifier.widthIn(max = 350.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Horizontal interactive pill bar
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Multi-step timeline toggle chip when more than 1 tool was used (or as a quick summary header)
            if (records.size > 1) {
                Surface(
                    color = if (showFullTimeline) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        Color(0xFF171C28)
                    },
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(
                        1.dp,
                        if (showFullTimeline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable {
                            showFullTimeline = !showFullTimeline
                            if (showFullTimeline) selectedRecord = null
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountTree,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (isGerman) {
                                "${records.size} Schritte"
                            } else {
                                "${records.size} Steps"
                            } + if (totalDurationMs > 0L) " • ${formatDurationShort(totalDurationMs)}" else "",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (showFullTimeline) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                Color.White
                            }
                        )
                        Icon(
                            imageVector = if (showFullTimeline) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Individual Tool Step Pills
            records.forEachIndexed { idx, record ->
                val isSelected = !showFullTimeline && selectedRecord == record
                val accent = accentColorForCategory(record.category)

                Surface(
                    color = if (isSelected) {
                        accent.copy(alpha = 0.22f)
                    } else {
                        Color(0xFF171C28)
                    },
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) accent else accent.copy(alpha = 0.38f)
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable {
                            showFullTimeline = false
                            selectedRecord = if (isSelected) null else record
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (records.size > 1) {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(accent.copy(alpha = 0.25f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${idx + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = accent
                                )
                            }
                        }
                        Icon(
                            imageVector = iconForToolName(record.toolName, record.category),
                            contentDescription = record.displayTitle,
                            modifier = Modifier.size(14.dp),
                            tint = accent
                        )
                        Text(
                            text = record.displayTitle,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Icon(
                            imageVector = if (isSelected) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        // Expandable Inspector Panel (either full multi-step timeline or single clicked tool)
        val recordsToDisplay = when {
            showFullTimeline -> records
            selectedRecord != null -> listOf(selectedRecord!!)
            else -> emptyList()
        }

        AnimatedVisibility(
            visible = recordsToDisplay.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Surface(
                color = Color(0xFF141824),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                tonalElevation = 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Inspector Header Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (showFullTimeline) {
                                    if (isGerman) "Mehrstufiger Werkzeug-Verlauf (${records.size})" else "Multi-Step Tool Timeline (${records.size})"
                                } else {
                                    if (isGerman) "Werkzeug-Details" else "Tool Execution Inspector"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (records.size > 1 && !showFullTimeline) {
                                TextButton(
                                    onClick = {
                                        showFullTimeline = true
                                        selectedRecord = null
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(26.dp)
                                ) {
                                    Text(
                                        text = if (isGerman) "Alle zeigen" else "Show All",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    showFullTimeline = false
                                    selectedRecord = null
                                },
                                modifier = Modifier.size(22.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }

                    recordsToDisplay.forEachIndexed { idx, rec ->
                        ToolStepDetailCard(
                            record = rec,
                            stepNumber = records.indexOf(rec) + 1,
                            totalSteps = records.size,
                            isGerman = isGerman
                        )
                        if (idx < recordsToDisplay.size - 1) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Rich Detail Card for a single tool execution step showing:
 * - Step badge, Category pill (`ANDROID`, `WEB`, `MEMORY`, `API MCP`, `MCP`), and latency (`ms`)
 * - What it did + formatted parameters
 * - Specialized rich result views (clickable DuckDuckGo search cards, website reader card, device telemetry, etc.)
 */
@Composable
private fun ToolStepDetailCard(
    record: ToolExecutionRecord,
    stepNumber: Int,
    totalSteps: Int,
    isGerman: Boolean
) {
    val context = LocalContext.current
    val accent = accentColorForCategory(record.category)
    var showRawJson by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Step Title & Metadata Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = iconForToolName(record.toolName, record.category),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(14.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (totalSteps > 1) {
                            Text(
                                text = if (isGerman) "Schritt $stepNumber" else "Step $stepNumber",
                                style = MaterialTheme.typography.labelSmall,
                                color = accent,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = record.displayTitle,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CategoryBadgePill(category = record.category)
                if (record.durationMs > 0L) {
                    Text(
                        text = formatDurationShort(record.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.65f)
                    )
                }
            }
        }

        // "What it did" section
        if (record.whatItDid.isNotBlank()) {
            Surface(
                color = Color(0xFF1B2030),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isGerman) "Aktion (Was gemacht wurde)" else "Action (What it did)",
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (record.rawArgumentsJson.isNotBlank() && record.rawArgumentsJson != "{}") {
                            Text(
                                text = if (showRawJson) {
                                    if (isGerman) "JSON ausblenden" else "Hide JSON"
                                } else {
                                    if (isGerman) "Parameter" else "Params"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { showRawJson = !showRawJson }
                            )
                        }
                    }
                    Text(
                        text = record.whatItDid,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.92f)
                    )
                    if (showRawJson && record.rawArgumentsJson.isNotBlank()) {
                        Text(
                            text = record.rawArgumentsJson,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF90CAF9),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }

        // Specialized Rich Result View depending on tool type
        val lowerTool = record.toolName.trim().lowercase(Locale.ROOT)
        val parsedJson = remember(record.rawResultJson) {
            runCatching {
                if (record.rawResultJson.trim().startsWith("{")) JSONObject(record.rawResultJson) else null
            }.getOrNull()
        }

        when {
            lowerTool == "search_duckduckgo" && parsedJson != null -> {
                DuckDuckGoResultCards(
                    resultJson = parsedJson,
                    isGerman = isGerman,
                    onOpenUrl = { url ->
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        }
                    }
                )
            }
            lowerTool == "read_website" && parsedJson != null -> {
                WebsiteReaderResultCard(
                    resultJson = parsedJson,
                    isGerman = isGerman,
                    onOpenUrl = { url ->
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        }
                    }
                )
            }
            lowerTool == "get_device_status" && parsedJson != null -> {
                DeviceStatusTelemetryChips(
                    resultJson = parsedJson,
                    isGerman = isGerman
                )
            }
            record.resultSummary.isNotBlank() -> {
                Surface(
                    color = Color(0xFF1B2030),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = if (isGerman) "Ergebnis" else "Result",
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = record.resultSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.85f),
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DuckDuckGoResultCards(
    resultJson: JSONObject,
    isGerman: Boolean,
    onOpenUrl: (String) -> Unit
) {
    val instant = resultJson.optString("instant_answer", "").trim()
    val resultsArr = resultJson.optJSONArray("results")

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = if (isGerman) "Gefundene Web-Ergebnisse" else "Web Search Grounding Results",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF4FC3F7),
            fontWeight = FontWeight.SemiBold
        )

        if (instant.isNotBlank()) {
            Surface(
                color = Color(0xFF162938),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Color(0xFF4FC3F7).copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = if (isGerman) "Direktantwort" else "Instant Answer",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4FC3F7)
                    )
                    Text(
                        text = instant,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White
                    )
                }
            }
        }

        if (resultsArr != null && resultsArr.length() > 0) {
            for (i in 0 until resultsArr.length()) {
                val item = resultsArr.optJSONObject(i) ?: continue
                val title = item.optString("title", "")
                val snippet = item.optString("snippet", "")
                val url = item.optString("url", "")
                val domain = item.optString("domain", "")
                    .ifBlank { runCatching { Uri.parse(url).host?.removePrefix("www.") ?: "" }.getOrDefault("") }

                Surface(
                    color = Color(0xFF1B2030),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = url.isNotBlank()) { onOpenUrl(url) }
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${i + 1}. $title",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF90CAF9),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (domain.isNotBlank()) {
                                Text(
                                    text = domain,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.55f),
                                    modifier = Modifier.padding(start = 6.dp)
                                )
                            }
                        }
                        if (snippet.isNotBlank()) {
                            Text(
                                text = snippet,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.82f),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WebsiteReaderResultCard(
    resultJson: JSONObject,
    isGerman: Boolean,
    onOpenUrl: (String) -> Unit
) {
    val title = resultJson.optString("title", "")
    val url = resultJson.optString("url", "")
    val content = resultJson.optString("content", "").take(900)

    Surface(
        color = Color(0xFF1B2030),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = url.isNotBlank()) { onOpenUrl(url) }
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title.ifBlank { if (isGerman) "Gelesene Webseite" else "Extracted Webpage" },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF4FC3F7),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Default.OpenInNew,
                    contentDescription = null,
                    tint = Color(0xFF4FC3F7),
                    modifier = Modifier.size(14.dp)
                )
            }
            if (content.isNotBlank()) {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun DeviceStatusTelemetryChips(
    resultJson: JSONObject,
    isGerman: Boolean
) {
    val battery = resultJson.optInt("battery_percent", -1)
    val charging = resultJson.optBoolean("is_charging", false)
    val musicVol = resultJson.optInt("music_volume_percent", -1)
    val torch = resultJson.optBoolean("flashlight_on", false)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (battery >= 0) {
            TelemetryMiniChip(
                icon = if (charging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryStd,
                label = if (isGerman) "Akku $battery%" else "Battery $battery%",
                color = Color(0xFF81C784)
            )
        }
        if (musicVol >= 0) {
            TelemetryMiniChip(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                label = if (isGerman) "Medien $musicVol%" else "Media $musicVol%",
                color = Color(0xFF64B5F6)
            )
        }
        TelemetryMiniChip(
            icon = Icons.Default.FlashlightOn,
            label = if (isGerman) "Licht ${if (torch) "EIN" else "AUS"}" else "Torch ${if (torch) "ON" else "OFF"}",
            color = if (torch) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun TelemetryMiniChip(
    icon: ImageVector,
    label: String,
    color: Color
) {
    Surface(
        color = Color(0xFF1B2030),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color.White)
        }
    }
}

@Composable
private fun CategoryBadgePill(category: String) {
    val accent = accentColorForCategory(category)
    val label = when (category.uppercase(Locale.ROOT)) {
        "ANDROID" -> "ANDROID"
        "WEB" -> "WEB"
        "MEMORY" -> "MEMORY"
        "API_MCP" -> "API MCP"
        "MCP" -> "MCP"
        "THINKING" -> "THINK"
        else -> category.uppercase(Locale.ROOT)
    }
    Surface(
        color = accent.copy(alpha = 0.16f),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(0.8.dp, accent.copy(alpha = 0.45f))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = accent,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

private fun accentColorForCategory(category: String): Color {
    return when (category.uppercase(Locale.ROOT)) {
        "ANDROID" -> Color(0xFF81C784) // Soft Emerald Green
        "WEB" -> Color(0xFF4FC3F7)     // Light Blue
        "MEMORY" -> Color(0xFFFFB74D)  // Warm Amber
        "API_MCP" -> Color(0xFFCE93D8) // Orchid / API MCP Accent
        "MCP" -> Color(0xFF9FA8DA)     // Indigo MCP Accent
        "THINKING" -> Color(0xFFB388FF)
        else -> Color(0xFF90CAF9)
    }
}

private fun formatDurationShort(ms: Long): String {
    return if (ms >= 1000L) {
        String.format(Locale.US, "%.1f s", ms / 1000.0)
    } else {
        "$ms ms"
    }
}

private fun iconForToolName(toolName: String, category: String = ""): ImageVector {
    val lower = toolName.lowercase(Locale.ROOT)
    return when {
        lower == "thinking" || lower == "thought" || lower == "gedacht" || category == "THINKING" -> Icons.Default.Psychology
        lower == "search_duckduckgo" -> Icons.Default.Search
        lower == "read_website" -> Icons.Default.Language
        lower == "set_flashlight" -> Icons.Default.FlashlightOn
        lower == "open_app" -> Icons.Default.OpenInNew
        lower == "open_url_or_deep_link" -> Icons.Default.AdsClick
        lower == "set_volume" -> Icons.AutoMirrored.Filled.VolumeUp
        lower == "set_alarm_or_timer" -> Icons.Default.Alarm
        lower == "get_device_status" -> Icons.Default.BatteryChargingFull
        lower == "open_system_settings" -> Icons.Default.Settings
        lower == "control_media" -> Icons.Default.PlayCircleOutline
        lower == "clipboard_action" -> Icons.Default.ContentPaste
        lower == "save_memory" || lower == "recall_memories" || lower == "forget_memory" -> Icons.Default.Memory
        category == "API_MCP" || lower.startsWith("api_mcp") -> Icons.Default.CloudSync
        category == "MCP" || lower.startsWith("mcp_") -> Icons.Default.Extension
        else -> Icons.Default.BuildCircle
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
