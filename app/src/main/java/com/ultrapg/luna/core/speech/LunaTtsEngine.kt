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

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.ultrapg.luna.core.hardware.NpuHardwareManager
import com.ultrapg.luna.core.models.LocalModelId
import com.ultrapg.luna.core.models.LocalModelStorage
import com.ultrapg.luna.core.settings.LunaSettings
import com.ultrapg.luna.core.settings.LunaSettingsRepository
import com.ultrapg.luna.core.settings.TtsVoiceModelId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Fast, natural Female Text-to-Speech Engine for English and German:
 * 1. Custom User-Trained ONNX Voice Slot (`.onnx` + `tokens.txt` + optional `espeak-ng-data`) —
 *    allows the user to drop in their own custom-trained female voice model anytime.
 * 2. Downloaded Sherpa-ONNX Neural Female Voice Models:
 *    - English: `Amy` (`vits-piper-en_US-amy-medium`) or `Kokoro 82M` (`af_heart`, `af_bella`, `af_sarah`, `af_nicole`)
 *    - German: `Dii High` (`vits-piper-de_DE-dii-high`) or `Kerstin` (`vits-piper-de_DE-kerstin-low`)
 * 3. Instant Zero-Download Android Native Female TTS Fallback (`en_US` & `de_DE`) so Luna can speak
 *    with a warm female voice immediately out of the box even before neural models finish downloading.
 */
class LunaTtsEngine private constructor(context: Context) {

    companion object {
        private const val TAG = "LunaTtsEngine"

        @Volatile
        private var instance: LunaTtsEngine? = null

        fun getInstance(context: Context): LunaTtsEngine {
            return instance ?: synchronized(this) {
                instance ?: LunaTtsEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val ttsMutex = Mutex()

    private var activeOfflineTts: OfflineTts? = null
    private var loadedModelSignature: String? = null
    private var activeAudioTrack: AudioTrack? = null
    private var speakingJob: Job? = null

    private var androidTts: TextToSpeech? = null
    private var androidTtsReady = false

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _outputRms = MutableStateFlow(0f)
    val outputRms: StateFlow<Float> = _outputRms.asStateFlow()

    init {
        initAndroidFallbackTts()
    }

    private fun initAndroidFallbackTts() {
        runCatching {
            androidTts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    androidTtsReady = true
                    androidTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _isSpeaking.value = true
                            _outputRms.value = 0.45f
                        }

                        override fun onDone(utteranceId: String?) {
                            _isSpeaking.value = false
                            _outputRms.value = 0f
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            _isSpeaking.value = false
                            _outputRms.value = 0f
                        }
                    })
                }
            }
        }
    }

    fun stopSpeaking() {
        speakingJob?.cancel()
        speakingJob = null
        runCatching {
            activeAudioTrack?.pause()
            activeAudioTrack?.flush()
            activeAudioTrack?.stop()
            activeAudioTrack?.release()
        }
        activeAudioTrack = null
        runCatching {
            androidTts?.stop()
        }
        _isSpeaking.value = false
        _outputRms.value = 0f
    }

    fun speak(text: String, languageCode: String? = null) {
        val cleanText = stripMarkdownForSpeech(text)
        if (cleanText.isBlank()) return

        val settings = LunaSettingsRepository.current(appContext)
        if (!settings.ttsEnabled) return

        stopSpeaking()

        speakingJob = scope.launch {
            val lang = languageCode?.lowercase(Locale.ROOT)
                ?: detectTextLanguageHeuristic(cleanText, settings.appLanguage.resolveLanguageCode())

            val usedNeural = trySpeakWithNeuralOnnx(cleanText, lang, settings)
            if (!usedNeural) {
                speakWithAndroidFemaleFallback(cleanText, lang, settings)
            }
        }
    }

    private suspend fun trySpeakWithNeuralOnnx(
        cleanText: String,
        langCode: String,
        settings: LunaSettings
    ): Boolean = withContext(Dispatchers.Default) {
        ttsMutex.withLock {
            try {
                val ttsInstance = resolveOrLoadOfflineTtsLocked(langCode, settings) ?: return@withLock false
                val speakerId = if (loadedModelSignature?.startsWith("kokoro") == true) {
                    settings.kokoroSpeakerId.coerceIn(0, 10)
                } else {
                    0
                }
                val speed = settings.ttsSpeed.coerceIn(0.6f, 1.8f)

                _isSpeaking.value = true
                val sentences = splitIntoSentences(cleanText)
                val sampleRate = ttsInstance.sampleRate().coerceAtLeast(16000)

                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT
                ).coerceAtLeast(4096)

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                activeAudioTrack = track
                track.play()

                for (sentence in sentences) {
                    if (!isActive) break
                    val trimmed = sentence.trim()
                    if (trimmed.isEmpty()) continue

                    val generated = ttsInstance.generate(
                        trimmed,
                        speakerId,
                        speed
                    )
                    val samples = generated.samples
                    if (samples.isNotEmpty() && isActive) {
                        var offset = 0
                        val chunkLen = 2048
                        while (offset < samples.size && isActive) {
                            val count = minOf(chunkLen, samples.size - offset)
                            var sumSq = 0.0
                            for (i in 0 until count) {
                                val s = samples[offset + i]
                                sumSq += s * s
                            }
                            val rms = (sqrt(sumSq / count).toFloat() * 4.5f).coerceIn(0.05f, 1.0f)
                            _outputRms.value = rms
                            track.write(samples, offset, count, AudioTrack.WRITE_BLOCKING)
                            offset += count
                        }
                    }
                }

                runCatching {
                    track.stop()
                    track.release()
                }
                if (activeAudioTrack === track) {
                    activeAudioTrack = null
                }
                _isSpeaking.value = false
                _outputRms.value = 0f
                true
            } catch (t: Throwable) {
                Log.w(TAG, "Neural ONNX TTS failed (${t.message}), falling back to system female TTS", t)
                _isSpeaking.value = false
                _outputRms.value = 0f
                false
            }
        }
    }

    private fun resolveOrLoadOfflineTtsLocked(
        langCode: String,
        settings: LunaSettings
    ): OfflineTts? {
        val profile = NpuHardwareManager.detectHardwareProfile(appContext)
        val provider = NpuHardwareManager.resolveSherpaOnnxProvider(settings.hardwareAccelerator, profile)
        val threads = profile.recommendedCpuThreads

        // 1. Check Custom User-Trained Female Voice Model Slot first
        if (settings.useCustomTtsModel && settings.customTtsModelPath.isNotBlank()) {
            val customModelFile = File(settings.customTtsModelPath)
            val customTokensFile = File(settings.customTtsTokensPath)
            if (customModelFile.exists() && customTokensFile.exists()) {
                val sig = "custom:${customModelFile.absolutePath}:${customTokensFile.absolutePath}:$provider"
                if (loadedModelSignature == sig && activeOfflineTts != null) {
                    return activeOfflineTts
                }
                val dataDir = settings.customTtsDataDirPath.takeIf { it.isNotBlank() && File(it).exists() }
                    ?: findFallbackEspeakDataDir()
                    ?: ""
                val vitsConfig = OfflineTtsVitsModelConfig(
                    model = customModelFile.absolutePath,
                    lexicon = "",
                    tokens = customTokensFile.absolutePath,
                    dataDir = dataDir,
                    dictDir = "",
                    noiseScale = 0.667f,
                    noiseScaleW = 0.8f,
                    lengthScale = 1.0f
                )
                return instantiateOfflineTts(
                    sig = sig,
                    vits = vitsConfig,
                    kokoro = OfflineTtsKokoroModelConfig(),
                    threads = threads,
                    provider = provider
                )
            }
        }

        // 2. Check Downloaded Neural Voice for the target language (DE vs EN)
        val isDe = langCode.startsWith("de")
        if (isDe) {
            val preferredDe = when (settings.preferredGermanTts) {
                TtsVoiceModelId.PIPER_DE_KERSTIN -> LocalModelId.TTS_PIPER_DE_KERSTIN
                else -> LocalModelId.TTS_PIPER_DE_DII
            }
            val deCandidates = listOf(
                preferredDe,
                LocalModelId.TTS_PIPER_DE_DII,
                LocalModelId.TTS_PIPER_DE_KERSTIN
            ).distinct()

            for (candidate in deCandidates) {
                if (LocalModelStorage.isModelDownloaded(appContext, candidate)) {
                    val root = LocalModelStorage.modelRootDir(appContext, candidate)
                    val subDir = if (candidate == LocalModelId.TTS_PIPER_DE_DII) {
                        File(root, "vits-piper-de_DE-dii-high")
                    } else {
                        File(root, "vits-piper-de_DE-kerstin-low")
                    }
                    val onnxName = if (candidate == LocalModelId.TTS_PIPER_DE_DII) {
                        "de_DE-dii-high.onnx"
                    } else {
                        "de_DE-kerstin-low.onnx"
                    }
                    val modelFile = File(subDir, onnxName)
                    val tokensFile = File(subDir, "tokens.txt")
                    val dataDir = File(subDir, "espeak-ng-data")
                    if (modelFile.exists() && tokensFile.exists()) {
                        val sig = "vits:${modelFile.absolutePath}:$provider"
                        if (loadedModelSignature == sig && activeOfflineTts != null) {
                            return activeOfflineTts
                        }
                        val vits = OfflineTtsVitsModelConfig(
                            model = modelFile.absolutePath,
                            lexicon = "",
                            tokens = tokensFile.absolutePath,
                            dataDir = if (dataDir.exists()) dataDir.absolutePath else "",
                            dictDir = "",
                            noiseScale = 0.667f,
                            noiseScaleW = 0.8f,
                            lengthScale = 1.0f
                        )
                        return instantiateOfflineTts(
                            sig = sig,
                            vits = vits,
                            kokoro = OfflineTtsKokoroModelConfig(),
                            threads = threads,
                            provider = provider
                        )
                    }
                }
            }
        } else {
            val preferredEn = when (settings.preferredEnglishTts) {
                TtsVoiceModelId.KOKORO_EN_HEART -> LocalModelId.TTS_KOKORO_EN_HEART
                else -> LocalModelId.TTS_PIPER_EN_AMY
            }
            val enCandidates = listOf(
                preferredEn,
                LocalModelId.TTS_PIPER_EN_AMY,
                LocalModelId.TTS_KOKORO_EN_HEART
            ).distinct()

            for (candidate in enCandidates) {
                if (!LocalModelStorage.isModelDownloaded(appContext, candidate)) continue
                val root = LocalModelStorage.modelRootDir(appContext, candidate)
                if (candidate == LocalModelId.TTS_KOKORO_EN_HEART) {
                    val subDir = File(root, "kokoro-int8-en-v0_19")
                    val modelFile = File(subDir, "model.int8.onnx")
                    val voicesFile = File(subDir, "voices.bin")
                    val tokensFile = File(subDir, "tokens.txt")
                    val dataDir = File(subDir, "espeak-ng-data")
                    if (modelFile.exists() && voicesFile.exists() && tokensFile.exists()) {
                        val sig = "kokoro:${modelFile.absolutePath}:$provider"
                        if (loadedModelSignature == sig && activeOfflineTts != null) {
                            return activeOfflineTts
                        }
                        val kokoro = OfflineTtsKokoroModelConfig(
                            model = modelFile.absolutePath,
                            voices = voicesFile.absolutePath,
                            tokens = tokensFile.absolutePath,
                            dataDir = if (dataDir.exists()) dataDir.absolutePath else "",
                            lexicon = "",
                            lang = "",
                            dictDir = "",
                            lengthScale = 1.0f
                        )
                        return instantiateOfflineTts(
                            sig = sig,
                            vits = OfflineTtsVitsModelConfig(),
                            kokoro = kokoro,
                            threads = threads,
                            provider = provider
                        )
                    }
                } else {
                    val subDir = File(root, "vits-piper-en_US-amy-medium")
                    val modelFile = File(subDir, "en_US-amy-medium.onnx")
                    val tokensFile = File(subDir, "tokens.txt")
                    val dataDir = File(subDir, "espeak-ng-data")
                    if (modelFile.exists() && tokensFile.exists()) {
                        val sig = "vits:${modelFile.absolutePath}:$provider"
                        if (loadedModelSignature == sig && activeOfflineTts != null) {
                            return activeOfflineTts
                        }
                        val vits = OfflineTtsVitsModelConfig(
                            model = modelFile.absolutePath,
                            lexicon = "",
                            tokens = tokensFile.absolutePath,
                            dataDir = if (dataDir.exists()) dataDir.absolutePath else "",
                            dictDir = "",
                            noiseScale = 0.667f,
                            noiseScaleW = 0.8f,
                            lengthScale = 1.0f
                        )
                        return instantiateOfflineTts(
                            sig = sig,
                            vits = vits,
                            kokoro = OfflineTtsKokoroModelConfig(),
                            threads = threads,
                            provider = provider
                        )
                    }
                }
            }
        }
        return null
    }

    private fun instantiateOfflineTts(
        sig: String,
        vits: OfflineTtsVitsModelConfig,
        kokoro: OfflineTtsKokoroModelConfig,
        threads: Int,
        provider: String
    ): OfflineTts? {
        runCatching { activeOfflineTts?.release() }
        activeOfflineTts = null
        loadedModelSignature = null

        // Try with requested provider (e.g. "nnapi" for NPU/GPU), and fallback to "cpu" if NNAPI rejects dynamic ops
        val providersToTry = if (provider != "cpu") listOf(provider, "cpu") else listOf("cpu")
        for (prov in providersToTry) {
            try {
                val modelConfig = OfflineTtsModelConfig().apply {
                    this.vits = vits
                    this.kokoro = kokoro
                    this.numThreads = threads
                    this.debug = false
                    this.provider = prov
                }
                val config = OfflineTtsConfig().apply {
                    this.model = modelConfig
                    this.maxNumSentences = 1
                }
                val instance = OfflineTts(assetManager = null, config = config)
                activeOfflineTts = instance
                loadedModelSignature = sig
                return instance
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to init OfflineTts with provider=$prov: ${t.message}")
            }
        }
        return null
    }

    private fun findFallbackEspeakDataDir(): String? {
        val candidates = listOf(
            LocalModelId.TTS_PIPER_EN_AMY to "vits-piper-en_US-amy-medium/espeak-ng-data",
            LocalModelId.TTS_PIPER_DE_DII to "vits-piper-de_DE-dii-high/espeak-ng-data",
            LocalModelId.TTS_PIPER_DE_KERSTIN to "vits-piper-de_DE-kerstin-low/espeak-ng-data",
            LocalModelId.TTS_KOKORO_EN_HEART to "kokoro-int8-en-v0_19/espeak-ng-data"
        )
        for ((model, rel) in candidates) {
            val dir = File(LocalModelStorage.modelRootDir(appContext, model), rel)
            if (dir.exists() && dir.isDirectory) {
                return dir.absolutePath
            }
        }
        return null
    }

    private fun speakWithAndroidFemaleFallback(
        cleanText: String,
        langCode: String,
        settings: LunaSettings
    ) {
        val tts = androidTts ?: return
        if (!androidTtsReady) return

        val targetLocale = if (langCode.startsWith("de")) Locale.GERMANY else Locale.US
        runCatching {
            tts.language = targetLocale
            // Select a female voice if available on the device
            val voices = tts.voices
            if (voices != null) {
                val langVoices = voices.filter {
                    it.locale.language.equals(targetLocale.language, ignoreCase = true) &&
                        !it.isNetworkConnectionRequired
                }
                val femaleCandidate = langVoices.firstOrNull { v ->
                    val n = v.name.lowercase(Locale.ROOT)
                    n.contains("female") || n.contains("tpf") || n.contains("sfg") ||
                        n.contains("iob") || n.contains("tha") || n.contains("deb") || n.contains("#f")
                } ?: langVoices.firstOrNull()
                if (femaleCandidate != null) {
                    tts.voice = femaleCandidate
                }
            }
            tts.setSpeechRate(settings.ttsSpeed.coerceIn(0.6f, 1.8f))
            tts.setPitch(settings.ttsPitch.coerceIn(0.8f, 1.4f))
            tts.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        }
    }

    private fun stripMarkdownForSpeech(raw: String): String {
        return raw
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("`([^`]+)`"), "$1")
            .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
            .replace(Regex("\\*([^*]+)\\*"), "$1")
            .replace(Regex("#+\\s*"), "")
            .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun splitIntoSentences(text: String): List<String> {
        return text.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(text) }
    }

    private fun detectTextLanguageHeuristic(text: String, fallback: String): String {
        val lower = " ${text.lowercase(Locale.ROOT)} "
        val deHits = listOf(" und ", " ist ", " nicht ", " ich ", " das ", " die ", " der ", " für ", " mit ", " auf ", "ä", "ö", "ü", "ß")
            .count { lower.contains(it) }
        val enHits = listOf(" the ", " and ", " is ", " you ", " that ", " with ", " for ", " have ", " this ")
            .count { lower.contains(it) }
        return when {
            deHits > enHits -> "de"
            enHits > deHits -> "en"
            else -> fallback
        }
    }
}
