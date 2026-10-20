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
import android.media.AudioManager
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fast, natural Female Text-to-Speech Engine for English and German:
 * 1. Custom User-Trained ONNX Voice Slot (`.onnx` + `tokens.txt` + optional `espeak-ng-data`)
 * 2. Downloaded Sherpa-ONNX Neural Female Voice Models (`Amy`, `Kokoro 82M`, `Dii High`, `Kerstin`)
 * 3. Instant Zero-Download Android Native Female TTS Fallback (`en_US` & `de_DE`)
 *
 * Computes real-time `_outputRms` and 5-band `_outputWaveBands` during audio playback so the
 * vertical side-notch bubble shows the exact same audio-reactive equalizer animation in light blue
 * while speaking as it does in white while listening.
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
    @Volatile
    private var androidTtsReady = false
    private val utteranceWaiters = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _outputRms = MutableStateFlow(0f)
    val outputRms: StateFlow<Float> = _outputRms.asStateFlow()

    private val _outputWaveBands = MutableStateFlow(floatArrayOf(0.1f, 0.1f, 0.1f, 0.1f, 0.1f))
    val outputWaveBands: StateFlow<FloatArray> = _outputWaveBands.asStateFlow()

    init {
        initAndroidFallbackTts()
    }

    private fun initAndroidFallbackTts() {
        runCatching {
            androidTts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    androidTtsReady = true
                    androidTts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    androidTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _isSpeaking.value = true
                        }

                        override fun onDone(utteranceId: String?) {
                            if (utteranceId != null) {
                                utteranceWaiters.remove(utteranceId)?.complete(Unit)
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            if (utteranceId != null) {
                                utteranceWaiters.remove(utteranceId)?.complete(Unit)
                            }
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            if (utteranceId != null) {
                                utteranceWaiters.remove(utteranceId)?.complete(Unit)
                            }
                        }
                    })
                }
            }
        }
    }

    fun stopSpeaking() {
        speakingJob?.cancel()
        speakingJob = null
        utteranceWaiters.values.forEach { it.complete(Unit) }
        utteranceWaiters.clear()
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
        _outputWaveBands.value = floatArrayOf(0.06f, 0.06f, 0.06f, 0.06f, 0.06f)
    }

    /**
     * Fire-and-forget speak (used by the "Test" button in the Voice tab).
     */
    fun speak(text: String, languageCode: String? = null) {
        stopSpeaking()
        speakingJob = scope.launch {
            speakAndAwait(text, languageCode)
        }
    }

    /**
     * Suspends until the entire TTS utterance has finished playing out of the speaker.
     * Drives `_isSpeaking`, `_outputRms`, and `_outputWaveBands` in real time.
     */
    suspend fun speakAndAwait(text: String, languageCode: String? = null) {
        val cleanText = stripMarkdownForSpeech(text)
        if (cleanText.isBlank()) return

        val settings = LunaSettingsRepository.current(appContext)
        if (!settings.ttsEnabled) return

        // Ensure Android audio routing is in normal media mode after microphone recording
        runCatching {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null && am.mode != AudioManager.MODE_NORMAL) {
                am.mode = AudioManager.MODE_NORMAL
            }
        }

        _isSpeaking.value = true
        _outputRms.value = 0.25f

        try {
            val lang = languageCode?.lowercase(Locale.ROOT)
                ?: detectTextLanguageHeuristic(cleanText, settings.appLanguage.resolveLanguageCode())

            val usedNeural = trySpeakWithNeuralOnnx(cleanText, lang, settings)
            if (!usedNeural) {
                speakWithAndroidFemaleFallbackAndAwait(cleanText, lang, settings)
            }
        } finally {
            _isSpeaking.value = false
            _outputRms.value = 0f
            _outputWaveBands.value = floatArrayOf(0.06f, 0.06f, 0.06f, 0.06f, 0.06f)
        }
    }

    private suspend fun trySpeakWithNeuralOnnx(
        cleanText: String,
        langCode: String,
        settings: LunaSettings
    ): Boolean = withContext(Dispatchers.Default) {
        ttsMutex.withLock {
            var track: AudioTrack? = null
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

                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
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

                var totalSamplesPlayed = 0

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
                    if (samples != null && samples.isNotEmpty() && isActive) {
                        var offset = 0
                        val chunkLen = 1024 // ~46ms chunks for responsive 20fps equalizer animation
                        while (offset < samples.size && isActive) {
                            val count = minOf(chunkLen, samples.size - offset)
                            var sumSq = 0.0
                            val bandAccum = FloatArray(5)
                            val bandBucketSize = (count / 5).coerceAtLeast(1)

                            for (i in 0 until count) {
                                val s = samples[offset + i]
                                sumSq += s * s
                                val bandIdx = (i / bandBucketSize).coerceIn(0, 4)
                                bandAccum[bandIdx] += abs(s)
                            }

                            val rms = (sqrt(sumSq / count).toFloat() * 4.8f).coerceIn(0.08f, 1.0f)
                            _outputRms.value = _outputRms.value * 0.3f + rms * 0.7f

                            val prevBands = _outputWaveBands.value
                            val nextBands = FloatArray(5) { idx ->
                                val avg = (bandAccum[idx] / bandBucketSize) * 6.2f
                                (prevBands[idx] * 0.3f + avg * 0.7f).coerceIn(0.08f, 1.0f)
                            }
                            _outputWaveBands.value = nextBands

                            val written = track.write(samples, offset, count, AudioTrack.WRITE_BLOCKING)
                            if (written > 0) {
                                totalSamplesPlayed += written
                            }
                            offset += count
                        }
                    }
                }

                // Wait briefly for the hardware AudioTrack buffer to drain its final samples
                if (totalSamplesPlayed > 0 && isActive) {
                    delay(180)
                }

                runCatching {
                    track.stop()
                    track.release()
                }
                if (activeAudioTrack === track) {
                    activeAudioTrack = null
                }

                totalSamplesPlayed > 0
            } catch (t: Throwable) {
                Log.w(TAG, "Neural ONNX TTS failed (${t.message}), falling back to system female TTS", t)
                runCatching {
                    track?.stop()
                    track?.release()
                }
                if (activeAudioTrack === track) {
                    activeAudioTrack = null
                }
                false
            }
        }
    }

    private fun resolveOrLoadOfflineTtsLocked(
        langCode: String,
        settings: LunaSettings
    ): OfflineTts? {
        val profile = NpuHardwareManager.detectHardwareProfile(appContext)
        val threads = profile.recommendedCpuThreads.coerceIn(2, 4)
        // Always use multi-threaded ARM64 XNNPACK ("cpu") for Sherpa-ONNX VITS/Kokoro synthesis
        // because Android NNAPI rejects dynamic-shape phoneme/noise ops at runtime.
        val provider = "cpu"

        // 1. Check Custom User-Trained Female Voice Model Slot first
        if (settings.useCustomTtsModel && settings.customTtsModelPath.isNotBlank()) {
            val customModelFile = File(settings.customTtsModelPath)
            val customTokensFile = File(settings.customTtsTokensPath)
            if (customModelFile.exists() && customTokensFile.exists()) {
                val sig = "custom:${customModelFile.absolutePath}:${customTokensFile.absolutePath}"
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

        // 2. Build ordered candidate list: user's selected active voice first, then language-matched models, then any installed TTS model
        val selectedModelId = when (settings.selectedTtsVoice) {
            TtsVoiceModelId.PIPER_EN_AMY -> LocalModelId.TTS_PIPER_EN_AMY
            TtsVoiceModelId.KOKORO_EN_HEART -> LocalModelId.TTS_KOKORO_EN_HEART
            TtsVoiceModelId.PIPER_DE_DII -> LocalModelId.TTS_PIPER_DE_DII
            TtsVoiceModelId.PIPER_DE_KERSTIN -> LocalModelId.TTS_PIPER_DE_KERSTIN
            else -> null
        }

        val isDe = langCode.startsWith("de")
        val candidates = buildList {
            if (isDe) {
                selectedModelId?.takeIf { it == LocalModelId.TTS_PIPER_DE_DII || it == LocalModelId.TTS_PIPER_DE_KERSTIN }?.let { add(it) }
                add(LocalModelId.TTS_PIPER_DE_DII)
                add(LocalModelId.TTS_PIPER_DE_KERSTIN)
                selectedModelId?.let { add(it) }
                add(LocalModelId.TTS_PIPER_EN_AMY)
                add(LocalModelId.TTS_KOKORO_EN_HEART)
            } else {
                selectedModelId?.takeIf { it == LocalModelId.TTS_PIPER_EN_AMY || it == LocalModelId.TTS_KOKORO_EN_HEART }?.let { add(it) }
                add(LocalModelId.TTS_PIPER_EN_AMY)
                add(LocalModelId.TTS_KOKORO_EN_HEART)
                selectedModelId?.let { add(it) }
                add(LocalModelId.TTS_PIPER_DE_DII)
                add(LocalModelId.TTS_PIPER_DE_KERSTIN)
            }
        }.distinct()

        for (candidate in candidates) {
            if (!LocalModelStorage.isModelDownloaded(appContext, candidate)) continue
            val root = LocalModelStorage.modelRootDir(appContext, candidate)
            val onnxFile = findFileRecursively(root) { it.isFile && it.name.endsWith(".onnx") } ?: continue
            val modelDir = onnxFile.parentFile ?: root
            val tokensFile = File(modelDir, "tokens.txt").takeIf { it.exists() }
                ?: findFileRecursively(root) { it.isFile && it.name == "tokens.txt" }
                ?: continue
            val dataDir = File(modelDir, "espeak-ng-data").takeIf { it.isDirectory }
                ?: findFileRecursively(root) { it.isDirectory && it.name == "espeak-ng-data" }

            if (candidate == LocalModelId.TTS_KOKORO_EN_HEART) {
                val voicesFile = File(modelDir, "voices.bin").takeIf { it.exists() }
                    ?: findFileRecursively(root) { it.isFile && it.name == "voices.bin" }
                    ?: continue
                val sig = "kokoro:${onnxFile.absolutePath}"
                if (loadedModelSignature == sig && activeOfflineTts != null) {
                    return activeOfflineTts
                }
                val kokoro = OfflineTtsKokoroModelConfig(
                    model = onnxFile.absolutePath,
                    voices = voicesFile.absolutePath,
                    tokens = tokensFile.absolutePath,
                    dataDir = dataDir?.absolutePath ?: "",
                    lexicon = "",
                    lang = "",
                    dictDir = "",
                    lengthScale = 1.0f
                )
                val loaded = instantiateOfflineTts(
                    sig = sig,
                    vits = OfflineTtsVitsModelConfig(),
                    kokoro = kokoro,
                    threads = threads,
                    provider = provider
                )
                if (loaded != null) return loaded
            } else {
                val sig = "vits:${onnxFile.absolutePath}"
                if (loadedModelSignature == sig && activeOfflineTts != null) {
                    return activeOfflineTts
                }
                val vits = OfflineTtsVitsModelConfig(
                    model = onnxFile.absolutePath,
                    lexicon = "",
                    tokens = tokensFile.absolutePath,
                    dataDir = dataDir?.absolutePath ?: "",
                    dictDir = "",
                    noiseScale = 0.667f,
                    noiseScaleW = 0.8f,
                    lengthScale = 1.0f
                )
                val loaded = instantiateOfflineTts(
                    sig = sig,
                    vits = vits,
                    kokoro = OfflineTtsKokoroModelConfig(),
                    threads = threads,
                    provider = provider
                )
                if (loaded != null) return loaded
            }
        }
        return null
    }

    private fun findFileRecursively(dir: File, predicate: (File) -> Boolean): File? {
        if (!dir.exists()) return null
        val children = dir.listFiles() ?: return null
        for (child in children) {
            if (predicate(child)) return child
        }
        for (child in children) {
            if (child.isDirectory) {
                val found = findFileRecursively(child, predicate)
                if (found != null) return found
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

        return try {
            val modelConfig = OfflineTtsModelConfig().apply {
                this.vits = vits
                this.kokoro = kokoro
                this.numThreads = threads
                this.debug = false
                this.provider = provider
            }
            val config = OfflineTtsConfig().apply {
                this.model = modelConfig
                this.maxNumSentences = 1
            }
            val instance = OfflineTts(assetManager = null, config = config)
            activeOfflineTts = instance
            loadedModelSignature = sig
            instance
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to init OfflineTts: ${t.message}", t)
            null
        }
    }

    private fun findFallbackEspeakDataDir(): String? {
        val models = listOf(
            LocalModelId.TTS_PIPER_DE_DII,
            LocalModelId.TTS_PIPER_DE_KERSTIN,
            LocalModelId.TTS_PIPER_EN_AMY,
            LocalModelId.TTS_KOKORO_EN_HEART
        )
        for (model in models) {
            val root = LocalModelStorage.modelRootDir(appContext, model)
            val found = findFileRecursively(root) { it.isDirectory && it.name == "espeak-ng-data" }
            if (found != null) return found.absolutePath
        }
        return null
    }

    private suspend fun speakWithAndroidFemaleFallbackAndAwait(
        cleanText: String,
        langCode: String,
        settings: LunaSettings
    ) {
        // Wait up to 1.5s if Android TextToSpeech is still initializing on cold start
        var waitInitMs = 0L
        while (!androidTtsReady && waitInitMs < 1500L) {
            delay(50)
            waitInitMs += 50L
        }
        val tts = androidTts ?: return
        if (!androidTtsReady) return

        val utteranceId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Unit>()
        utteranceWaiters[utteranceId] = deferred

        val targetLocale = if (langCode.startsWith("de")) Locale.GERMANY else Locale.US
        val started = runCatching {
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            tts.language = targetLocale
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
            val res = tts.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            res == TextToSpeech.SUCCESS
        }.getOrDefault(false)

        if (!started) {
            utteranceWaiters.remove(utteranceId)
            return
        }

        // Animate `_outputRms` and `_outputWaveBands` while Android system TTS is speaking
        // so the light-blue equalizer bars react smoothly just like neural TTS!
        val animJob = scope.launch {
            var tick = 0f
            while (isActive && !deferred.isCompleted) {
                tick += 0.35f
                val simRms = (0.42f + 0.28f * abs(sin(tick))).coerceIn(0.20f, 0.85f)
                _outputRms.value = simRms
                _outputWaveBands.value = FloatArray(5) { idx ->
                    (0.30f + 0.55f * abs(sin(tick + idx * 0.7f))).coerceIn(0.15f, 0.92f)
                }
                delay(45)
            }
        }

        try {
            withTimeoutOrNull(45_000L) {
                deferred.await()
            }
        } finally {
            animJob.cancel()
            utteranceWaiters.remove(utteranceId)
        }
    }

    private fun stripMarkdownForSpeech(raw: String): String {
        return raw
            .replace(Regex("(?i)<think>[\\s\\S]*?</think>"), " ")
            .replace(Regex("(?i)<thinking>[\\s\\S]*?</thinking>"), " ")
            .replace(Regex("(?i)<thought>[\\s\\S]*?</thought>"), " ")
            .replace(Regex("(?i)<think>[\\s\\S]*$"), " ")
            .replace(Regex("(?i)<thinking>[\\s\\S]*$"), " ")
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("`([^`]+)`"), "$1")
            .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
            .replace(Regex("\\*([^*]+)\\*"), "$1")
            .replace(Regex("#+\\s*"), "")
            .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("\\|[-:\\s|]+\\|"), " ")
            .replace(Regex("\\|"), ", ")
            .replace(Regex("(?m)^\\s*[-*•]+\\s+"), "")
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
