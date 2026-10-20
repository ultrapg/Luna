/*
 * Luna — Native Android Digital Assistant
 * Copyright (C) 2026 ultrapg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.ultrapg.luna.core.speech

import ai.desertant.ear.Ear
import ai.desertant.voz.Voz
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.ultrapg.luna.core.hardware.NpuHardwareManager
import com.ultrapg.luna.core.models.LocalModelId
import com.ultrapg.luna.core.models.LocalModelStorage
import com.ultrapg.luna.core.settings.HardwareAccelerator
import com.ultrapg.luna.core.settings.LunaSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class SttTranscriptionResult(
    val text: String,
    val detectedLanguage: String,
    val durationMs: Long
)

data class SttPipelineStatus(
    val isReady: Boolean = false,
    val isLoading: Boolean = false,
    val activeAcceleratorLabel: String = "NPU Priority",
    val detectedNpuSummary: String = NpuHardwareManager.detectHardwareProfile().summaryLabel,
    val statusText: String = "Ready",
    val lastLatencyMs: Long? = null
)

/**
 * Fully local on-device Speech-to-Text engine powered by Desert Ant Labs `Ear` (spoken language
 * identification) and `Voz` (multilingual Conformer-TDT transcription supporting English, German,
 * and 23 additional European languages), accelerated via Qualcomm Hexagon NPU / NeuroPilot / Tensor
 * or OpenCL GPU / XNNPACK CPU.
 */
class LunaSpeechPipeline private constructor(context: Context) {

    companion object {
        private const val TAG = "LunaSpeechPipeline"
        const val SAMPLE_RATE_HZ = 16_000.0
        private const val SILENCE_THRESHOLD = 0.0035f
        private const val SILENCE_PAD_SAMPLES = 2_560 // 160ms @ 16kHz
        private const val MAX_CHUNK_SAMPLES = 20 * 16_000
        private const val MIN_CHUNK_SAMPLES = 11 * 16_000
        private const val MAX_EAR_SAMPLES = 88_000 // 5.5s @ 16kHz

        val SUPPORTED_LANGUAGES = setOf(
            "bg", "cs", "da", "de", "el",
            "en", "es", "et", "fi", "fr",
            "hr", "hu", "it", "lt", "lv",
            "mt", "nl", "pl", "pt", "ro",
            "ru", "sk", "sl", "sv", "uk"
        )

        @Volatile
        private var instance: LunaSpeechPipeline? = null

        fun getInstance(context: Context): LunaSpeechPipeline {
            return instance ?: synchronized(this) {
                instance ?: LunaSpeechPipeline(context.applicationContext).also { instance = it }
            }
        }
    }

    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var voz: Voz? = null
    private var ear: Ear? = null
    private var appliedAccelerator: HardwareAccelerator? = null

    private val _status = MutableStateFlow(
        SttPipelineStatus(
            detectedNpuSummary = NpuHardwareManager.detectHardwareProfile(appContext).summaryLabel
        )
    )
    val status: StateFlow<SttPipelineStatus> = _status.asStateFlow()

    fun warmUpAsync() {
        scope.launch {
            runCatching { ensureInitialized(warmUp = true) }
        }
    }

    suspend fun ensureInitialized(warmUp: Boolean = true): Boolean = withContext(Dispatchers.Default) {
        mutex.withLock {
            val settings = LunaSettingsRepository.current(appContext)
            if (!LocalModelStorage.isModelDownloaded(appContext, LocalModelId.VOZ)) {
                _status.value = _status.value.copy(
                    isReady = false,
                    isLoading = false,
                    statusText = if (settings.appLanguage.isGerman) {
                        "Voz Sprachmodell noch nicht heruntergeladen"
                    } else {
                        "Voz speech model not downloaded yet"
                    }
                )
                return@withLock false
            }

            val accelChanged = appliedAccelerator != null && appliedAccelerator != settings.hardwareAccelerator
            if (accelChanged) {
                closeAllLocked()
            }

            if (voz != null && !accelChanged) {
                return@withLock true
            }

            _status.value = _status.value.copy(
                isLoading = true,
                statusText = if (settings.appLanguage.isGerman) "Lade Voz & Ear auf NPU/GPU..." else "Loading Voz & Ear on NPU/GPU..."
            )

            val accelDesc = NpuHardwareManager.configureNativeRuntime(
                context = appContext,
                requested = settings.hardwareAccelerator,
                lowMemoryPressure = false
            )
            appliedAccelerator = settings.hardwareAccelerator

            return@withLock try {
                val vozDir = LocalModelStorage.modelRootDir(appContext, LocalModelId.VOZ).absolutePath
                val v = Voz(appContext, vozDir)
                if (warmUp) {
                    v.transcribe(samples = FloatArray(3_200), sampleRate = SAMPLE_RATE_HZ)
                }
                voz = v

                if (settings.earEnabled && LocalModelStorage.isModelDownloaded(appContext, LocalModelId.EAR)) {
                    runCatching {
                        val earDir = LocalModelStorage.modelRootDir(appContext, LocalModelId.EAR).absolutePath
                        val e = Ear(appContext, earDir)
                        if (warmUp) {
                            e.identify(
                                samples = FloatArray(3_200),
                                sampleRate = SAMPLE_RATE_HZ,
                                options = ai.desertant.ear.Options(windows = 1)
                            )
                        }
                        ear = e
                    }
                }

                _status.value = _status.value.copy(
                    isReady = true,
                    isLoading = false,
                    activeAcceleratorLabel = accelDesc,
                    statusText = accelDesc
                )
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to initialize Desert Ant Voz/Ear: ${t.message}", t)
                closeAllLocked()
                _status.value = _status.value.copy(
                    isReady = false,
                    isLoading = false,
                    statusText = "Error: ${t.message}"
                )
                false
            }
        }
    }

    suspend fun transcribe(
        rawSamples: FloatArray,
        onStageUpdate: ((String) -> Unit)? = null
    ): SttTranscriptionResult = withContext(Dispatchers.Default) {
        val settings = LunaSettingsRepository.current(appContext)
        val fallbackLang = settings.appLanguage.resolveLanguageCode()
        if (rawSamples.isEmpty()) {
            return@withContext SttTranscriptionResult("", fallbackLang, 0L)
        }

        val trimmed = trimLeadingAndTrailingSilence(rawSamples)
        if (trimmed.isEmpty()) {
            return@withContext SttTranscriptionResult("", fallbackLang, 0L)
        }

        val startMs = SystemClock.elapsedRealtime()
        val ready = ensureInitialized(warmUp = false)
        if (!ready) {
            throw IllegalStateException(
                if (settings.appLanguage.isGerman) {
                    "Desert Ant Voz Sprachmodell ist noch nicht heruntergeladen. Bitte in den Einstellungen unter 'Stimme' herunterladen."
                } else {
                    "Desert Ant Voz speech model is not downloaded yet. Please download it in 'Voice' settings."
                }
            )
        }

        mutex.withLock {
            val vozInstance = voz ?: throw IllegalStateException("Voz instance unavailable")

            // 1. Detect spoken language via Desert Ant Labs `Ear` (supports DE, EN, and 23+ languages)
            var detectedLang = fallbackLang
            if (settings.earEnabled && trimmed.size >= 14_400 && LocalModelStorage.isModelDownloaded(appContext, LocalModelId.EAR)) {
                val earInstance = ear ?: runCatching {
                    Ear(appContext, LocalModelStorage.modelRootDir(appContext, LocalModelId.EAR).absolutePath).also { ear = it }
                }.getOrNull()

                if (earInstance != null) {
                    onStageUpdate?.invoke("Ear")
                    runCatching {
                        val slice = if (trimmed.size > MAX_EAR_SAMPLES) trimmed.copyOfRange(0, MAX_EAR_SAMPLES) else trimmed
                        val detection = earInstance.identify(
                            samples = slice,
                            sampleRate = SAMPLE_RATE_HZ,
                            options = ai.desertant.ear.Options(windows = 2)
                        )
                        val candidate = detection.language?.lowercase(Locale.ROOT)
                        if (detection.isReliable && candidate != null && candidate in SUPPORTED_LANGUAGES) {
                            detectedLang = candidate
                        }
                    }
                }
            }

            // 2. Smart silence-aware chunking for bounded memory
            val chunks = splitAudioIntoSmartChunks(trimmed, MAX_CHUNK_SAMPLES, MIN_CHUNK_SAMPLES)
            val segmentTexts = ArrayList<String>(chunks.size)

            for ((idx, chunk) in chunks.withIndex()) {
                val suffix = if (chunks.size > 1) " (${idx + 1}/${chunks.size})" else ""
                onStageUpdate?.invoke("Voz ($detectedLang)$suffix")
                val result = vozInstance.transcribe(samples = chunk, sampleRate = SAMPLE_RATE_HZ)
                val clean = result.text.trim()
                if (clean.isNotEmpty()) {
                    segmentTexts.add(clean)
                }
            }

            val totalMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
            val fullText = segmentTexts.joinToString(" ").trim()
            _status.value = _status.value.copy(lastLatencyMs = totalMs)
            SttTranscriptionResult(
                text = fullText,
                detectedLanguage = detectedLang,
                durationMs = totalMs
            )
        }
    }

    private fun trimLeadingAndTrailingSilence(samples: FloatArray): FloatArray {
        var first = -1
        var last = -1
        for (i in samples.indices) {
            if (abs(samples[i]) >= SILENCE_THRESHOLD) {
                if (first == -1) first = i
                last = i
            }
        }
        if (first == -1 || last == -1) return FloatArray(0)
        val start = max(0, first - SILENCE_PAD_SAMPLES)
        val end = min(samples.size, last + SILENCE_PAD_SAMPLES + 1)
        return if (start == 0 && end == samples.size) samples else samples.copyOfRange(start, end)
    }

    private fun splitAudioIntoSmartChunks(
        samples: FloatArray,
        maxChunkSamples: Int,
        minChunkSamples: Int
    ): List<FloatArray> {
        if (samples.size <= maxChunkSamples) return listOf(samples)
        val result = mutableListOf<FloatArray>()
        var cursor = 0
        val windowSize = 1_280 // 80ms @ 16kHz
        while (cursor < samples.size) {
            val remaining = samples.size - cursor
            if (remaining <= maxChunkSamples) {
                result.add(samples.copyOfRange(cursor, samples.size))
                break
            }
            val searchStart = cursor + minChunkSamples
            val searchEnd = cursor + maxChunkSamples
            var bestSplit = searchEnd
            var lowestEnergy = Float.MAX_VALUE
            var i = searchStart
            while (i + windowSize <= searchEnd) {
                var energy = 0f
                for (j in 0 until windowSize) {
                    val s = samples[i + j]
                    energy += s * s
                }
                if (energy < lowestEnergy) {
                    lowestEnergy = energy
                    bestSplit = i + windowSize / 2
                }
                i += windowSize / 2
            }
            result.add(samples.copyOfRange(cursor, bestSplit))
            cursor = bestSplit
        }
        return result
    }

    private fun closeAllLocked() {
        runCatching { ear?.close() }
        runCatching { voz?.close() }
        ear = null
        voz = null
    }
}
