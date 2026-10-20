/*
 * Luna — Native Android Digital Assistant
 * Copyright (C) 2026 ultrapg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.ultrapg.luna.assistant

import android.content.Context
import android.os.SystemClock
import android.os.Vibrator
import android.util.Log
import com.ultrapg.luna.core.database.ChatMessageEntity
import com.ultrapg.luna.core.database.ChatSessionEntity
import com.ultrapg.luna.core.database.LunaDatabase
import com.ultrapg.luna.core.llm.OpenAiAgentEngine
import com.ultrapg.luna.core.settings.LunaSettingsRepository
import com.ultrapg.luna.core.speech.LunaAudioRecorder
import com.ultrapg.luna.core.speech.LunaSpeechPipeline
import com.ultrapg.luna.core.speech.LunaTtsEngine
import com.ultrapg.luna.overlay.LunaOverlayWindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AssistantOverlayPhase {
    IDLE_NOTCH,
    LISTENING,
    TRANSCRIBING,
    THINKING,
    SPEAKING,
    RESULT_CARD
}

data class AssistantLiveState(
    val isVisible: Boolean = false,
    val isDismissing: Boolean = false,
    val isExpanded: Boolean = false,
    val phase: AssistantOverlayPhase = AssistantOverlayPhase.IDLE_NOTCH,
    val activeSessionId: Long? = null,
    val userTranscript: String = "",
    val assistantReply: String = "",
    val statusBadge: String = "",
    val executedTools: List<String> = emptyList(),
    val detectedLanguage: String = "en"
)

/**
 * Central coordinator that connects:
 * - Side-button Digital Assistant triggers (`VoiceInteractionSession`, `AssistTrampolineActivity`, `AccessibilityService`)
 * - Local audio recording + 3s silence auto-send (`LunaAudioRecorder`)
 * - On-device Desert Ant Labs `Ear` + `Voz` STT (`LunaSpeechPipeline`)
 * - OpenAI-compatible Agent Loop + Android Tools + DuckDuckGo + MCP (`OpenAiAgentEngine`)
 * - Fast local neural Female TTS (`LunaTtsEngine`)
 * - Persistent Room Chat Sessions (`LunaDatabase`)
 */
object LunaAssistantController {
    private const val TAG = "LunaAssistantController"

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    val audioRecorder = LunaAudioRecorder()

    private val _liveState = MutableStateFlow(AssistantLiveState())
    val liveState: StateFlow<AssistantLiveState> = _liveState.asStateFlow()

    private var activeTurnJob: Job? = null
    private var dismissAnimationJob: Job? = null

    @Volatile
    private var lastExternalActivityLaunchMs: Long = 0L

    /**
     * Called when an Android action tool (`open_app`, `open_system_settings`, `set_alarm_or_timer`)
     * starts an external Activity so the bubble overlay stays visible across the activity transition
     * and finishes speaking its response.
     */
    fun notifyExternalActivityLaunched(context: Context) {
        val appContext = context.applicationContext
        lastExternalActivityLaunchMs = SystemClock.elapsedRealtime()
        scope.launch {
            for (waitMs in longArrayOf(100L, 280L, 550L)) {
                delay(waitMs)
                if (_liveState.value.isVisible && !_liveState.value.isDismissing) {
                    if (LunaOverlayWindowManager.canShowSystemOverlay(appContext)) {
                        LunaOverlayWindowManager.ensureOverlayAttached(appContext)
                        LunaOverlayWindowManager.syncWindowPosition(appContext)
                    } else {
                        LunaVoiceInteractionSession.ensureSessionShownForActiveTurn()
                    }
                }
            }
        }
    }

    /**
     * Returns true during the brief window (~800ms) right after Luna launches an external app
     * so synthetic `ACTION_OUTSIDE` focus events from Android's activity transition don't prematurely
     * dismiss the bubble while Luna still has something to say.
     */
    fun shouldIgnoreOutsideTouch(): Boolean {
        val elapsed = SystemClock.elapsedRealtime() - lastExternalActivityLaunchMs
        return elapsed in 0L..800L
    }

    fun selectSession(sessionId: Long?) {
        _liveState.value = _liveState.value.copy(activeSessionId = sessionId)
    }

    fun startNewSession() {
        _liveState.value = _liveState.value.copy(
            activeSessionId = null,
            userTranscript = "",
            assistantReply = "",
            executedTools = emptyList()
        )
    }

    fun deleteSession(context: Context, sessionId: Long) {
        val appContext = context.applicationContext
        scope.launch {
            LunaDatabase.getInstance(appContext).chatDao().deleteSession(sessionId)
            if (_liveState.value.activeSessionId == sessionId) {
                _liveState.value = _liveState.value.copy(activeSessionId = null)
            }
        }
    }

    fun cancelCurrentOperation(context: Context) {
        val appContext = context.applicationContext
        activeTurnJob?.cancel()
        activeTurnJob = null
        scope.launch { audioRecorder.stopRecording() }
        LunaTtsEngine.getInstance(appContext).stopSpeaking()
        _liveState.value = _liveState.value.copy(
            phase = AssistantOverlayPhase.IDLE_NOTCH,
            statusBadge = ""
        )
    }

    fun triggerFromSideButton(context: Context, startListeningImmediately: Boolean = true) {
        val appContext = context.applicationContext
        LunaSettingsRepository.init(appContext)
        hapticPulse(appContext)

        dismissAnimationJob?.cancel()
        dismissAnimationJob = null

        val current = _liveState.value
        if (current.isVisible && !current.isDismissing && current.phase == AssistantOverlayPhase.LISTENING) {
            finishListeningAndProcess(appContext)
            return
        }

        LunaTtsEngine.getInstance(appContext).stopSpeaking()
        _liveState.value = current.copy(
            isVisible = true,
            isDismissing = false,
            isExpanded = true,
            phase = if (startListeningImmediately) AssistantOverlayPhase.LISTENING else AssistantOverlayPhase.IDLE_NOTCH,
            userTranscript = "",
            assistantReply = "",
            statusBadge = "",
            executedTools = emptyList()
        )

        if (startListeningImmediately) {
            startVoiceListening(appContext, showOverlay = true)
        }
    }

    fun showCollapsedNotch(context: Context) {
        val appContext = context.applicationContext
        LunaSettingsRepository.init(appContext)
        dismissAnimationJob?.cancel()
        dismissAnimationJob = null
        _liveState.value = _liveState.value.copy(
            isVisible = true,
            isDismissing = false,
            isExpanded = false,
            phase = AssistantOverlayPhase.IDLE_NOTCH
        )
    }

    /**
     * Stops all recording, thinking, and TTS playback immediately, and plays the smooth
     * Material 3 fade-away + edge-retract animation before detaching the overlay window.
     */
    fun dismissOverlay(context: Context) {
        val appContext = context.applicationContext
        activeTurnJob?.cancel()
        activeTurnJob = null
        scope.launch {
            audioRecorder.stopRecording()
        }
        LunaTtsEngine.getInstance(appContext).stopSpeaking()
        val keepDock = LunaSettingsRepository.current(appContext).bubbleKeepAliveDock
        if (keepDock) {
            dismissAnimationJob?.cancel()
            _liveState.value = _liveState.value.copy(
                isVisible = true,
                isDismissing = false,
                isExpanded = false,
                phase = AssistantOverlayPhase.IDLE_NOTCH,
                statusBadge = ""
            )
            return
        }

        if (!_liveState.value.isVisible) {
            LunaOverlayWindowManager.detachOverlay()
            return
        }

        _liveState.value = _liveState.value.copy(
            isVisible = true,
            isDismissing = true,
            isExpanded = false,
            phase = AssistantOverlayPhase.IDLE_NOTCH,
            statusBadge = ""
        )
        dismissAnimationJob?.cancel()
        dismissAnimationJob = scope.launch {
            delay(280L)
            _liveState.value = _liveState.value.copy(
                isVisible = false,
                isDismissing = false,
                isExpanded = false,
                phase = AssistantOverlayPhase.IDLE_NOTCH,
                statusBadge = ""
            )
            LunaOverlayWindowManager.detachOverlay()
        }
    }

    fun toggleListeningFromPill(context: Context) {
        val appContext = context.applicationContext
        val current = _liveState.value
        when (current.phase) {
            AssistantOverlayPhase.LISTENING -> finishListeningAndProcess(appContext)
            AssistantOverlayPhase.SPEAKING -> {
                dismissOverlay(appContext)
            }
            AssistantOverlayPhase.THINKING, AssistantOverlayPhase.TRANSCRIBING -> {
                dismissOverlay(appContext)
            }
            else -> {
                _liveState.value = current.copy(
                    isVisible = true,
                    isExpanded = true,
                    userTranscript = "",
                    assistantReply = "",
                    executedTools = emptyList()
                )
                startVoiceListening(appContext, showOverlay = true)
            }
        }
    }

    fun startVoiceListening(context: Context, showOverlay: Boolean = true) {
        val appContext = context.applicationContext
        val settings = LunaSettingsRepository.current(appContext)
        val isDe = settings.appLanguage.isGerman

        LunaTtsEngine.getInstance(appContext).stopSpeaking()
        LunaSpeechPipeline.getInstance(appContext).warmUpAsync()

        activeTurnJob?.cancel()
        activeTurnJob = scope.launch {
            _liveState.value = _liveState.value.copy(
                isVisible = showOverlay || _liveState.value.isVisible,
                isExpanded = showOverlay || _liveState.value.isExpanded,
                phase = AssistantOverlayPhase.LISTENING,
                statusBadge = if (isDe) "Höre zu..." else "Listening..."
            )

            val started = audioRecorder.startRecording(
                scope = scope,
                autoStopOnSilence = settings.autoSilenceStop,
                silenceTimeoutMs = settings.silenceTimeoutMs,
                onAutoSilenceDetected = {
                    scope.launch {
                        finishListeningAndProcess(appContext)
                    }
                }
            )

            if (!started) {
                _liveState.value = _liveState.value.copy(
                    phase = AssistantOverlayPhase.RESULT_CARD,
                    assistantReply = if (isDe) {
                        "Mikrofon konnte nicht gestartet werden. Bitte Mikrofonberechtigung in Luna prüfen."
                    } else {
                        "Could not start microphone. Please grant microphone permission in Luna."
                    },
                    statusBadge = ""
                )
                delay(1500)
                if (_liveState.value.isVisible) {
                    dismissOverlay(appContext)
                }
            }
        }
    }

    fun finishListeningAndProcess(context: Context) {
        val appContext = context.applicationContext
        val settings = LunaSettingsRepository.current(appContext)
        val isDe = settings.appLanguage.isGerman

        activeTurnJob?.cancel()
        activeTurnJob = scope.launch {
            val samples = audioRecorder.stopRecording()
            if (samples.isEmpty()) {
                dismissOverlay(appContext)
                return@launch
            }

            _liveState.value = _liveState.value.copy(
                phase = AssistantOverlayPhase.TRANSCRIBING,
                statusBadge = if (isDe) "Transkribiere lokal (Ear + Voz)..." else "Transcribing locally (Ear + Voz)..."
            )

            val sttResult = try {
                LunaSpeechPipeline.getInstance(appContext).transcribe(samples) { stage ->
                    _liveState.value = _liveState.value.copy(
                        statusBadge = stage
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Transcription error: ${t.message}", t)
                _liveState.value = _liveState.value.copy(
                    phase = AssistantOverlayPhase.RESULT_CARD,
                    assistantReply = t.message ?: "Speech transcription failed.",
                    statusBadge = ""
                )
                delay(1200)
                if (_liveState.value.isVisible) {
                    dismissOverlay(appContext)
                }
                return@launch
            }

            if (sttResult.text.isBlank()) {
                dismissOverlay(appContext)
                return@launch
            }

            _liveState.value = _liveState.value.copy(
                userTranscript = sttResult.text,
                detectedLanguage = sttResult.detectedLanguage,
                phase = AssistantOverlayPhase.THINKING,
                statusBadge = if (isDe) "Luna denkt nach..." else "Luna is thinking..."
            )

            submitPromptInternal(
                appContext = appContext,
                promptText = sttResult.text,
                detectedLanguage = sttResult.detectedLanguage
            )
        }
    }

    /**
     * Submits a text prompt from the main app's ChatsScreen without opening the floating side-notch overlay.
     */
    fun submitTextFromChat(context: Context, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val appContext = context.applicationContext
        val settings = LunaSettingsRepository.current(appContext)
        val isDe = settings.appLanguage.isGerman

        activeTurnJob?.cancel()
        activeTurnJob = scope.launch {
            audioRecorder.stopRecording()
            LunaTtsEngine.getInstance(appContext).stopSpeaking()
            _liveState.value = _liveState.value.copy(
                userTranscript = clean,
                assistantReply = "",
                phase = AssistantOverlayPhase.THINKING,
                statusBadge = if (isDe) "Luna denkt nach..." else "Luna is thinking..."
            )
            submitPromptInternal(
                appContext = appContext,
                promptText = clean,
                detectedLanguage = settings.appLanguage.resolveLanguageCode()
            )
        }
    }

    fun submitTextFromOverlay(context: Context, text: String) {
        submitTextFromChat(context, text)
    }

    private suspend fun submitPromptInternal(
        appContext: Context,
        promptText: String,
        detectedLanguage: String
    ) {
        val db = LunaDatabase.getInstance(appContext)
        val chatDao = db.chatDao()
        val settings = LunaSettingsRepository.current(appContext)

        val sessionId = _liveState.value.activeSessionId ?: run {
            val newId = chatDao.insertSession(
                ChatSessionEntity(
                    title = promptText.take(48),
                    modelUsed = settings.openAiModel,
                    languageCode = detectedLanguage
                )
            )
            _liveState.value = _liveState.value.copy(activeSessionId = newId)
            newId
        }

        val historyBefore = chatDao.getMessagesForSession(sessionId)
        chatDao.insertMessage(
            ChatMessageEntity(
                sessionId = sessionId,
                role = "user",
                content = promptText
            )
        )

        val result = try {
            OpenAiAgentEngine.runAgentTurn(
                context = appContext,
                conversationHistory = historyBefore,
                userPrompt = promptText,
                detectedLanguage = detectedLanguage,
                onToolStep = { step ->
                    _liveState.value = _liveState.value.copy(
                        statusBadge = step.statusText
                    )
                }
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Agent turn failed: ${t.message}", t)
            val errReply = t.message ?: "Error communicating with OpenAI API."
            chatDao.insertMessage(
                ChatMessageEntity(
                    sessionId = sessionId,
                    role = "assistant",
                    content = errReply
                )
            )
            _liveState.value = _liveState.value.copy(
                phase = AssistantOverlayPhase.IDLE_NOTCH,
                assistantReply = errReply,
                statusBadge = ""
            )
            if (_liveState.value.isVisible) {
                delay(800)
                dismissOverlay(appContext)
            }
            return
        }

        chatDao.insertMessage(
            ChatMessageEntity(
                sessionId = sessionId,
                role = "assistant",
                content = result.replyText,
                toolCallsSummary = result.executedToolsSummary.joinToString(", ")
            )
        )
        chatDao.updateSessionMetadata(
            sessionId = sessionId,
            title = historyBefore.firstOrNull()?.content?.take(48) ?: promptText.take(48),
            updatedAt = System.currentTimeMillis(),
            model = result.modelUsed
        )

        val ttsEngine = LunaTtsEngine.getInstance(appContext)
        if (settings.ttsEnabled) {
            _liveState.value = _liveState.value.copy(
                phase = AssistantOverlayPhase.SPEAKING,
                assistantReply = result.replyText,
                executedTools = result.executedToolsSummary,
                statusBadge = ""
            )
            // Suspend until the entire TTS synthesis & playback completes
            ttsEngine.speakAndAwait(result.replyText, detectedLanguage)
            _liveState.value = _liveState.value.copy(
                phase = AssistantOverlayPhase.IDLE_NOTCH,
                statusBadge = ""
            )
            if (_liveState.value.isVisible) {
                // Natural post-speech pause before smoothly fading away into the side edge
                delay(1050L)
                dismissOverlay(appContext)
            }
        } else {
            _liveState.value = _liveState.value.copy(
                phase = AssistantOverlayPhase.IDLE_NOTCH,
                assistantReply = result.replyText,
                executedTools = result.executedToolsSummary,
                statusBadge = ""
            )
            if (_liveState.value.isVisible) {
                delay(900L)
                dismissOverlay(appContext)
            }
        }
    }

    private fun hapticPulse(context: Context) {
        runCatching {
            val vibrator = context.getSystemService(Vibrator::class.java)
            vibrator?.vibrate(android.os.VibrationEffect.createOneShot(24L, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
