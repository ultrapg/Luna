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

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Captures 16 kHz mono 16-bit PCM audio on-device for Desert Ant Labs `Ear` & `Voz`
 * and computes real-time RMS + 5-band audio spectrum amplitudes to drive the Gemini-style
 * glowing wave & pill animation. Also supports automatic end-of-utterance VAD detection.
 */
class LunaAudioRecorder {
    companion object {
        private const val TAG = "LunaAudioRecorder"
        const val SAMPLE_RATE = 16000
        private const val INITIAL_CAPACITY_SAMPLES = SAMPLE_RATE * 15
        private const val MAX_RECORDING_SAMPLES = SAMPLE_RATE * 240 // 4 minutes max
        private const val SPEECH_RMS_THRESHOLD = 0.048f
    }

    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var recordingJob: Job? = null

    private val bufferLock = Any()
    private var pcmBuffer: FloatArray? = null
    private var pcmSize: Int = 0

    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    private val _waveBands = MutableStateFlow(floatArrayOf(0.1f, 0.1f, 0.1f, 0.1f, 0.1f))
    val waveBands: StateFlow<FloatArray> = _waveBands.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val controllerMutex = Mutex()

    @SuppressLint("MissingPermission")
    suspend fun startRecording(
        scope: CoroutineScope,
        autoStopOnSilence: Boolean = true,
        silenceTimeoutMs: Long = 1600L,
        onAutoSilenceDetected: (() -> Unit)? = null
    ): Boolean = controllerMutex.withLock {
        stopInternalLocked()

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, audioFormat)
        val bufferSize = if (minBuf > 0) maxOf(minBuf * 2, 4096) else 8192

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                channelConfig,
                audioFormat,
                bufferSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioRecord: ${e.message}", e)
            return@withLock false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            return@withLock false
        }

        audioRecord = record
        synchronized(bufferLock) {
            pcmBuffer = FloatArray(INITIAL_CAPACITY_SAMPLES)
            pcmSize = 0
        }

        runCatching {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
            }
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(record.audioSessionId)?.apply { enabled = true }
            }
        }

        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                releaseHardware()
                return@withLock false
            }
        } catch (e: Exception) {
            releaseHardware()
            return@withLock false
        }

        _isRecording.value = true
        val startMs = SystemClock.elapsedRealtime()

        recordingJob = scope.launch(Dispatchers.IO) {
            val shortBuffer = ShortArray(1024)
            var hasHeardSpeech = false
            var lastSpeechMs = SystemClock.elapsedRealtime()

            while (isActive && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val readCount = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: 0
                if (readCount > 0) {
                    var sumSquares = 0.0
                    val bandAccum = FloatArray(5)
                    val bandBucketSize = (readCount / 5).coerceAtLeast(1)

                    synchronized(bufferLock) {
                        val currentBuf = pcmBuffer ?: FloatArray(INITIAL_CAPACITY_SAMPLES).also { pcmBuffer = it }
                        val available = MAX_RECORDING_SAMPLES - pcmSize
                        val toCopy = min(readCount, available)
                        val targetBuf = if (pcmSize + toCopy > currentBuf.size) {
                            val grown = min(MAX_RECORDING_SAMPLES, maxOf(pcmSize + toCopy, currentBuf.size + SAMPLE_RATE * 20))
                            runCatching { currentBuf.copyOf(grown).also { pcmBuffer = it } }.getOrDefault(currentBuf)
                        } else {
                            currentBuf
                        }
                        val actualCopy = min(toCopy, targetBuf.size - pcmSize)
                        for (i in 0 until actualCopy) {
                            val norm = shortBuffer[i] / 32768.0f
                            sumSquares += norm * norm
                            targetBuf[pcmSize + i] = norm
                            val bandIdx = (i / bandBucketSize).coerceIn(0, 4)
                            bandAccum[bandIdx] += abs(norm)
                        }
                        pcmSize += actualCopy
                    }

                    val rawRms = sqrt(sumSquares / readCount).toFloat()
                    val scaledRms = (rawRms * 5.4f).coerceIn(0f, 1f)
                    _rmsLevel.value = _rmsLevel.value * 0.35f + scaledRms * 0.65f

                    val prevBands = _waveBands.value
                    val nextBands = FloatArray(5) { idx ->
                        val avg = (bandAccum[idx] / bandBucketSize) * 6.8f
                        (prevBands[idx] * 0.3f + avg * 0.7f).coerceIn(0.08f, 1.0f)
                    }
                    _waveBands.value = nextBands

                    val now = SystemClock.elapsedRealtime()
                    if (scaledRms >= SPEECH_RMS_THRESHOLD) {
                        hasHeardSpeech = true
                        lastSpeechMs = now
                    } else if (autoStopOnSilence && hasHeardSpeech && (now - startMs > 1200L)) {
                        if (now - lastSpeechMs >= silenceTimeoutMs) {
                            onAutoSilenceDetected?.invoke()
                            break
                        }
                    }
                }
            }
        }
        true
    }

    suspend fun stopRecording(): FloatArray = controllerMutex.withLock {
        stopInternalLocked()
        synchronized(bufferLock) {
            val buf = pcmBuffer
            val size = pcmSize
            pcmBuffer = null
            pcmSize = 0
            if (buf == null || size <= 0) {
                FloatArray(0)
            } else if (size == buf.size) {
                buf
            } else {
                runCatching { buf.copyOf(size) }.getOrDefault(buf)
            }
        }
    }

    private suspend fun stopInternalLocked() {
        runCatching {
            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED &&
                audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING
            ) {
                audioRecord?.stop()
            }
        }
        recordingJob?.cancelAndJoin()
        recordingJob = null
        releaseHardware()
        _isRecording.value = false
        _rmsLevel.value = 0f
        _waveBands.value = floatArrayOf(0.08f, 0.08f, 0.08f, 0.08f, 0.08f)
    }

    private fun releaseHardware() {
        runCatching { audioRecord?.release() }
        runCatching { noiseSuppressor?.release() }
        runCatching { agc?.release() }
        audioRecord = null
        noiseSuppressor = null
        agc = null
    }
}
