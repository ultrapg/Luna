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
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
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
 * Manages the floating Gemini-style Pill/Notch overlay window across the system.
 * Automatically uses `TYPE_ACCESSIBILITY_OVERLAY` (if LunaAccessibilityService is active)
 * or `TYPE_APPLICATION_OVERLAY` (if `Settings.canDrawOverlays` is granted).
 */
object LunaOverlayWindowManager {
    private var composeView: ComposeView? = null
    private var windowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var lifecycleOwner: ComposeOverlayLifecycleOwner? = null

    fun canShowSystemOverlay(context: Context): Boolean {
        return LunaAccessibilityService.activeInstance != null || Settings.canDrawOverlays(context)
    }

    @Synchronized
    fun ensureOverlayAttached(context: Context) {
        if (composeView != null) {
            syncWindowPosition(context)
            return
        }
        val a11yContext = LunaAccessibilityService.activeInstance
        val hasOverlayPerm = Settings.canDrawOverlays(context)
        if (a11yContext == null && !hasOverlayPerm) return

        val hostContext: Context = a11yContext ?: context.applicationContext
        val wm = hostContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val settings = LunaSettingsRepository.current(hostContext)
        val screenHeight = hostContext.resources.displayMetrics.heightPixels
        val density = hostContext.resources.displayMetrics.density

        val windowType = if (a11yContext != null) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = when (settings.bubbleEdge) {
                BubbleEdge.RIGHT -> Gravity.END or Gravity.TOP
                BubbleEdge.LEFT -> Gravity.START or Gravity.TOP
                BubbleEdge.FREE -> Gravity.START or Gravity.TOP
            }
            x = (settings.bubbleOffsetXDp * density).toInt()
            y = (screenHeight * settings.bubbleVerticalFraction).toInt()
        }

        val owner = ComposeOverlayLifecycleOwner().apply {
            performRestore(null)
            handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            handleLifecycleEvent(Lifecycle.Event.ON_START)
            handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        val view = ComposeView(hostContext).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                val liveState by LunaAssistantController.liveState.collectAsState()
                val curSettings by LunaSettingsRepository.settingsFlow.collectAsState()
                val micRms by LunaAssistantController.audioRecorder.rmsLevel.collectAsState()
                val waveBands by LunaAssistantController.audioRecorder.waveBands.collectAsState()
                val ttsEngine = LunaTtsEngine.getInstance(hostContext)
                val ttsRms by ttsEngine.outputRms.collectAsState()
                val isSpeaking by ttsEngine.isSpeaking.collectAsState()

                if (liveState.isVisible) {
                    GeminiSidePillOverlay(
                        liveState = liveState,
                        settings = curSettings,
                        micRms = micRms,
                        waveBands = waveBands,
                        ttsRms = ttsRms,
                        isSpeaking = isSpeaking,
                        onDragDelta = { dx, dy ->
                            handleDragDelta(hostContext, dx, dy)
                        },
                        onToggleExpand = {},
                        onDismiss = {
                            LunaAssistantController.dismissOverlay(hostContext)
                        }
                    )
                }
            }
        }

        runCatching {
            wm.addView(view, lp)
            windowManager = wm
            layoutParams = lp
            composeView = view
            lifecycleOwner = owner
        }
    }

    @Synchronized
    fun syncWindowPosition(context: Context) {
        val view = composeView ?: return
        val wm = windowManager ?: return
        val lp = layoutParams ?: return
        val settings = LunaSettingsRepository.current(context)
        val liveState = LunaAssistantController.liveState.value
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

        // Allow soft keyboard input when expanded so the quick text field inside the pill works
        lp.flags = if (liveState.isExpanded) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }

        runCatching { wm.updateViewLayout(view, lp) }
    }

    private fun handleDragDelta(context: Context, dxPx: Float, dyPx: Float) {
        val screenHeight = context.resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        LunaSettingsRepository.update(context) { cur ->
            val newYFrac = (cur.bubbleVerticalFraction + (dyPx / screenHeight)).coerceIn(0.05f, 0.88f)
            val newXDp = if (cur.bubbleEdge == BubbleEdge.FREE) {
                (cur.bubbleOffsetXDp + (dxPx / density).toInt()).coerceIn(0, 360)
            } else {
                cur.bubbleOffsetXDp
            }
            cur.copy(
                bubbleVerticalFraction = newYFrac,
                bubbleOffsetXDp = newXDp
            )
        }
        syncWindowPosition(context)
    }

    @Synchronized
    fun detachOverlay() {
        val view = composeView ?: return
        val wm = windowManager
        lifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        runCatching { wm?.removeView(view) }
        composeView = null
        windowManager = null
        layoutParams = null
        lifecycleOwner = null
    }
}

class LunaOverlayService : Service() {
    companion object {
        private const val CHANNEL_ID = "luna_assistant_overlay_channel"
        private const val NOTIFICATION_ID = 2002

        fun startOrTrigger(context: Context, startListening: Boolean = true) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, LunaOverlayService::class.java).apply {
                putExtra("start_listening", startListening)
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
        val startListening = intent?.getBooleanExtra("start_listening", true) ?: true
        if (LunaOverlayWindowManager.canShowSystemOverlay(this)) {
            LunaOverlayWindowManager.ensureOverlayAttached(this)
        }
        LunaAssistantController.triggerFromSideButton(this, startListeningImmediately = startListening)
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
