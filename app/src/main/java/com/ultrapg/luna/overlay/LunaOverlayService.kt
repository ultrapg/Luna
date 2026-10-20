/*
 * Luna — Native Android Digital Assistant
 * Copyright (C) 2026 ultrapg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.ultrapg.luna.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ultrapg.luna.MainActivity
import com.ultrapg.luna.R
import com.ultrapg.luna.assistant.LunaAccessibilityService
import com.ultrapg.luna.assistant.LunaAssistantController
import com.ultrapg.luna.core.settings.BubbleEdge
import com.ultrapg.luna.core.settings.LunaSettingsRepository
import com.ultrapg.luna.core.speech.LunaTtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ComposeOverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    fun performRestore(state: Bundle?) {
        savedStateRegistryController.performRestore(state)
    }

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }
}

/**
 * Manages the floating horizontal Side-Notch overlay window across the system.
 * Uses a proper Android 11+ visual `WindowContext` + `ContextThemeWrapper` so `ComposeView`
 * and `WindowManager` never crash with `IllegalArgumentException` on Android 14/15 (`targetSdk = 35`).
 */
object LunaOverlayWindowManager {
    private const val TAG = "LunaOverlayWM"

    private var rootView: View? = null
    private var windowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var lifecycleOwner: ComposeOverlayLifecycleOwner? = null
    private var horizontalDragAccumPx: Float = 0f

    fun canShowSystemOverlay(context: Context): Boolean {
        return LunaAccessibilityService.activeInstance != null || Settings.canDrawOverlays(context)
    }

    private fun createVisualWindowContext(baseContext: Context, windowType: Int): Context {
        val rawVisualContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val dm = baseContext.getSystemService(DisplayManager::class.java)
                val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
                if (display != null) {
                    baseContext.createDisplayContext(display).createWindowContext(windowType, null)
                } else {
                    baseContext
                }
            }.getOrDefault(baseContext)
        } else {
            baseContext
        }
        return ContextThemeWrapper(rawVisualContext, R.style.Theme_Luna)
    }

    @Synchronized
    fun ensureOverlayAttached(context: Context) {
        if (rootView != null) {
            syncWindowPosition(context)
            return
        }
        val a11yContext = LunaAccessibilityService.activeInstance
        val hasOverlayPerm = Settings.canDrawOverlays(context)
        if (a11yContext == null && !hasOverlayPerm) return

        val windowType = if (a11yContext != null) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }

        val baseContext: Context = a11yContext ?: context.applicationContext
        val visualContext = if (a11yContext != null) {
            ContextThemeWrapper(a11yContext, R.style.Theme_Luna)
        } else {
            createVisualWindowContext(baseContext, windowType)
        }

        val wm = visualContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val settings = LunaSettingsRepository.current(visualContext)
        val screenHeight = visualContext.resources.displayMetrics.heightPixels
        val density = visualContext.resources.displayMetrics.density

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = when (settings.bubbleEdge) {
                BubbleEdge.RIGHT -> Gravity.END or Gravity.TOP
                BubbleEdge.LEFT -> Gravity.START or Gravity.TOP
                BubbleEdge.FREE -> Gravity.START or Gravity.TOP
            }
            x = if (settings.bubbleEdge == BubbleEdge.FREE) {
                (settings.bubbleOffsetXDp * density).toInt()
            } else {
                0
            }
            y = (screenHeight * settings.bubbleVerticalFraction).toInt()
        }

        val owner = ComposeOverlayLifecycleOwner().apply {
            performRestore(null)
            handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            handleLifecycleEvent(Lifecycle.Event.ON_START)
            handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        val container = object : FrameLayout(visualContext) {
            override fun onTouchEvent(event: MotionEvent?): Boolean {
                if (event?.action == MotionEvent.ACTION_OUTSIDE ||
                    event?.actionMasked == MotionEvent.ACTION_OUTSIDE
                ) {
                    LunaAssistantController.dismissOverlay(visualContext)
                    return true
                }
                return super.onTouchEvent(event)
            }
        }.apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
        }

        val compose = ComposeView(visualContext).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                val liveState by LunaAssistantController.liveState.collectAsState()
                val curSettings by LunaSettingsRepository.settingsFlow.collectAsState()
                val micRms by LunaAssistantController.audioRecorder.rmsLevel.collectAsState()
                val micWaveBands by LunaAssistantController.audioRecorder.waveBands.collectAsState()
                val ttsEngine = LunaTtsEngine.getInstance(visualContext.applicationContext)
                val ttsRms by ttsEngine.outputRms.collectAsState()
                val ttsWaveBands by ttsEngine.outputWaveBands.collectAsState()
                val isSpeaking by ttsEngine.isSpeaking.collectAsState()
                val activeWaveBands = if (isSpeaking || liveState.phase == com.ultrapg.luna.assistant.AssistantOverlayPhase.SPEAKING) {
                    ttsWaveBands
                } else {
                    micWaveBands
                }

                if (liveState.isVisible) {
                    GeminiSidePillOverlay(
                        liveState = liveState,
                        settings = curSettings,
                        micRms = micRms,
                        waveBands = activeWaveBands,
                        ttsRms = ttsRms,
                        isSpeaking = isSpeaking,
                        onDragDelta = { dx, dy ->
                            handleDragDelta(visualContext, dx, dy)
                        },
                        onToggleExpand = {},
                        onDismiss = {
                            LunaAssistantController.dismissOverlay(visualContext)
                        }
                    )
                }
            }
        }

        container.addView(compose)

        runCatching {
            wm.addView(container, lp)
            windowManager = wm
            layoutParams = lp
            rootView = container
            lifecycleOwner = owner
        }.onFailure { e ->
            Log.e(TAG, "Failed to attach overlay window: ${e.message}", e)
            owner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }

    @Synchronized
    fun syncWindowPosition(context: Context) {
        val view = rootView ?: return
        val wm = windowManager ?: return
        val lp = layoutParams ?: return
        val settings = LunaSettingsRepository.current(context)
        val screenHeight = context.resources.displayMetrics.heightPixels
        val density = context.resources.displayMetrics.density

        lp.gravity = when (settings.bubbleEdge) {
            BubbleEdge.RIGHT -> Gravity.END or Gravity.TOP
            BubbleEdge.LEFT -> Gravity.START or Gravity.TOP
            BubbleEdge.FREE -> Gravity.START or Gravity.TOP
        }
        lp.x = if (settings.bubbleEdge == BubbleEdge.FREE) {
            (settings.bubbleOffsetXDp * density).toInt()
        } else {
            0
        }
        lp.y = (screenHeight * settings.bubbleVerticalFraction).toInt()

        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        runCatching { wm.updateViewLayout(view, lp) }
    }

    fun handleDragDelta(context: Context, dxPx: Float, dyPx: Float) {
        val metrics = context.resources.displayMetrics
        val screenHeight = metrics.heightPixels.coerceAtLeast(1)
        val screenWidth = metrics.widthPixels.coerceAtLeast(1)
        val density = metrics.density.coerceAtLeast(1f)
        val screenWidthDp = (screenWidth / density).toInt().coerceAtLeast(240)

        horizontalDragAccumPx += dxPx
        val snapThresholdPx = 56f * density

        LunaSettingsRepository.update(context) { cur ->
            val newYFrac = (cur.bubbleVerticalFraction + (dyPx / screenHeight)).coerceIn(0.04f, 0.90f)
            var newEdge = cur.bubbleEdge
            var newXDp = cur.bubbleOffsetXDp

            when (cur.bubbleEdge) {
                BubbleEdge.RIGHT -> {
                    if (horizontalDragAccumPx < -snapThresholdPx) {
                        newEdge = BubbleEdge.LEFT
                        horizontalDragAccumPx = 0f
                    }
                }
                BubbleEdge.LEFT -> {
                    if (horizontalDragAccumPx > snapThresholdPx) {
                        newEdge = BubbleEdge.RIGHT
                        horizontalDragAccumPx = 0f
                    }
                }
                BubbleEdge.FREE -> {
                    newXDp = (cur.bubbleOffsetXDp + (dxPx / density).toInt())
                        .coerceIn(0, (screenWidthDp - 64).coerceAtLeast(0))
                    if (newXDp <= 16) {
                        newEdge = BubbleEdge.LEFT
                        newXDp = 0
                    } else if (newXDp >= screenWidthDp - 84) {
                        newEdge = BubbleEdge.RIGHT
                        newXDp = 0
                    }
                }
            }

            cur.copy(
                bubbleEdge = newEdge,
                bubbleVerticalFraction = newYFrac,
                bubbleOffsetXDp = newXDp
            )
        }
        syncWindowPosition(context)
    }

    @Synchronized
    fun detachOverlay() {
        val view = rootView ?: return
        val wm = windowManager
        lifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        runCatching { wm?.removeView(view) }
        rootView = null
        windowManager = null
        layoutParams = null
        lifecycleOwner = null
        horizontalDragAccumPx = 0f
    }
}

class LunaOverlayService : Service() {
    companion object {
        private const val CHANNEL_ID = "luna_assistant_overlay_channel"
        private const val NOTIFICATION_ID = 2002

        fun startOrTrigger(context: Context, startListening: Boolean = true) {
            val appContext = context.applicationContext
            // Immediately attach overlay and trigger assistant on the main thread
            // so it works even if background FGS restrictions apply on Android 15
            if (LunaOverlayWindowManager.canShowSystemOverlay(appContext)) {
                LunaOverlayWindowManager.ensureOverlayAttached(appContext)
            }
            LunaAssistantController.triggerFromSideButton(
                appContext,
                startListeningImmediately = startListening
            )

            val intent = Intent(appContext, LunaOverlayService::class.java).apply {
                putExtra("start_listening", false)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat()

        scope.launch {
            LunaAssistantController.liveState.collect { state ->
                if (state.isVisible) {
                    LunaOverlayWindowManager.ensureOverlayAttached(this@LunaOverlayService)
                    LunaOverlayWindowManager.syncWindowPosition(this@LunaOverlayService)
                } else {
                    LunaOverlayWindowManager.detachOverlay()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        val startListening = intent?.getBooleanExtra("start_listening", false) ?: false
        if (LunaOverlayWindowManager.canShowSystemOverlay(this)) {
            LunaOverlayWindowManager.ensureOverlayAttached(this)
        }
        if (startListening) {
            LunaAssistantController.triggerFromSideButton(this, startListeningImmediately = true)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        LunaOverlayWindowManager.detachOverlay()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Luna Digital Assistant")
            .setContentText("Ready via Side Button • Local Ear & Voz STT + Neural TTS")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Luna Digital Assistant",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}
