/*
 * Luna — Native Android Digital Assistant
 * Copyright (C) 2026 ultrapg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.ultrapg.luna.core.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ultrapg.luna.MainActivity
import com.ultrapg.luna.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class LocalModelId(
    val id: String,
    val displayNameEn: String,
    val displayNameDe: String,
    val roleEn: String,
    val roleDe: String,
    val sizeMb: Int,
    val isDesertAnt: Boolean,
    val isTarBz2Archive: Boolean = false
) {
    VOZ(
        id = "voz",
        displayNameEn = "Desert Ant Voz (STT)",
        displayNameDe = "Desert Ant Voz (STT)",
        roleEn = "Required • Local speech-to-text (EN, DE & 23+ languages)",
        roleDe = "Erforderlich • Lokale Spracherkennung (DE, EN & 23+ Sprachen)",
        sizeMb = 1187,
        isDesertAnt = true
    ),
    EAR(
        id = "ear",
        displayNameEn = "Desert Ant Ear (Language ID)",
        displayNameDe = "Desert Ant Ear (Spracherkennung)",
        roleEn = "Recommended • Auto-detects English & German speech",
        roleDe = "Empfohlen • Erkennt automatisch Deutsch & Englisch",
        sizeMb = 10,
        isDesertAnt = true
    ),
    TTS_PIPER_EN_AMY(
        id = "piper_en_amy",
        displayNameEn = "Amy — English Female",
        displayNameDe = "Amy — Englisch (Weiblich)",
        roleEn = "Fast US English neural voice (VITS)",
        roleDe = "Schnelle US-englische Frauenstimme (VITS)",
        sizeMb = 64,
        isDesertAnt = false,
        isTarBz2Archive = true
    ),
    TTS_KOKORO_EN_HEART(
        id = "kokoro_en_heart",
        displayNameEn = "Kokoro 82M — Studio Female",
        displayNameDe = "Kokoro 82M — Studio (Weiblich)",
        roleEn = "Expressive English female voice (af_heart / af_bella)",
        roleDe = "Ausdrucksstarke englische Frauenstimme (af_heart / af_bella)",
        sizeMb = 98,
        isDesertAnt = false,
        isTarBz2Archive = true
    ),
    TTS_PIPER_DE_DII(
        id = "piper_de_dii",
        displayNameEn = "Dii High — German Female",
        displayNameDe = "Dii High — Deutsch (Weiblich)",
        roleEn = "High-quality natural German female voice (VITS)",
        roleDe = "Hochwertige natürliche deutsche Frauenstimme (VITS)",
        sizeMb = 64,
        isDesertAnt = false,
        isTarBz2Archive = true
    ),
    TTS_PIPER_DE_KERSTIN(
        id = "piper_de_kerstin",
        displayNameEn = "Kerstin — German Female",
        displayNameDe = "Kerstin — Deutsch (Weiblich)",
        roleEn = "Ultra-low-latency German female voice (VITS)",
        roleDe = "Ultraschnelle deutsche Frauenstimme (VITS)",
        sizeMb = 64,
        isDesertAnt = false,
        isTarBz2Archive = true
    );
}

data class ModelFileArtifact(
    val relativePath: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String
)

enum class ModelQueueStatus {
    QUEUED,
    DOWNLOADING,
    EXTRACTING,
    VERIFYING,
    DONE,
    PAUSED,
    ERROR
}

data class DownloadSessionState(
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val isCompleted: Boolean = false,
    val activeModel: LocalModelId? = null,
    val currentFileName: String = "",
    val currentModelIndex: Int = 0,
    val totalModelsInQueue: Int = 0,
    val modelDownloadedBytes: Long = 0L,
    val modelTotalBytes: Long = 0L,
    val overallDownloadedBytes: Long = 0L,
    val overallTotalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long? = null,
    val statusNote: String? = null,
    val errorMessage: String? = null,
    val modelStatuses: Map<LocalModelId, ModelQueueStatus> = emptyMap()
) {
    val modelProgress: Float
        get() = if (modelTotalBytes > 0L) {
            (modelDownloadedBytes.toDouble() / modelTotalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }

    val overallProgress: Float
        get() = if (overallTotalBytes > 0L) {
            (overallDownloadedBytes.toDouble() / overallTotalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }

    val formattedSpeed: String
        get() = formatSpeed(speedBytesPerSec)

    val formattedEta: String
        get() = formatEta(etaSeconds)

    val formattedOverallBytes: String
        get() = "${formatBytesMb(overallDownloadedBytes)} / ${formatBytesMb(overallTotalBytes)}"

    companion object {
        fun formatBytesMb(bytes: Long): String {
            val mb = bytes.toDouble() / (1024.0 * 1024.0)
            return if (mb >= 100.0) {
                String.format(Locale.US, "%.0f MB", mb)
            } else {
                String.format(Locale.US, "%.1f MB", mb)
            }
        }

        fun formatSpeed(bytesPerSec: Long): String {
            if (bytesPerSec <= 0L) return "Connecting..."
            val kb = bytesPerSec.toDouble() / 1024.0
            return if (kb >= 1024.0) {
                String.format(Locale.US, "%.2f MB/s", kb / 1024.0)
            } else {
                String.format(Locale.US, "%.0f KB/s", kb)
            }
        }

        fun formatEta(seconds: Long?): String {
            if (seconds == null || seconds < 0L) return "Calculating..."
            if (seconds == 0L) return "Almost done..."
            val hours = seconds / 3600L
            val mins = (seconds % 3600L) / 60L
            val secs = seconds % 60L
            return when {
                hours > 0L -> String.format(Locale.US, "~%dh %02dm left", hours, mins)
                mins > 0L -> String.format(Locale.US, "~%dm %02ds left", mins, secs)
                else -> String.format(Locale.US, "~%ds left", secs)
            }
        }
    }
}

object LocalModelStorage {
    private const val VERIFIED_STAMP_FILE = ".luna_verified"

    private val DESERT_ANT_REVISIONS = mapOf(
        LocalModelId.VOZ to "v0.4.0",
        LocalModelId.EAR to "v0.2.0"
    )

    fun desertAntRevision(model: LocalModelId): String {
        return DESERT_ANT_REVISIONS[model] ?: "v1.0.0"
    }

    fun modelRootDir(context: Context, model: LocalModelId): File {
        val appContext = context.applicationContext
        return if (model.isDesertAnt) {
            File(appContext.cacheDir, "desert-ant-models/${model.id}/${desertAntRevision(model)}")
        } else {
            File(appContext.filesDir, "luna-tts-models/${model.id}")
        }
    }

    fun customTtsDir(context: Context): File {
        return File(context.applicationContext.filesDir, "luna-tts-models/custom_voice").apply { mkdirs() }
    }

    fun isModelDownloaded(context: Context, model: LocalModelId): Boolean {
        val dir = modelRootDir(context, model)
        if (!dir.exists() || !dir.isDirectory) return false

        if (model.isDesertAnt) {
            val manifest = File(File(dir, ".dal-meta"), "manifest")
            if (!manifest.exists() || manifest.length() == 0L) return false
            val artifacts = ModelDownloadManager.MODEL_SPECS[model] ?: return false
            for (artifact in artifacts) {
                val f = File(dir, artifact.relativePath)
                val part = File(dir, "${artifact.relativePath}.part")
                if (part.exists() || !f.exists() || f.length() != artifact.sizeBytes) {
                    return false
                }
            }
            return true
        } else {
            val stamp = File(dir, VERIFIED_STAMP_FILE)
            if (!stamp.exists()) return false
            return when (model) {
                LocalModelId.TTS_PIPER_EN_AMY ->
                    File(dir, "vits-piper-en_US-amy-medium/en_US-amy-medium.onnx").exists()
                LocalModelId.TTS_KOKORO_EN_HEART ->
                    File(dir, "kokoro-int8-en-v0_19/model.int8.onnx").exists()
                LocalModelId.TTS_PIPER_DE_DII ->
                    File(dir, "vits-piper-de_DE-dii-high/de_DE-dii-high.onnx").exists()
                LocalModelId.TTS_PIPER_DE_KERSTIN ->
                    File(dir, "vits-piper-de_DE-kerstin-low/de_DE-kerstin-low.onnx").exists()
                else -> false
            }
        }
    }

    @Synchronized
    fun writeDesertAntManifestAndStamp(targetDir: File, artifacts: List<ModelFileArtifact>) {
        if (!targetDir.exists()) targetDir.mkdirs()
        val metaDir = File(targetDir, ".dal-meta")
        metaDir.mkdirs()
        val manifestFile = File(metaDir, "manifest")
        val rLines = artifacts.joinToString("\n") { "R\t${it.relativePath}" }
        val fLines = artifacts.joinToString("\n") { "F\t${it.relativePath}\t${it.sizeBytes}\t${it.sha256}" }
        manifestFile.writeText("DAL1\n$rLines\n$fLines\n")
        File(targetDir, VERIFIED_STAMP_FILE).writeText("verified=true\n")
    }

    @Synchronized
    fun markModelVerified(targetDir: File) {
        if (!targetDir.exists()) targetDir.mkdirs()
        File(targetDir, VERIFIED_STAMP_FILE).writeText("verified=true\n")
    }

    @Synchronized
    fun deleteModel(context: Context, model: LocalModelId) {
        val dir = modelRootDir(context, model)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }
}

object ModelDownloadManager {
    private const val TAG = "ModelDownloadManager"
    private const val MAX_STALL_RETRIES = 12
    private const val MAX_INTEGRITY_RETRIES = 3
    private const val CONNECT_TIMEOUT_MS = 45_000
    private const val READ_TIMEOUT_MS = 90_000

    val MODEL_SPECS: Map<LocalModelId, List<ModelFileArtifact>> = mapOf(
        LocalModelId.VOZ to listOf(
            ModelFileArtifact(
                relativePath = "encoder.tflite",
                url = "https://huggingface.co/desert-ant-labs/voz/resolve/v0.4.0/encoder.tflite",
                sizeBytes = 1_207_525_456L,
                sha256 = "1152abb5fce2cf50b57b1cd80a1bf70320c32b20656f835bfe08c7faa5c3f885"
            ),
            ModelFileArtifact(
                relativePath = "mel.tflite",
                url = "https://huggingface.co/desert-ant-labs/voz/resolve/v0.4.0/mel.tflite",
                sizeBytes = 1_663_424L,
                sha256 = "505d80fc1be255c115c715a6cffdb55e51ff22465bca820e5feb59f0cccabe09"
            ),
            ModelFileArtifact(
                relativePath = "decoder.tflite",
                url = "https://huggingface.co/desert-ant-labs/voz/resolve/v0.4.0/decoder.tflite",
                sizeBytes = 24_503_072L,
                sha256 = "429ff2b574455733b388f1667d05d6764ac6086417b94d4cc24edabd25d52591"
            ),
            ModelFileArtifact(
                relativePath = "meta.litert.json",
                url = "https://huggingface.co/desert-ant-labs/voz/resolve/v0.4.0/meta.litert.json",
                sizeBytes = 579L,
                sha256 = "a8ad38f4144d12445dce0e32a723859816596c1c667906c062df97c94a81e741"
            ),
            ModelFileArtifact(
                relativePath = "vocab.json",
                url = "https://huggingface.co/desert-ant-labs/voz/resolve/v0.4.0/vocab.json",
                sizeBytes = 78_654L,
                sha256 = "3eb2f73e8853dce5156c2ed97c0b1bb84eb4ebd201eb1761d15977fc1ea31490"
            ),
            ModelFileArtifact(
                relativePath = "embedding.f16",
                url = "https://huggingface.co/desert-ant-labs/voz/resolve/v0.4.0/embedding.f16",
                sizeBytes = 10_487_040L,
                sha256 = "07163e5f6f8a68f9ffeed7a8ff770c68098c2db216232321912188bd614b9a72"
            )
        ),
        LocalModelId.EAR to listOf(
            ModelFileArtifact(
                relativePath = "ear.tflite",
                url = "https://huggingface.co/desert-ant-labs/ear/resolve/v0.2.0/ear.tflite",
                sizeBytes = 10_671_552L,
                sha256 = "a19b43892abe53afba50122a861b10fd76f312a5027a5c659b0738081135feeb"
            ),
            ModelFileArtifact(
                relativePath = "languages.json",
                url = "https://huggingface.co/desert-ant-labs/ear/resolve/v0.2.0/languages.json",
                sizeBytes = 628L,
                sha256 = "0a08f30dec26ad25730a1fa9375a5d693f5a159032d0cd7818007b7dbc3eae18"
            ),
            ModelFileArtifact(
                relativePath = "ear_meta.json",
                url = "https://huggingface.co/desert-ant-labs/ear/resolve/v0.2.0/ear_meta.json",
                sizeBytes = 2_190L,
                sha256 = "538d66c747024d4ef2ae33aef273817c7baea21171430fef0c2713a66bb6af18"
            ),
            ModelFileArtifact(
                relativePath = "mel_filters.f32",
                url = "https://huggingface.co/desert-ant-labs/ear/resolve/v0.2.0/mel_filters.f32",
                sizeBytes = 64_328L,
                sha256 = "a3099fcba1a7f30f229b230011146ea0140db8de4f467b44aaa5de70bcc0f325"
            )
        ),
        LocalModelId.TTS_PIPER_EN_AMY to listOf(
            ModelFileArtifact(
                relativePath = "vits-piper-en_US-amy-medium.tar.bz2",
                url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-amy-medium.tar.bz2",
                sizeBytes = 67_223_746L,
                sha256 = "9a5d1fc497f85e8022b785bff5f8105203b1e33099ee6265203efc70b0cb0264"
            )
        ),
        LocalModelId.TTS_KOKORO_EN_HEART to listOf(
            ModelFileArtifact(
                relativePath = "kokoro-int8-en-v0_19.tar.bz2",
                url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-en-v0_19.tar.bz2",
                sizeBytes = 103_248_205L,
                sha256 = "c9f0dd393615805b0bab050c340834d5e684e732aec91c0e860cd30e982c08bd"
            )
        ),
        LocalModelId.TTS_PIPER_DE_DII to listOf(
            ModelFileArtifact(
                relativePath = "vits-piper-de_DE-dii-high.tar.bz2",
                url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-de_DE-dii-high.tar.bz2",
                sizeBytes = 67_200_058L,
                sha256 = "64d46d39afb0f53a49871f5415259ffe474f443696148930b34a003612b69110"
            )
        ),
        LocalModelId.TTS_PIPER_DE_KERSTIN to listOf(
            ModelFileArtifact(
                relativePath = "vits-piper-de_DE-kerstin-low.tar.bz2",
                url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-de_DE-kerstin-low.tar.bz2",
                sizeBytes = 67_107_768L,
                sha256 = "4bfe121d5f690a3b87acecd7ae1e70b48f4589ce6ae9f45a1809e6eb19fad97b"
            )
        )
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeJob: Job? = null

    private val _sessionState = MutableStateFlow(DownloadSessionState())
    val sessionState: StateFlow<DownloadSessionState> = _sessionState.asStateFlow()

    private val queueLock = Any()
    private val activeQueue = mutableListOf<LocalModelId>()
    private val completionCallbacks = mutableListOf<() -> Unit>()

    fun modelTotalBytes(model: LocalModelId): Long {
        return MODEL_SPECS[model]?.sumOf { it.sizeBytes } ?: (model.sizeMb * 1024L * 1024L)
    }

    fun getCachedOrPartialBytes(context: Context, model: LocalModelId): Long {
        if (LocalModelStorage.isModelDownloaded(context, model)) {
            return modelTotalBytes(model)
        }
        val artifacts = MODEL_SPECS[model] ?: return 0L
        val modelDir = LocalModelStorage.modelRootDir(context, model)
        if (!modelDir.exists()) return 0L
        var sum = 0L
        for (artifact in artifacts) {
            val finalFile = File(modelDir, artifact.relativePath)
            val partFile = File(modelDir, "${artifact.relativePath}.part")
            sum += when {
                finalFile.exists() && finalFile.length() == artifact.sizeBytes -> artifact.sizeBytes
                partFile.exists() -> min(partFile.length(), artifact.sizeBytes)
                else -> 0L
            }
        }
        return sum
    }

    @Synchronized
    fun startQueue(
        context: Context,
        models: List<LocalModelId>,
        onAllCompleted: (() -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        val requested = models.distinct()
        if (requested.isEmpty()) return

        synchronized(queueLock) {
            if (onAllCompleted != null) {
                completionCallbacks.add(onAllCompleted)
            }
            if (_sessionState.value.isRunning && activeJob?.isActive == true) {
                val currentStatuses = _sessionState.value.modelStatuses.toMutableMap()
                for (m in requested) {
                    if (m !in activeQueue) {
                        activeQueue.add(m)
                        currentStatuses[m] = if (LocalModelStorage.isModelDownloaded(appContext, m)) {
                            ModelQueueStatus.DONE
                        } else {
                            ModelQueueStatus.QUEUED
                        }
                    }
                }
                val snapshotQueue = activeQueue.toList()
                val newTotalBytes = snapshotQueue.sumOf { modelTotalBytes(it) }.coerceAtLeast(1L)
                val newDoneBytes = snapshotQueue.sumOf { getCachedOrPartialBytes(appContext, it) }
                updateState {
                    copy(
                        totalModelsInQueue = snapshotQueue.size,
                        overallDownloadedBytes = newDoneBytes,
                        overallTotalBytes = newTotalBytes,
                        modelStatuses = currentStatuses.toMap()
                    )
                }
                return
            }
            activeQueue.clear()
            activeQueue.addAll(requested)
        }

        val initialQueue = synchronized(queueLock) { activeQueue.toList() }
        val initialStatuses = initialQueue.associateWith { m ->
            if (LocalModelStorage.isModelDownloaded(appContext, m)) {
                ModelQueueStatus.DONE
            } else {
                ModelQueueStatus.QUEUED
            }
        }.toMutableMap()

        val totalBytesAll = initialQueue.sumOf { modelTotalBytes(it) }.coerceAtLeast(1L)
        val initialDoneBytes = initialQueue.sumOf { getCachedOrPartialBytes(appContext, it) }

        _sessionState.value = DownloadSessionState(
            isRunning = true,
            isPaused = false,
            isCompleted = false,
            activeModel = initialQueue.firstOrNull { initialStatuses[it] != ModelQueueStatus.DONE },
            currentModelIndex = 1,
            totalModelsInQueue = initialQueue.size,
            overallDownloadedBytes = initialDoneBytes,
            overallTotalBytes = totalBytesAll,
            modelStatuses = initialStatuses.toMap()
        )

        ModelDownloadService.start(appContext)

        activeJob = scope.launch {
            val speedTracker = RollingSpeedTracker()
            try {
                var idx = 0
                while (true) {
                    ensureActive()
                    val currentQueue = synchronized(queueLock) { activeQueue.toList() }
                    if (idx >= currentQueue.size) break
                    val model = currentQueue[idx]
                    val mTotal = modelTotalBytes(model)

                    if (LocalModelStorage.isModelDownloaded(appContext, model)) {
                        initialStatuses[model] = ModelQueueStatus.DONE
                        updateState {
                            copy(
                                activeModel = model,
                                currentModelIndex = idx + 1,
                                totalModelsInQueue = currentQueue.size,
                                modelDownloadedBytes = mTotal,
                                modelTotalBytes = mTotal,
                                overallDownloadedBytes = currentQueue.sumOf { getCachedOrPartialBytes(appContext, it) },
                                overallTotalBytes = currentQueue.sumOf { modelTotalBytes(it) }.coerceAtLeast(1L),
                                modelStatuses = initialStatuses.toMap()
                            )
                        }
                        idx++
                        continue
                    }

                    initialStatuses[model] = ModelQueueStatus.DOWNLOADING
                    updateState {
                        copy(
                            activeModel = model,
                            currentModelIndex = idx + 1,
                            totalModelsInQueue = currentQueue.size,
                            modelDownloadedBytes = getCachedOrPartialBytes(appContext, model),
                            modelTotalBytes = mTotal,
                            overallDownloadedBytes = currentQueue.sumOf { getCachedOrPartialBytes(appContext, it) },
                            overallTotalBytes = currentQueue.sumOf { modelTotalBytes(it) }.coerceAtLeast(1L),
                            statusNote = null,
                            errorMessage = null,
                            modelStatuses = initialStatuses.toMap()
                        )
                    }

                    downloadSingleModelResumable(
                        context = appContext,
                        model = model,
                        modelIndex = idx + 1,
                        statuses = initialStatuses,
                        speedTracker = speedTracker
                    )

                    val updatedQueue = synchronized(queueLock) { activeQueue.toList() }
                    initialStatuses[model] = ModelQueueStatus.DONE
                    updateState {
                        copy(
                            totalModelsInQueue = updatedQueue.size,
                            modelDownloadedBytes = mTotal,
                            modelTotalBytes = mTotal,
                            overallDownloadedBytes = updatedQueue.sumOf { getCachedOrPartialBytes(appContext, it) },
                            overallTotalBytes = updatedQueue.sumOf { modelTotalBytes(it) }.coerceAtLeast(1L),
                            statusNote = null,
                            modelStatuses = initialStatuses.toMap()
                        )
                    }
                    idx++
                }

                updateState {
                    copy(
                        isRunning = false,
                        isPaused = false,
                        isCompleted = true,
                        speedBytesPerSec = 0L,
                        etaSeconds = 0L,
                        statusNote = "All selected voice models downloaded & verified.",
                        errorMessage = null,
                        modelStatuses = initialStatuses.toMap()
                    )
                }

                val callbacks = synchronized(queueLock) {
                    val copy = completionCallbacks.toList()
                    completionCallbacks.clear()
                    copy
                }
                withContext(Dispatchers.Main) {
                    callbacks.forEach { runCatching { it.invoke() } }
                }
            } catch (ce: CancellationException) {
                val pausedStatuses = initialStatuses.mapValues { (_, st) ->
                    if (st == ModelQueueStatus.DOWNLOADING || st == ModelQueueStatus.VERIFYING || st == ModelQueueStatus.EXTRACTING) {
                        ModelQueueStatus.PAUSED
                    } else {
                        st
                    }
                }
                updateState {
                    copy(
                        isRunning = false,
                        isPaused = true,
                        speedBytesPerSec = 0L,
                        etaSeconds = null,
                        statusNote = "Download paused — progress saved on disk.",
                        modelStatuses = pausedStatuses
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Model download failed: ${t.message}", t)
                val active = _sessionState.value.activeModel
                if (active != null) {
                    initialStatuses[active] = ModelQueueStatus.ERROR
                }
                updateState {
                    copy(
                        isRunning = false,
                        isPaused = false,
                        speedBytesPerSec = 0L,
                        etaSeconds = null,
                        statusNote = null,
                        errorMessage = "Connection interrupted (${t.message ?: "Network error"}). Progress saved — tap Resume.",
                        modelStatuses = initialStatuses.toMap()
                    )
                }
            } finally {
                ModelDownloadService.stop(appContext)
            }
        }
    }

    @Synchronized
    fun pauseDownloads() {
        activeJob?.cancel()
        activeJob = null
    }

    private suspend fun downloadSingleModelResumable(
        context: Context,
        model: LocalModelId,
        modelIndex: Int,
        statuses: MutableMap<LocalModelId, ModelQueueStatus>,
        speedTracker: RollingSpeedTracker
    ) {
        val artifacts = MODEL_SPECS[model]
            ?: throw IOException("Missing catalog specification for ${model.id}")
        val targetDir = LocalModelStorage.modelRootDir(context, model)
        if (!targetDir.exists() && !targetDir.mkdirs() && !targetDir.exists()) {
            throw IOException("Cannot create model directory: ${targetDir.absolutePath}")
        }

        val mTotalBytes = artifacts.sumOf { it.sizeBytes }
        var completedBytesInModel = 0L

        for (artifact in artifacts) {
            coroutineContext.ensureActive()
            val finalFile = File(targetDir, artifact.relativePath)
            val partFile = File(targetDir, "${artifact.relativePath}.part")

            if (finalFile.exists() && finalFile.length() == artifact.sizeBytes) {
                if (verifySha256Incremental(finalFile, artifact.sha256)) {
                    completedBytesInModel += artifact.sizeBytes
                    continue
                } else {
                    finalFile.delete()
                }
            }

            val completedBeforeFile = completedBytesInModel
            val currentQueueSnapshot = synchronized(queueLock) { activeQueue.toList() }
            val otherModelsCachedBytes = currentQueueSnapshot
                .filter { it != model }
                .sumOf { getCachedOrPartialBytes(context, it) }
            val overallTotalSnapshot = currentQueueSnapshot.sumOf { modelTotalBytes(it) }.coerceAtLeast(1L)

            downloadFileWithAutoResume(
                urlStr = artifact.url,
                partFile = partFile,
                expectedSize = artifact.sizeBytes,
                expectedSha256 = artifact.sha256,
                onProgress = { fileCurrentBytes, statusHint ->
                    val modelNow = (completedBeforeFile + fileCurrentBytes).coerceAtMost(mTotalBytes)
                    val overallNow = (otherModelsCachedBytes + modelNow).coerceAtMost(overallTotalSnapshot)
                    val speed = speedTracker.currentSpeedBytesPerSec()
                    val remainingBytes = max(0L, overallTotalSnapshot - overallNow)
                    val eta = if (speed > 0L) remainingBytes / speed else null

                    updateState {
                        copy(
                            activeModel = model,
                            currentFileName = artifact.relativePath,
                            currentModelIndex = modelIndex,
                            totalModelsInQueue = currentQueueSnapshot.size,
                            modelDownloadedBytes = modelNow,
                            modelTotalBytes = mTotalBytes,
                            overallDownloadedBytes = overallNow,
                            overallTotalBytes = overallTotalSnapshot,
                            speedBytesPerSec = speed,
                            etaSeconds = eta,
                            statusNote = statusHint
                        )
                    }
                },
                onChunkReceived = { chunkBytes ->
                    speedTracker.recordBytes(chunkBytes)
                }
            )

            if (finalFile.exists()) finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }
            completedBytesInModel += artifact.sizeBytes
        }

        if (model.isDesertAnt) {
            LocalModelStorage.writeDesertAntManifestAndStamp(targetDir, artifacts)
        } else if (model.isTarBz2Archive) {
            statuses[model] = ModelQueueStatus.EXTRACTING
            updateState {
                copy(
                    statusNote = "Extracting neural voice archive...",
                    modelStatuses = statuses.toMap()
                )
            }
            val archiveFile = File(targetDir, artifacts.first().relativePath)
            extractTarBz2Archive(archiveFile, targetDir)
            archiveFile.delete()
            LocalModelStorage.markModelVerified(targetDir)
        } else {
            LocalModelStorage.markModelVerified(targetDir)
        }
    }

    private suspend fun extractTarBz2Archive(archiveFile: File, targetDir: File) = withContext(Dispatchers.IO) {
        FileInputStream(archiveFile).use { fis ->
            BufferedInputStream(fis, 256 * 1024).use { bis ->
                BZip2CompressorInputStream(bis).use { bzis ->
                    TarArchiveInputStream(bzis).use { tis ->
                        val buffer = ByteArray(128 * 1024)
                        var entry: TarArchiveEntry? = tis.nextTarEntry
                        while (entry != null) {
                            coroutineContext.ensureActive()
                            val outFile = File(targetDir, entry.name)
                            // Prevent Zip-Slip path traversal
                            if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                                throw IOException("Blocked unsafe archive entry: ${entry.name}")
                            }
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { fos ->
                                    while (true) {
                                        val read = tis.read(buffer)
                                        if (read == -1) break
                                        fos.write(buffer, 0, read)
                                    }
                                }
                            }
                            entry = tis.nextTarEntry
                        }
                    }
                }
            }
        }
    }

    private suspend fun downloadFileWithAutoResume(
        urlStr: String,
        partFile: File,
        expectedSize: Long,
        expectedSha256: String,
        onProgress: (Long, String?) -> Unit,
        onChunkReceived: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        var stallAttempt = 0
        var integrityAttempt = 0
        var lastEmitMs = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        var hashedOffset = 0L

        while (true) {
            coroutineContext.ensureActive()
            val existingBytes = if (partFile.exists()) partFile.length() else 0L
            if (existingBytes > expectedSize) {
                partFile.delete()
                digest.reset()
                hashedOffset = 0L
            }

            val resumeOffset = if (partFile.exists()) partFile.length() else 0L
            if (resumeOffset < hashedOffset) {
                digest.reset()
                hashedOffset = 0L
            }
            if (resumeOffset > hashedOffset) {
                hashedOffset = catchUpDigestToOffset(partFile, digest, hashedOffset, resumeOffset) { pct ->
                    onProgress(resumeOffset, "Verifying saved progress ($pct%)...")
                }
            }

            if (resumeOffset == expectedSize && expectedSize > 0L) {
                val actualHex = digest.digest().joinToString("") { "%02x".format(it) }
                if (actualHex.equals(expectedSha256, ignoreCase = true)) {
                    onProgress(expectedSize, null)
                    return@withContext
                } else {
                    integrityAttempt++
                    partFile.delete()
                    digest.reset()
                    hashedOffset = 0L
                    if (integrityAttempt > MAX_INTEGRITY_RETRIES) {
                        throw IOException("SHA-256 mismatch for ${partFile.name}")
                    }
                    continue
                }
            }

            var connection: HttpURLConnection? = null
            var bytesReadThisConnection = 0L
            try {
                connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("Connection", "keep-alive")
                    if (resumeOffset > 0L) {
                        setRequestProperty("Range", "bytes=$resumeOffset-")
                    }
                }

                val responseCode = connection.responseCode
                val isPartial = responseCode == HttpURLConnection.HTTP_PARTIAL
                val isOk = responseCode == HttpURLConnection.HTTP_OK
                if (!isPartial && !isOk) {
                    throw IOException("HTTP $responseCode from server")
                }

                val startOffset = if (isPartial) resumeOffset else 0L
                RandomAccessFile(partFile, "rw").use { raf ->
                    if (!isPartial && resumeOffset > 0L) {
                        raf.setLength(0L)
                        digest.reset()
                        hashedOffset = 0L
                    }
                    raf.seek(startOffset)
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(128 * 1024)
                        var currentSize = startOffset
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            raf.write(buffer, 0, read)
                            if (hashedOffset == currentSize) {
                                digest.update(buffer, 0, read)
                                hashedOffset += read
                            }
                            currentSize += read
                            bytesReadThisConnection += read
                            onChunkReceived(read)
                            if (bytesReadThisConnection >= 64 * 1024L) {
                                stallAttempt = 0
                            }
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastEmitMs >= 120L || currentSize >= expectedSize) {
                                lastEmitMs = now
                                onProgress(currentSize, null)
                            }
                        }
                    }
                }

                val finalLength = partFile.length()
                if (finalLength == expectedSize) {
                    if (hashedOffset < finalLength) {
                        hashedOffset = catchUpDigestToOffset(partFile, digest, hashedOffset, finalLength)
                    }
                    val actualHex = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actualHex.equals(expectedSha256, ignoreCase = true)) {
                        integrityAttempt++
                        partFile.delete()
                        digest.reset()
                        hashedOffset = 0L
                        if (integrityAttempt > MAX_INTEGRITY_RETRIES) {
                            throw IOException("SHA-256 mismatch for ${partFile.name}")
                        }
                        throw IOException("SHA-256 retry $integrityAttempt")
                    }
                    onProgress(finalLength, null)
                    return@withContext
                } else {
                    throw IOException("Incomplete stream ($finalLength / $expectedSize bytes)")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                stallAttempt++
                if (stallAttempt > MAX_STALL_RETRIES) throw e
                val backoffMs = min(15_000L, 1_500L * stallAttempt)
                val saved = if (partFile.exists()) partFile.length() else 0L
                onProgress(
                    saved,
                    "Auto-resuming from ${DownloadSessionState.formatBytesMb(saved)} (retry $stallAttempt/$MAX_STALL_RETRIES)..."
                )
                delay(backoffMs)
            } finally {
                connection?.disconnect()
            }
        }
    }

    private suspend fun catchUpDigestToOffset(
        file: File,
        digest: MessageDigest,
        fromOffset: Long,
        toOffset: Long,
        onVerifyProgress: ((Int) -> Unit)? = null
    ): Long = withContext(Dispatchers.IO) {
        if (toOffset <= fromOffset || !file.exists()) return@withContext fromOffset
        val totalToHash = (toOffset - fromOffset).coerceAtLeast(1L)
        var current = fromOffset
        var lastEmitMs = 0L
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(fromOffset)
            val buf = ByteArray(256 * 1024)
            while (current < toOffset) {
                coroutineContext.ensureActive()
                val toRead = min(buf.size.toLong(), toOffset - current).toInt()
                val r = raf.read(buf, 0, toRead)
                if (r <= 0) break
                digest.update(buf, 0, r)
                current += r
                val now = SystemClock.elapsedRealtime()
                if (onVerifyProgress != null && (now - lastEmitMs >= 150L || current >= toOffset)) {
                    lastEmitMs = now
                    val pct = (((current - fromOffset) * 100L) / totalToHash).toInt().coerceIn(0, 100)
                    onVerifyProgress(pct)
                }
            }
        }
        current
    }

    suspend fun verifySha256Incremental(file: File, expectedHex: String): Boolean = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.isFile || file.length() <= 0L) return@withContext false
        return@withContext try {
            val digest = MessageDigest.getInstance("SHA-256")
            catchUpDigestToOffset(file, digest, 0L, file.length())
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            actual.equals(expectedHex, ignoreCase = true)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            false
        }
    }

    private inline fun updateState(transform: DownloadSessionState.() -> DownloadSessionState) {
        _sessionState.value = _sessionState.value.transform()
    }

    private class RollingSpeedTracker {
        private val samples = ArrayDeque<Pair<Long, Long>>()
        private var totalBytesRecorded = 0L

        @Synchronized
        fun recordBytes(count: Int) {
            val now = SystemClock.elapsedRealtime()
            totalBytesRecorded += count
            samples.addLast(now to totalBytesRecorded)
            pruneOld(now)
        }

        @Synchronized
        fun currentSpeedBytesPerSec(): Long {
            val now = SystemClock.elapsedRealtime()
            pruneOld(now)
            if (samples.size < 2) return 0L
            val oldest = samples.first()
            val newest = samples.last()
            val dtMs = newest.first - oldest.first
            val dBytes = newest.second - oldest.second
            if (dtMs <= 100L || dBytes <= 0L) return 0L
            return (dBytes * 1000L) / dtMs
        }

        private fun pruneOld(nowMs: Long) {
            while (samples.size > 2 && nowMs - samples.first().first > 4_000L) {
                samples.removeFirst()
            }
        }
    }
}

class ModelDownloadService : Service() {
    companion object {
        private const val CHANNEL_ID = "luna_model_download_channel"
        private const val NOTIFICATION_ID = 2001

        fun start(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, ModelDownloadService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.applicationContext.stopService(Intent(context.applicationContext, ModelDownloadService::class.java))
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquireWakeLock()
        startForegroundCompat(buildNotification(ModelDownloadManager.sessionState.value))

        serviceScope.launch {
            ModelDownloadManager.sessionState.collect { state ->
                if (!state.isRunning) {
                    stopSelf()
                } else {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIFICATION_ID, buildNotification(state))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildNotification(ModelDownloadManager.sessionState.value))
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun buildNotification(state: DownloadSessionState): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val percent = (state.overallProgress * 100f).roundToInt()
        val modelName = state.activeModel?.id?.uppercase(Locale.ROOT) ?: "Models"
        val title = "Luna: Downloading $modelName ($percent%) — ${state.formattedOverallBytes}"
        val subtitle = state.statusNote ?: "${state.formattedSpeed} • ${state.formattedEta}"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setProgress(1000, (state.overallProgress * 1000f).roundToInt().coerceIn(0, 1000), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Luna Model Downloads",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Luna::ModelDownloadWakeLock").apply {
                setReferenceCounted(false)
                acquire(2 * 60 * 60 * 1000L)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }
}
