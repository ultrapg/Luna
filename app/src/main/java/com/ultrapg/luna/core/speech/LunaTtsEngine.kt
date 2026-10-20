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
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 4-Tier Unbreakable Female Text-to-Speech Engine for English and German:
 * - Tier 1: Custom User-Trained ONNX Voice Slot OR Downloaded Sherpa-ONNX Neural Female Voice Models
 *   (`Amy`, `Kokoro 82M`, `Dii High`, `Kerstin`) using 16-bit normalized PCM & full hardware buffer drain.
 * - Tier 2: Android Native Female TTS (`synthesizeToFile` -> verified 16-bit PCM WAV -> `AudioTrack` with
 *   real-time equalizer waveform + direct `tts.speak` fallback, filtering out uninstalled voice packs).
 * - Tier 3: Network Neural TTS HTTPS Audio Fallback (guarantees speech output even when no local ONNX
 *   model is downloaded and the device's system TTS engine is missing or broken).
 * - Tier 4: Cross-language local Sherpa-ONNX fallback when completely offline.
 */
class LunaTtsEngine private constructor(context: Context) {

    companion object {
        private const val TAG = "LunaTtsEngine"

        private val KNOWN_TTS_ENGINES = listOf(
            null, // System default engine first
            "com.google.android.tts",
            "com.samsung.SMT",
            "com.svox.pico"
        )

        @Volatile
        private var instance: LunaTtsEngine? = null

        fun getInstance(context: Context): LunaTtsEngine {
            return instance ?: synchronized(this) {
                instance ?: LunaTtsEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val ttsMutex = Mutex()
    private val androidTtsInitMutex = Mutex()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private var activeOfflineTts: OfflineTts? = null
    private var loadedModelSignature: String? = null

    @Volatile
    private var activeAudioTrack: AudioTrack? = null

    @Volatile
    private var activeMediaPlayer: MediaPlayer? = null

    private var activeFocusRequest: AudioFocusRequest? = null
    private var speakingJob: Job? = null

    @Volatile
    private var androidTts: TextToSpeech? = null

    @Volatile
    private var androidTtsReady = false

    // Maps utteranceId -> CompletableDeferred<Boolean> (true = onDone, false = onError/cancelled)
    private val utteranceWaiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _outputRms = MutableStateFlow(0f)
    val outputRms: StateFlow<Float> = _outputRms.asStateFlow()

    private val _outputWaveBands = MutableStateFlow(floatArrayOf(0.1f, 0.1f, 0.1f, 0.1f, 0.1f))
    val outputWaveBands: StateFlow<FloatArray> = _outputWaveBands.asStateFlow()

    private val assistantSpeechAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val accessibilitySpeechAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .setLegacyStreamType(AudioManager.STREAM_ACCESSIBILITY)
        .build()

    data class AssistantAudioConfig(
        val attributes: AudioAttributes,
        val streamType: Int
    )

    /**
     * Resolves the dedicated AI Assistant audio attributes and hardware volume stream:
     * - If the device OS exposes a dedicated `STREAM_ASSISTANT` volume control separate from
     *   `STREAM_MUSIC`, uses `AudioAttributes.USAGE_ASSISTANT` directly.
     * - If the device OS links `USAGE_ASSISTANT` to `STREAM_MUSIC` (Media volume), uses
     *   `AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY` (`AudioManager.STREAM_ACCESSIBILITY`)
     *   so Luna always has a dedicated Assistant/Voice volume slider and always speaks audibly
     *   even when Media audio (`STREAM_MUSIC`) is muted or turned down!
     */
    fun resolveAssistantAudioConfig(): AssistantAudioConfig {
        val assistantStream = runCatching {
            assistantSpeechAttributes.volumeControlStream
        }.getOrDefault(AudioManager.STREAM_MUSIC)

        return if (assistantStream != AudioManager.STREAM_MUSIC &&
            assistantStream != AudioManager.USE_DEFAULT_STREAM_TYPE &&
            assistantStream >= 0
        ) {
            AssistantAudioConfig(assistantSpeechAttributes, assistantStream)
        } else {
            AssistantAudioConfig(accessibilitySpeechAttributes, AudioManager.STREAM_ACCESSIBILITY)
        }
    }

    fun getPreferredVolumeControlStream(): Int {
        return resolveAssistantAudioConfig().streamType
    }

    init {
        mainHandler.post {
            initAndroidFallbackTtsAsync(enginePackage = null)
        }
    }

    private fun initAndroidFallbackTtsAsync(
        enginePackage: String?,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        runCatching {
            val listener = TextToSpeech.OnInitListener { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val tts = androidTts
                    if (tts != null) {
                        val activeAttrs = resolveAssistantAudioConfig().attributes
                        runCatching { tts.setAudioAttributes(activeAttrs) }
                        attachUtteranceListener(tts)
                        androidTtsReady = true
                        onComplete?.invoke(true)
                        return@OnInitListener
                    }
                }
                androidTtsReady = false
                onComplete?.invoke(false)
            }
            androidTts = if (enginePackage.isNullOrBlank()) {
                TextToSpeech(appContext, listener)
            } else {
                TextToSpeech(appContext, listener, enginePackage)
            }
        }.onFailure {
            androidTtsReady = false
            onComplete?.invoke(false)
        }
    }

    private fun attachUtteranceListener(tts: TextToSpeech) {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId != null) {
                    utteranceWaiters.remove(utteranceId)?.complete(true)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.w(TAG, "Android TTS onError (legacy) for utterance=$utteranceId")
                if (utteranceId != null) {
                    utteranceWaiters.remove(utteranceId)?.complete(false)
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "Android TTS onError code=$errorCode for utterance=$utteranceId")
                if (utteranceId != null) {
                    utteranceWaiters.remove(utteranceId)?.complete(false)
                }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (utteranceId != null) {
                    utteranceWaiters.remove(utteranceId)?.complete(!interrupted)
                }
            }
        })
    }

    private suspend fun ensureAndroidTtsReady(): TextToSpeech? = androidTtsInitMutex.withLock {
        var waitMs = 0L
        while (!androidTtsReady && androidTts != null && waitMs < 1800L) {
            delay(60)
            waitMs += 60L
        }
        if (androidTtsReady && androidTts != null) {
            return@withLock androidTts
        }

        for (enginePkg in KNOWN_TTS_ENGINES) {
            val readyDeferred = CompletableDeferred<Boolean>()
            mainHandler.post {
                runCatching { androidTts?.shutdown() }
                androidTts = null
                androidTtsReady = false
                initAndroidFallbackTtsAsync(enginePkg) { ok ->
                    readyDeferred.complete(ok)
                }
            }
            val ok = withTimeoutOrNull(3000L) { readyDeferred.await() } == true
            if (ok && androidTts != null) {
                return@withLock androidTts
            }
        }
        return@withLock androidTts?.takeIf { androidTtsReady }
    }

    fun stopSpeaking() {
        speakingJob?.cancel()
        speakingJob = null
        utteranceWaiters.values.forEach { it.complete(false) }
        utteranceWaiters.clear()
        runCatching {
            activeAudioTrack?.pause()
            activeAudioTrack?.flush()
            activeAudioTrack?.stop()
            activeAudioTrack?.release()
        }
        activeAudioTrack = null
        runCatching {
            activeMediaPlayer?.stop()
            activeMediaPlayer?.release()
        }
        activeMediaPlayer = null
        runCatching {
            androidTts?.stop()
        }
        abandonSpeechAudioFocus()
        _isSpeaking.value = false
        _outputRms.value = 0f
        _outputWaveBands.value = floatArrayOf(0.06f, 0.06f, 0.06f, 0.06f, 0.06f)
    }

    /**
     * Fire-and-forget speak (used by the "Test" button in the Voice tab and message replay).
     * Always speaks even if auto-TTS was toggled off in chat.
     */
    fun speak(text: String, languageCode: String? = null, forceSpeak: Boolean = true) {
        stopSpeaking()
        speakingJob = scope.launch {
            speakAndAwait(text, languageCode, forceSpeak = forceSpeak)
        }
    }

    /**
     * Suspends until the entire TTS utterance has finished playing out of the speaker.
     * Drives `_isSpeaking`, `_outputRms`, and `_outputWaveBands` in real time.
     */
    suspend fun speakAndAwait(
        text: String,
        languageCode: String? = null,
        forceSpeak: Boolean = false
    ) {
        val cleanText = stripMarkdownForSpeech(text)
        if (cleanText.isBlank()) return

        val settings = LunaSettingsRepository.current(appContext)
        if (!forceSpeak && !settings.ttsEnabled) return

        prepareAudioHardwareForSpeech(forceRaiseMutedVolume = true)

        _isSpeaking.value = true
        _outputRms.value = 0.25f

        try {
            val lang = languageCode?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
                ?: detectTextLanguageHeuristic(cleanText, settings.appLanguage.resolveLanguageCode())

            // Tier 1: Local Sherpa-ONNX Neural TTS (Custom ONNX or language-matched downloaded model)
            var spoken = trySpeakWithNeuralOnnx(cleanText, lang, settings, strictLangMatch = true)

            // Tier 2: Android Native Female TTS (synthesizeToFile -> AudioTrack, plus direct speak fallback)
            if (!spoken && coroutineContext.isActive) {
                spoken = trySpeakWithAndroidSystemTts(cleanText, lang, settings)
            }

            // Tier 3: Network Neural TTS Audio Fallback (when no local ONNX model is installed & Android TTS failed)
            if (!spoken && coroutineContext.isActive) {
                spoken = trySpeakWithNetworkTtsFallback(cleanText, lang, settings)
            }

            // Tier 4: Any installed local Sherpa-ONNX model regardless of language (offline last resort)
            if (!spoken && coroutineContext.isActive) {
                trySpeakWithNeuralOnnx(cleanText, lang, settings, strictLangMatch = false)
            }
        } finally {
            abandonSpeechAudioFocus()
            _isSpeaking.value = false
            _outputRms.value = 0f
            _outputWaveBands.value = floatArrayOf(0.06f, 0.06f, 0.06f, 0.06f, 0.06f)
        }
    }

    private fun prepareAudioHardwareForSpeech(forceRaiseMutedVolume: Boolean) {
        runCatching {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (am.mode != AudioManager.MODE_NORMAL) {
                am.mode = AudioManager.MODE_NORMAL
            }
            val audioConfig = resolveAssistantAudioConfig()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(audioConfig.attributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { }
                    .build()
                activeFocusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null,
                    audioConfig.streamType,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }

            // Ensure the assistant/accessibility stream is audible without unmuting STREAM_MUSIC
            if (forceRaiseMutedVolume && audioConfig.streamType != AudioManager.STREAM_MUSIC) {
                val curVol = am.getStreamVolume(audioConfig.streamType)
                val maxVol = am.getStreamMaxVolume(audioConfig.streamType)
                if (curVol <= 0 && maxVol > 0) {
                    val targetVol = (maxVol * 0.45f).toInt().coerceAtLeast(2)
                    am.setStreamVolume(audioConfig.streamType, targetVol, 0)
                }
            }
        }
    }

    private fun abandonSpeechAudioFocus() {
        runCatching {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val req = activeFocusRequest
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && req != null) {
                am.abandonAudioFocusRequest(req)
            }
            activeFocusRequest = null
        }
    }

    private suspend fun trySpeakWithNeuralOnnx(
        cleanText: String,
        langCode: String,
        settings: LunaSettings,
        strictLangMatch: Boolean
    ): Boolean = withContext(Dispatchers.Default) {
        ttsMutex.withLock {
            try {
                val ttsInstance = resolveOrLoadOfflineTtsLocked(langCode, settings, strictLangMatch)
                    ?: return@withLock false
                val speakerId = if (loadedModelSignature?.startsWith("kokoro") == true) {
                    settings.kokoroSpeakerId.coerceIn(0, 10)
                } else {
                    0
                }
                val speed = settings.ttsSpeed.coerceIn(0.6f, 1.8f)
                val sampleRate = ttsInstance.sampleRate().coerceAtLeast(16000)

                val sentences = splitIntoSentences(cleanText)
                val synthesizedChunks = mutableListOf<ShortArray>()

                for (sentence in sentences) {
                    if (!isActive) break
                    val trimmed = sentence.trim()
                    if (trimmed.isEmpty()) continue

                    val generated = ttsInstance.generate(trimmed, speakerId, speed)
                    val floatSamples = generated.samples
                    if (floatSamples != null && floatSamples.isNotEmpty()) {
                        synthesizedChunks.add(normalizeFloatToPcm16(floatSamples))
                    }
                }

                if (synthesizedChunks.isEmpty() || !isActive) {
                    return@withLock false
                }

                val totalLen = synthesizedChunks.sumOf { it.size }
                val combinedPcm = ShortArray(totalLen)
                var destPos = 0
                for (chunk in synthesizedChunks) {
                    System.arraycopy(chunk, 0, combinedPcm, destPos, chunk.size)
                    destPos += chunk.size
                }

                playPcm16SamplesWithVisualizer(
                    pcmSamples = combinedPcm,
                    sampleRate = sampleRate,
                    channelCount = 1
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Neural ONNX TTS failed (${t.message}), falling back to next tier", t)
                false
            }
        }
    }

    /**
     * Converts normalized float samples `[-1, 1]` to 16-bit signed PCM with automatic peak loudness boost
     * so quiet neural voice outputs are always clear and audible.
     */
    private fun normalizeFloatToPcm16(samples: FloatArray): ShortArray {
        var maxAbs = 0f
        for (s in samples) {
            val a = abs(s)
            if (a > maxAbs && a.isFinite()) maxAbs = a
        }
        val gain = if (maxAbs in 0.005f..0.82f) {
            (0.92f / maxAbs).coerceIn(1.0f, 3.0f)
        } else {
            1.0f
        }
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            val s = samples[i]
            val clamped = if (s.isFinite()) (s * gain).coerceIn(-1.0f, 1.0f) else 0f
            out[i] = (clamped * 32767.0f).toInt().toShort()
        }
        return out
    }

    /**
     * Plays 16-bit PCM samples through `AudioTrack` (`ENCODING_PCM_16BIT`), computing real-time
     * `_outputRms` and 5-band `_outputWaveBands` and waiting until the hardware playback head
     * finishes draining 100% of the buffer before stopping.
     */
    private suspend fun playPcm16SamplesWithVisualizer(
        pcmSamples: ShortArray,
        sampleRate: Int,
        channelCount: Int = 1
    ): Boolean = withContext(Dispatchers.Default) {
        if (pcmSamples.isEmpty()) return@withContext false

        val channelMask = if (channelCount >= 2) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }
        val safeSampleRate = sampleRate.coerceIn(8000, 48000)
        val minBufBytes = AudioTrack.getMinBufferSize(
            safeSampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        var track: AudioTrack? = null
        try {
            val audioConfig = resolveAssistantAudioConfig()
            track = AudioTrack.Builder()
                .setAudioAttributes(audioConfig.attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(safeSampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(minBufBytes * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                runCatching { track.release() }
                return@withContext false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                runCatching { track.setVolume(1.0f) }
            }

            activeAudioTrack = track
            _isSpeaking.value = true
            track.play()

            var totalShortsWritten = 0
            var offset = 0
            val chunkLen = 1024 * channelCount.coerceAtLeast(1)

            while (offset < pcmSamples.size && isActive) {
                val count = minOf(chunkLen, pcmSamples.size - offset)
                var sumSq = 0.0
                val bandAccum = FloatArray(5)
                val bandBucketSize = (count / 5).coerceAtLeast(1)

                for (i in 0 until count) {
                    val norm = pcmSamples[offset + i] / 32768.0f
                    sumSq += norm * norm
                    val bandIdx = (i / bandBucketSize).coerceIn(0, 4)
                    bandAccum[bandIdx] += abs(norm)
                }

                val rms = (sqrt(sumSq / count).toFloat() * 4.8f).coerceIn(0.10f, 1.0f)
                _outputRms.value = _outputRms.value * 0.3f + rms * 0.7f

                val prevBands = _outputWaveBands.value
                val nextBands = FloatArray(5) { idx ->
                    val avg = (bandAccum[idx] / bandBucketSize) * 6.2f
                    (prevBands[idx] * 0.3f + avg * 0.7f).coerceIn(0.08f, 1.0f)
                }
                _outputWaveBands.value = nextBands

                val written = track.write(pcmSamples, offset, count, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    Log.w(TAG, "AudioTrack.write returned error code: $written")
                    break
                }
                totalShortsWritten += written
                offset += count
            }

            val totalFrames = totalShortsWritten / channelCount.coerceAtLeast(1)
            if (totalFrames > 0 && isActive) {
                awaitAudioTrackDrain(track, totalFrames, safeSampleRate)
            }

            runCatching {
                track.stop()
                track.release()
            }
            if (activeAudioTrack === track) {
                activeAudioTrack = null
            }
            totalFrames > 0
        } catch (t: Throwable) {
            Log.w(TAG, "playPcm16SamplesWithVisualizer error: ${t.message}", t)
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

    /**
     * Waits until the hardware `AudioTrack` has actually played all queued frames out of the speaker
     * so short utterances are never cut off prematurely while still buffered.
     */
    private suspend fun awaitAudioTrackDrain(
        track: AudioTrack,
        totalFramesWritten: Int,
        sampleRate: Int
    ) {
        val expectedDurationMs = ((totalFramesWritten.toLong() * 1000L) / sampleRate.coerceAtLeast(8000))
        val maxWaitMs = (expectedDurationMs + 900L).coerceIn(350L, 60_000L)
        val startMs = SystemClock.elapsedRealtime()
        var tick = 0f

        while (coroutineContext.isActive) {
            val elapsed = SystemClock.elapsedRealtime() - startMs
            if (elapsed >= maxWaitMs) break

            val headPos = runCatching {
                track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            }.getOrDefault(totalFramesWritten.toLong())

            if (headPos >= totalFramesWritten - (sampleRate / 25)) {
                // Allow final ~60ms DAC/speaker tail
                delay(65)
                break
            }

            tick += 0.35f
            _outputRms.value = (_outputRms.value * 0.7f + 0.25f * abs(sin(tick))).coerceIn(0.12f, 0.85f)
            delay(30)
        }
    }

    private fun resolveOrLoadOfflineTtsLocked(
        langCode: String,
        settings: LunaSettings,
        strictLangMatch: Boolean
    ): OfflineTts? {
        val profile = NpuHardwareManager.detectHardwareProfile(appContext)
        val threads = profile.recommendedCpuThreads.coerceIn(2, 4)
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

        // 2. Build ordered candidate list
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
                selectedModelId?.takeIf {
                    it == LocalModelId.TTS_PIPER_DE_DII || it == LocalModelId.TTS_PIPER_DE_KERSTIN
                }?.let { add(it) }
                add(LocalModelId.TTS_PIPER_DE_DII)
                add(LocalModelId.TTS_PIPER_DE_KERSTIN)
                if (!strictLangMatch) {
                    selectedModelId?.let { add(it) }
                    add(LocalModelId.TTS_PIPER_EN_AMY)
                    add(LocalModelId.TTS_KOKORO_EN_HEART)
                }
            } else {
                selectedModelId?.takeIf {
                    it == LocalModelId.TTS_PIPER_EN_AMY || it == LocalModelId.TTS_KOKORO_EN_HEART
                }?.let { add(it) }
                add(LocalModelId.TTS_PIPER_EN_AMY)
                add(LocalModelId.TTS_KOKORO_EN_HEART)
                if (!strictLangMatch) {
                    selectedModelId?.let { add(it) }
                    add(LocalModelId.TTS_PIPER_DE_DII)
                    add(LocalModelId.TTS_PIPER_DE_KERSTIN)
                }
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
            val lexiconFile = File(modelDir, "lexicon.txt").takeIf { it.exists() }
                ?: File(modelDir, "lexicon-us-en.txt").takeIf { it.exists() }
                ?: findFileRecursively(root) { it.isFile && it.name.startsWith("lexicon") && it.name.endsWith(".txt") }

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
                    lexicon = lexiconFile?.absolutePath ?: "",
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
                    lexicon = lexiconFile?.absolutePath ?: "",
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

    /**
     * Tier 2: Bulletproof Android System `TextToSpeech` playback.
     * - Filters out any uninstalled `Voice` (`KEY_FEATURE_NOT_INSTALLED` / `"notInstalled"`).
     * - First attempts `synthesizeToFile` to a temporary WAV and plays verified PCM samples through
     *   our own `AudioTrack` (guaranteeing non-empty audio and real equalizer waveform animation).
     * - If `synthesizeToFile` is not supported by the OEM engine, falls back to direct `tts.speak()`
     *   with `STREAM_MUSIC` and `KEY_PARAM_VOLUME = 1.0f`.
     */
    private suspend fun trySpeakWithAndroidSystemTts(
        cleanText: String,
        langCode: String,
        settings: LunaSettings
    ): Boolean {
        val tts = ensureAndroidTtsReady() ?: return false
        attachUtteranceListener(tts)

        val isDe = langCode.startsWith("de")
        val targetLocale = if (isDe) Locale.GERMANY else Locale.US
        val fallbackLocale = if (isDe) Locale("de") else Locale.ENGLISH

        val installedFemaleVoice = findInstalledFemaleVoice(tts, targetLocale)

        // Stage 2A: Try synthesizeToFile -> verified WAV -> AudioTrack
        val wavSuccess = trySynthesizeToWavAndPlay(
            tts = tts,
            text = cleanText,
            primaryLocale = targetLocale,
            fallbackLocale = fallbackLocale,
            preferredVoice = installedFemaleVoice,
            settings = settings
        )
        if (wavSuccess) return true

        if (!coroutineContext.isActive) return false

        // Stage 2B: Fallback to direct tts.speak() with default installed language voice
        return tryDirectAndroidTtsSpeak(
            tts = tts,
            text = cleanText,
            primaryLocale = targetLocale,
            fallbackLocale = fallbackLocale,
            settings = settings
        )
    }

    private fun findInstalledFemaleVoice(tts: TextToSpeech, targetLocale: Locale): Voice? {
        return runCatching {
            val allVoices = tts.voices ?: return null
            val installedForLang = allVoices.filter { v ->
                val sameLang = v.locale.language.equals(targetLocale.language, ignoreCase = true)
                val features = v.features ?: emptySet()
                val notInstalled = features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) ||
                    features.any { it.equals("notInstalled", ignoreCase = true) }
                sameLang && !notInstalled && !v.isNetworkConnectionRequired
            }
            installedForLang.firstOrNull { v ->
                val n = v.name.lowercase(Locale.ROOT)
                n.contains("female") || n.contains("#f") || n.contains("-f-") ||
                    n.contains("tpf") || n.contains("sfg") || n.contains("iob") ||
                    n.contains("tha") || n.contains("deb") || n.contains("nfh")
            }
        }.getOrNull()
    }

    private fun configureAndroidTtsLanguage(
        tts: TextToSpeech,
        primaryLocale: Locale,
        fallbackLocale: Locale,
        preferredVoice: Voice?,
        settings: LunaSettings
    ): AssistantAudioConfig {
        val audioConfig = resolveAssistantAudioConfig()
        runCatching { tts.setAudioAttributes(audioConfig.attributes) }
        val langRes = runCatching { tts.setLanguage(primaryLocale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (langRes == TextToSpeech.LANG_MISSING_DATA || langRes == TextToSpeech.LANG_NOT_SUPPORTED) {
            runCatching { tts.setLanguage(fallbackLocale) }
        }
        if (preferredVoice != null) {
            runCatching { tts.voice = preferredVoice }
        }
        runCatching {
            tts.setSpeechRate(settings.ttsSpeed.coerceIn(0.6f, 1.8f))
            tts.setPitch(settings.ttsPitch.coerceIn(0.8f, 1.4f))
        }
        return audioConfig
    }

    private suspend fun trySynthesizeToWavAndPlay(
        tts: TextToSpeech,
        text: String,
        primaryLocale: Locale,
        fallbackLocale: Locale,
        preferredVoice: Voice?,
        settings: LunaSettings
    ): Boolean = withContext(Dispatchers.IO) {
        val voiceAttempts = if (preferredVoice != null) listOf(preferredVoice, null) else listOf(null)

        for (voiceCandidate in voiceAttempts) {
            if (!isActive) return@withContext false
            val wavFile = File(appContext.cacheDir, "luna_tts_${UUID.randomUUID()}.wav")
            try {
                val audioConfig = configureAndroidTtsLanguage(tts, primaryLocale, fallbackLocale, voiceCandidate, settings)

                val utteranceId = "wav_${UUID.randomUUID()}"
                val deferred = CompletableDeferred<Boolean>()
                utteranceWaiters[utteranceId] = deferred

                val params = Bundle().apply {
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, audioConfig.streamType)
                }

                val callRes = runCatching {
                    tts.synthesizeToFile(text, params, wavFile, utteranceId)
                }.getOrDefault(TextToSpeech.ERROR)

                if (callRes != TextToSpeech.SUCCESS) {
                    utteranceWaiters.remove(utteranceId)
                    continue
                }

                val synthOk = withTimeoutOrNull(15_000L) { deferred.await() } == true
                utteranceWaiters.remove(utteranceId)

                if (synthOk && wavFile.exists() && wavFile.length() > 256L) {
                    val decoded = decodeWavFileToPcm16(wavFile)
                    if (decoded != null && decoded.samples.isNotEmpty()) {
                        val played = playPcm16SamplesWithVisualizer(
                            pcmSamples = decoded.samples,
                            sampleRate = decoded.sampleRate,
                            channelCount = decoded.channels
                        )
                        if (played) return@withContext true
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "synthesizeToFile attempt failed: ${t.message}")
            } finally {
                runCatching { wavFile.delete() }
            }
        }
        false
    }

    private data class DecodedWavPcm16(
        val samples: ShortArray,
        val sampleRate: Int,
        val channels: Int
    )

    /**
     * Parses a standard RIFF/WAVE file generated by Android `TextToSpeech.synthesizeToFile`
     * into normalized 16-bit signed PCM samples.
     */
    private fun decodeWavFileToPcm16(wavFile: File): DecodedWavPcm16? {
        return runCatching {
            val bytes = wavFile.readBytes()
            if (bytes.size < 44) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            // Verify "RIFF" and "WAVE"
            if (bytes[0] != 'R'.code.toByte() || bytes[1] != 'I'.code.toByte() ||
                bytes[8] != 'W'.code.toByte() || bytes[9] != 'A'.code.toByte()
            ) {
                return null
            }

            var audioFormat = 1
            var numChannels = 1
            var sampleRate = 22050
            var bitsPerSample = 16
            var dataOffset = 44
            var dataSize = bytes.size - 44

            var pos = 12
            while (pos + 8 <= bytes.size) {
                val chunkId = String(bytes, pos, 4, Charsets.US_ASCII)
                val chunkSize = buf.getInt(pos + 4)
                val chunkDataStart = pos + 8
                if (chunkSize < 0 || chunkDataStart > bytes.size) break

                if (chunkId == "fmt " && chunkDataStart + 16 <= bytes.size) {
                    audioFormat = buf.getShort(chunkDataStart).toInt() and 0xFFFF
                    numChannels = (buf.getShort(chunkDataStart + 2).toInt() and 0xFFFF).coerceIn(1, 2)
                    sampleRate = buf.getInt(chunkDataStart + 4).coerceIn(8000, 48000)
                    bitsPerSample = (buf.getShort(chunkDataStart + 14).toInt() and 0xFFFF)
                } else if (chunkId == "data") {
                    dataOffset = chunkDataStart
                    dataSize = minOf(chunkSize, bytes.size - chunkDataStart)
                    break
                }
                pos = chunkDataStart + chunkSize + (chunkSize and 1)
            }

            if (dataSize <= 0 || dataOffset >= bytes.size) return null

            val floatSamples: FloatArray = when {
                audioFormat == 3 && bitsPerSample == 32 -> {
                    val count = dataSize / 4
                    FloatArray(count) { i ->
                        buf.getFloat(dataOffset + i * 4)
                    }
                }
                bitsPerSample == 8 -> {
                    FloatArray(dataSize) { i ->
                        ((bytes[dataOffset + i].toInt() and 0xFF) - 128) / 128.0f
                    }
                }
                else -> {
                    val count = dataSize / 2
                    FloatArray(count) { i ->
                        buf.getShort(dataOffset + i * 2) / 32768.0f
                    }
                }
            }

            val normalizedPcm = normalizeFloatToPcm16(floatSamples)
            DecodedWavPcm16(
                samples = normalizedPcm,
                sampleRate = sampleRate,
                channels = numChannels
            )
        }.getOrNull()
    }

    private suspend fun tryDirectAndroidTtsSpeak(
        tts: TextToSpeech,
        text: String,
        primaryLocale: Locale,
        fallbackLocale: Locale,
        settings: LunaSettings
    ): Boolean {
        val utteranceId = "speak_${UUID.randomUUID()}"
        val deferred = CompletableDeferred<Boolean>()
        utteranceWaiters[utteranceId] = deferred

        val started = runCatching {
            val audioConfig = configureAndroidTtsLanguage(tts, primaryLocale, fallbackLocale, preferredVoice = null, settings = settings)
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, audioConfig.streamType)
            }
            val res = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            res == TextToSpeech.SUCCESS
        }.getOrDefault(false)

        if (!started) {
            utteranceWaiters.remove(utteranceId)
            return false
        }

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

        return try {
            withTimeoutOrNull(45_000L) {
                deferred.await()
            } == true
        } finally {
            animJob.cancel()
            utteranceWaiters.remove(utteranceId)
        }
    }

    /**
     * Tier 3: Network Neural TTS HTTPS Audio Fallback.
     * Guarantees speech output works even if the user has not downloaded a local Sherpa-ONNX model
     * AND the device's Android System TTS engine is broken, disabled, or missing voice data.
     */
    private suspend fun trySpeakWithNetworkTtsFallback(
        cleanText: String,
        langCode: String,
        settings: LunaSettings
    ): Boolean = withContext(Dispatchers.IO) {
        val tl = if (langCode.startsWith("de")) "de" else "en"
        val chunks = splitIntoNetworkTtsChunks(cleanText, maxChars = 180)
        if (chunks.isEmpty()) return@withContext false

        var anyPlayed = false
        for (chunk in chunks) {
            if (!isActive) break
            val tempMp3 = File(appContext.cacheDir, "luna_net_tts_${UUID.randomUUID()}.mp3")
            try {
                val encoded = URLEncoder.encode(chunk, "UTF-8")
                val url = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=$tl&q=$encoded"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
                    .get()
                    .build()

                val downloaded = httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use false
                    val bodyBytes = resp.body?.bytes() ?: return@use false
                    if (bodyBytes.size < 128) return@use false
                    tempMp3.writeBytes(bodyBytes)
                    true
                }

                if (downloaded && tempMp3.exists() && isActive) {
                    val chunkPlayed = playAudioFileWithMediaPlayer(tempMp3, settings.ttsSpeed)
                    if (chunkPlayed) {
                        anyPlayed = true
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Network TTS fallback chunk failed: ${t.message}")
            } finally {
                runCatching { tempMp3.delete() }
            }
        }
        anyPlayed
    }

    private suspend fun playAudioFileWithMediaPlayer(
        audioFile: File,
        speed: Float
    ): Boolean = withContext(Dispatchers.IO) {
        val doneDeferred = CompletableDeferred<Boolean>()
        var mp: MediaPlayer? = null
        try {
            val audioConfig = resolveAssistantAudioConfig()
            mp = MediaPlayer().apply {
                setAudioAttributes(audioConfig.attributes)
                setDataSource(audioFile.absolutePath)
                setVolume(1.0f, 1.0f)
                setOnCompletionListener {
                    doneDeferred.complete(true)
                }
                setOnErrorListener { _, _, _ ->
                    doneDeferred.complete(false)
                    true
                }
                prepare()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching {
                    mp.playbackParams = PlaybackParams().setSpeed(speed.coerceIn(0.75f, 1.5f))
                }
            }

            activeMediaPlayer = mp
            _isSpeaking.value = true
            mp.start()

            val animJob = scope.launch {
                var tick = 0f
                while (isActive && !doneDeferred.isCompleted) {
                    tick += 0.38f
                    _outputRms.value = (0.45f + 0.30f * abs(sin(tick))).coerceIn(0.20f, 0.88f)
                    _outputWaveBands.value = FloatArray(5) { idx ->
                        (0.32f + 0.55f * abs(sin(tick + idx * 0.75f))).coerceIn(0.15f, 0.94f)
                    }
                    delay(45)
                }
            }

            val ok = withTimeoutOrNull(30_000L) { doneDeferred.await() } == true
            animJob.cancel()
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "MediaPlayer fallback playback error: ${t.message}")
            false
        } finally {
            runCatching {
                mp?.stop()
            }
            runCatching {
                mp?.release()
            }
            if (activeMediaPlayer === mp) {
                activeMediaPlayer = null
            }
        }
    }

    private fun splitIntoNetworkTtsChunks(text: String, maxChars: Int): List<String> {
        val sentences = splitIntoSentences(text)
        val result = mutableListOf<String>()
        for (sentence in sentences) {
            if (sentence.length <= maxChars) {
                result.add(sentence)
            } else {
                val words = sentence.split(" ")
                val sb = StringBuilder()
                for (w in words) {
                    if (sb.length + w.length + 1 > maxChars && sb.isNotEmpty()) {
                        result.add(sb.toString().trim())
                        sb.clear()
                    }
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(w)
                }
                if (sb.isNotEmpty()) {
                    result.add(sb.toString().trim())
                }
            }
        }
        return result.filter { it.isNotEmpty() }
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
        val deHits = listOf(" und ", " ist ", " nicht ", " ich ", " das ", " die ", " der ", " für ", " mit ", " auf ", "ä", "ö", "ü", "ß", " hallo ")
            .count { lower.contains(it) }
        val enHits = listOf(" the ", " and ", " is ", " you ", " that ", " with ", " for ", " have ", " this ", " hi ", " hello ")
            .count { lower.contains(it) }
        return when {
            deHits > enHits -> "de"
            enHits > deHits -> "en"
            else -> fallback
        }
    }
}
