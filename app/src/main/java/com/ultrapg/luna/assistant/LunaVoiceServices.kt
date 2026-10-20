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

import android.accessibilityservice.AccessibilityService
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ultrapg.luna.R
import com.ultrapg.luna.core.settings.BubbleEdge
import com.ultrapg.luna.core.settings.LunaSettingsRepository
import com.ultrapg.luna.core.speech.LunaTtsEngine
import com.ultrapg.luna.overlay.ComposeOverlayLifecycleOwner
import com.ultrapg.luna.overlay.LunaSidePillOverlay
import com.ultrapg.luna.overlay.LunaOverlayService
import com.ultrapg.luna.overlay.LunaOverlayWindowManager
import com.ultrapg.luna.overlay.LunaScreenMetrics

/**
 * Helper to check and request Android's Default Digital Assistant role (`ROLE_ASSISTANT`)
 * so holding the physical side/power button invokes Luna directly.
 */
object AssistantRoleHelper {
    fun isDefaultAssistant(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = context.getSystemService(RoleManager::class.java)
            if (rm?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true) return true
        }
        val current = runCatching {
            Settings.Secure.getString(context.contentResolver, "assistant")
        }.getOrNull()
        return current?.contains(context.packageName) == true
    }

    fun openDefaultAssistantSettings(context: Context) {
        val intents = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
    }

    fun openOverlayPermissionSettings(context: Context) {
        runCatching {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun openAccessibilitySettings(context: Context) {
        runCatching {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

class LunaVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        LunaSettingsRepository.init(applicationContext)
    }
}

class LunaVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return LunaVoiceInteractionSession(this)
    }
}

/**
 * Mandatory RecognitionService stub required by Android's `VoiceInteractionServiceInfo`
 * system parser to validate `LunaVoiceInteractionService` as an eligible Digital Assistant.
 */
class LunaRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, listener: Callback?) {}
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}

/**
 * Non-blocking `VoiceInteractionSession` that spawns Luna's side-notch bubble
 * anchored directly next to the physical side button without dimming or blocking the screen.
 */
class LunaVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    companion object {
        private const val EXTRA_RESUME_ACTIVE_TURN = "resume_active_turn"

        @Volatile
        private var activeSession: LunaVoiceInteractionSession? = null

        fun ensureSessionShownForActiveTurn(): Boolean {
            val session = activeSession ?: return false
            val state = LunaAssistantController.liveState.value
            if (state.isVisible && !state.isDismissing) {
                return runCatching {
                    val args = Bundle().apply {
                        putBoolean(EXTRA_RESUME_ACTIVE_TURN, true)
                    }
                    session.show(args, 0)
                    true
                }.getOrDefault(false)
            }
            return false
        }

        fun hideActiveSessionIfShown() {
            runCatching {
                activeSession?.hide()
            }
        }
    }

    private var pillContainerView: View? = null
    private var lifecycleOwner: ComposeOverlayLifecycleOwner? = null
    private var horizontalDragAccumPx: Float = 0f

    @Suppress("DEPRECATION")
    private fun configureSessionWindowForLockScreen() {
        window?.window?.let { win ->
            runCatching {
                win.volumeControlStream = LunaTtsEngine.getInstance(context.applicationContext).getPreferredVolumeControlStream()
            }
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val params = win.attributes
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            params.flags = (params.flags
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) and
                WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
            params.dimAmount = 0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                params.fitInsetsTypes = 0
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            win.attributes = params
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeSession = this
        configureSessionWindowForLockScreen()
    }

    override fun onCreateContentView(): View {
        // Use the Dialog Window's visual Context wrapped with Theme.Luna so ComposeView
        // has a valid Display & WindowManager on Android 14/15 (`targetSdk = 35`)
        val winContext = window?.window?.context ?: context
        val themedContext = ContextThemeWrapper(winContext, R.style.Theme_Luna)

        val owner = ComposeOverlayLifecycleOwner().apply {
            performRestore(null)
            handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            handleLifecycleEvent(Lifecycle.Event.ON_START)
            handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        lifecycleOwner = owner

        var rootTopInsetPx = 0
        val settings = LunaSettingsRepository.current(themedContext)
        val screenHeight = LunaScreenMetrics.getRealScreenHeightPx(themedContext)
        val density = themedContext.resources.displayMetrics.density.coerceAtLeast(1f)

        val root = object : FrameLayout(themedContext) {
            override fun onTouchEvent(event: MotionEvent?): Boolean {
                if (event?.action == MotionEvent.ACTION_OUTSIDE ||
                    event?.action == MotionEvent.ACTION_DOWN
                ) {
                    if (!LunaAssistantController.shouldIgnoreOutsideTouch()) {
                        LunaAssistantController.dismissOverlay(themedContext)
                    }
                    return true
                }
                return super.onTouchEvent(event)
            }
        }.apply {
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
        }

        val composePill = ComposeView(themedContext).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = when (settings.bubbleEdge) {
                    BubbleEdge.RIGHT -> Gravity.END or Gravity.TOP
                    BubbleEdge.LEFT -> Gravity.START or Gravity.TOP
                    BubbleEdge.FREE -> Gravity.END or Gravity.TOP
                }
                topMargin = LunaScreenMetrics.computeTargetScreenYPx(themedContext, settings.bubbleVerticalFraction)
            }
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val loc = IntArray(2)
                root.getLocationOnScreen(loc)
                if (loc[1] != rootTopInsetPx) {
                    rootTopInsetPx = loc[1]
                    val curFrac = LunaSettingsRepository.current(themedContext).bubbleVerticalFraction
                    val targetY = LunaScreenMetrics.computeTargetScreenYPx(themedContext, curFrac)
                    (layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                        lp.topMargin = (targetY - rootTopInsetPx).coerceAtLeast(0)
                        layoutParams = lp
                    }
                }
                window?.window?.decorView?.requestLayout()
            }
            setContent {
                val liveState by LunaAssistantController.liveState.collectAsState()
                val curSettings by LunaSettingsRepository.settingsFlow.collectAsState()
                val micRms by LunaAssistantController.audioRecorder.rmsLevel.collectAsState()
                val micWaveBands by LunaAssistantController.audioRecorder.waveBands.collectAsState()
                val ttsEngine = LunaTtsEngine.getInstance(themedContext.applicationContext)
                val ttsRms by ttsEngine.outputRms.collectAsState()
                val ttsWaveBands by ttsEngine.outputWaveBands.collectAsState()
                val isSpeaking by ttsEngine.isSpeaking.collectAsState()
                val activeWaveBands = if (isSpeaking || liveState.phase == AssistantOverlayPhase.SPEAKING) {
                    ttsWaveBands
                } else {
                    micWaveBands
                }

                LaunchedEffect(liveState.isVisible, liveState.isDismissing) {
                    if (!liveState.isVisible && !liveState.isDismissing) {
                        hide()
                    }
                }

                if (liveState.isVisible || liveState.isDismissing) {
                    LunaSidePillOverlay(
                        liveState = liveState,
                        settings = curSettings,
                        micRms = micRms,
                        waveBands = activeWaveBands,
                        ttsRms = ttsRms,
                        isSpeaking = isSpeaking,
                        onDragDelta = { dx, dy ->
                            horizontalDragAccumPx += dx
                            val snapThreshold = 56f * density
                            var newEdge = curSettings.bubbleEdge
                            if (curSettings.bubbleEdge == BubbleEdge.RIGHT && horizontalDragAccumPx < -snapThreshold) {
                                newEdge = BubbleEdge.LEFT
                                horizontalDragAccumPx = 0f
                            } else if (curSettings.bubbleEdge == BubbleEdge.LEFT && horizontalDragAccumPx > snapThreshold) {
                                newEdge = BubbleEdge.RIGHT
                                horizontalDragAccumPx = 0f
                            }
                            val newFrac = (curSettings.bubbleVerticalFraction + dy / screenHeight.toFloat()).coerceIn(0.04f, 0.90f)
                            LunaSettingsRepository.update(themedContext) {
                                it.copy(bubbleEdge = newEdge, bubbleVerticalFraction = newFrac)
                            }
                            (layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                                lp.gravity = when (newEdge) {
                                    BubbleEdge.RIGHT -> Gravity.END or Gravity.TOP
                                    BubbleEdge.LEFT -> Gravity.START or Gravity.TOP
                                    BubbleEdge.FREE -> Gravity.END or Gravity.TOP
                                }
                                val targetY = LunaScreenMetrics.computeTargetScreenYPx(themedContext, newFrac)
                                lp.topMargin = (targetY - rootTopInsetPx).coerceAtLeast(0)
                                layoutParams = lp
                            }
                        },
                        onToggleExpand = {},
                        onDismiss = {
                            LunaAssistantController.dismissOverlay(themedContext)
                        }
                    )
                }
            }
        }

        root.addView(composePill)
        pillContainerView = composePill
        return root
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        outInsets.contentInsets.setEmpty()
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION

        val pill = pillContainerView
        if (pill != null && pill.visibility == View.VISIBLE && pill.width > 0 && pill.height > 0) {
            val loc = IntArray(2)
            pill.getLocationInWindow(loc)
            outInsets.touchableRegion.set(
                loc[0],
                loc[1],
                loc[0] + pill.width,
                loc[1] + pill.height
            )
        } else {
            outInsets.touchableRegion.setEmpty()
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        configureSessionWindowForLockScreen()
        val isResumingTurn = args?.getBoolean(EXTRA_RESUME_ACTIVE_TURN, false) == true
        if (LunaOverlayWindowManager.canShowSystemOverlay(context)) {
            if (isResumingTurn) {
                LunaOverlayWindowManager.ensureOverlayAttached(context)
                LunaOverlayWindowManager.syncWindowPosition(context)
            } else {
                LunaOverlayService.startOrTrigger(context, startListening = true)
            }
            hide()
        } else if (!isResumingTurn) {
            LunaAssistantController.triggerFromSideButton(context, startListeningImmediately = true)
        }
    }

    override fun onHide() {
        super.onHide()
        val state = LunaAssistantController.liveState.value
        // If Android hid the VoiceInteractionSession because a tool launched an external app
        // while Luna is still thinking or speaking, immediately re-show the bubble over the new app!
        if (state.isVisible && !state.isDismissing && !LunaOverlayWindowManager.canShowSystemOverlay(context)) {
            pillContainerView?.postDelayed({
                ensureSessionShownForActiveTurn()
            }, 140L)
        }
    }

    override fun onDestroy() {
        if (activeSession === this) {
            activeSession = null
        }
        lifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        lifecycleOwner = null
        super.onDestroy()
    }
}

/**
 * Assist Activity for `android.intent.action.ASSIST` and `ACTION_VOICE_COMMAND`.
 * - If system overlay permission (`canDrawOverlays` or `LunaAccessibilityService`) is active,
 *   attaches the floating side-notch overlay and finishes immediately with zero animation.
 * - If overlay permission is NOT yet granted, renders the non-touch-modal side-notch
 *   directly in its own transparent window anchored to the side edge so the side button always works!
 */
class AssistTrampolineActivity : ComponentActivity() {
    companion object {
        const val EXTRA_LOCK_SCREEN_HOST_ONLY = "lock_screen_host_only"
    }

    private var horizontalDragAccumPx: Float = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            volumeControlStream = LunaTtsEngine.getInstance(applicationContext).getPreferredVolumeControlStream()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        if (LunaOverlayWindowManager.canShowSystemOverlay(this)) {
            LunaOverlayService.startOrTrigger(this, startListening = true)
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
            return
        }

        // Fallback when SYSTEM_ALERT_WINDOW is not granted yet OR when on the Lock Screen:
        // Configure this Activity's window as a non-touch-modal side-edge notch window over the Lock Screen
        val settings = LunaSettingsRepository.current(this)
        val screenHeight = LunaScreenMetrics.getRealScreenHeightPx(this)
        val density = resources.displayMetrics.density.coerceAtLeast(1f)

        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        @Suppress("DEPRECATION")
        window.attributes = window.attributes.apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            format = PixelFormat.TRANSLUCENT
            gravity = when (settings.bubbleEdge) {
                BubbleEdge.RIGHT -> Gravity.END or Gravity.TOP
                BubbleEdge.LEFT -> Gravity.START or Gravity.TOP
                BubbleEdge.FREE -> Gravity.END or Gravity.TOP
            }
            x = 0
            y = LunaScreenMetrics.computeTargetScreenYPx(this@AssistTrampolineActivity, settings.bubbleVerticalFraction)
            flags = (flags
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) and
                WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
            dimAmount = 0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                fitInsetsTypes = 0
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@AssistTrampolineActivity)
            setViewTreeViewModelStoreOwner(this@AssistTrampolineActivity)
            setViewTreeSavedStateRegistryOwner(this@AssistTrampolineActivity)
            setContent {
                val liveState by LunaAssistantController.liveState.collectAsState()
                val curSettings by LunaSettingsRepository.settingsFlow.collectAsState()
                val micRms by LunaAssistantController.audioRecorder.rmsLevel.collectAsState()
                val micWaveBands by LunaAssistantController.audioRecorder.waveBands.collectAsState()
                val ttsEngine = LunaTtsEngine.getInstance(applicationContext)
                val ttsRms by ttsEngine.outputRms.collectAsState()
                val ttsWaveBands by ttsEngine.outputWaveBands.collectAsState()
                val isSpeaking by ttsEngine.isSpeaking.collectAsState()
                val activeWaveBands = if (isSpeaking || liveState.phase == AssistantOverlayPhase.SPEAKING) {
                    ttsWaveBands
                } else {
                    micWaveBands
                }

                LaunchedEffect(liveState.isVisible, liveState.isDismissing) {
                    if (!liveState.isVisible && !liveState.isDismissing) {
                        finish()
                        @Suppress("DEPRECATION")
                        overridePendingTransition(0, 0)
                    }
                }

                if (liveState.isVisible || liveState.isDismissing) {
                    LunaSidePillOverlay(
                        liveState = liveState,
                        settings = curSettings,
                        micRms = micRms,
                        waveBands = activeWaveBands,
                        ttsRms = ttsRms,
                        isSpeaking = isSpeaking,
                        onDragDelta = { dx, dy ->
                            horizontalDragAccumPx += dx
                            val snapThreshold = 56f * density
                            var newEdge = curSettings.bubbleEdge
                            if (curSettings.bubbleEdge == BubbleEdge.RIGHT && horizontalDragAccumPx < -snapThreshold) {
                                newEdge = BubbleEdge.LEFT
                                horizontalDragAccumPx = 0f
                            } else if (curSettings.bubbleEdge == BubbleEdge.LEFT && horizontalDragAccumPx > snapThreshold) {
                                newEdge = BubbleEdge.RIGHT
                                horizontalDragAccumPx = 0f
                            }
                            val newFrac = (curSettings.bubbleVerticalFraction + dy / screenHeight.toFloat()).coerceIn(0.04f, 0.90f)
                            LunaSettingsRepository.update(this@AssistTrampolineActivity) {
                                it.copy(bubbleEdge = newEdge, bubbleVerticalFraction = newFrac)
                            }
                            window.attributes = window.attributes.apply {
                                gravity = when (newEdge) {
                                    BubbleEdge.RIGHT -> Gravity.END or Gravity.TOP
                                    BubbleEdge.LEFT -> Gravity.START or Gravity.TOP
                                    BubbleEdge.FREE -> Gravity.END or Gravity.TOP
                                }
                                y = LunaScreenMetrics.computeTargetScreenYPx(this@AssistTrampolineActivity, newFrac)
                            }
                        },
                        onToggleExpand = {},
                        onDismiss = {
                            LunaAssistantController.dismissOverlay(this@AssistTrampolineActivity)
                        }
                    )
                }
            }
        }

        setContentView(composeView)
        val hostOnly = intent?.getBooleanExtra(EXTRA_LOCK_SCREEN_HOST_ONLY, false) == true
        if (!hostOnly) {
            LunaAssistantController.triggerFromSideButton(this, startListeningImmediately = true)
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_OUTSIDE) {
            if (!LunaAssistantController.shouldIgnoreOutsideTouch()) {
                LunaAssistantController.dismissOverlay(this)
            }
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val hostOnly = intent.getBooleanExtra(EXTRA_LOCK_SCREEN_HOST_ONLY, false)
        if (LunaOverlayWindowManager.canShowSystemOverlay(this)) {
            if (!hostOnly) {
                LunaOverlayService.startOrTrigger(this, startListening = true)
            }
            finish()
        } else if (!hostOnly) {
            LunaAssistantController.triggerFromSideButton(this, startListeningImmediately = true)
        }
    }
}

/**
 * Optional Accessibility Service that enables:
 * 1. `TYPE_ACCESSIBILITY_OVERLAY` window attachment without requiring `SYSTEM_ALERT_WINDOW`
 * 2. Hardware `KEYCODE_ASSIST` / `KEYCODE_VOICE_ASSIST` interception
 */
class LunaAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        var activeInstance: LunaAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        val settings = LunaSettingsRepository.current(this)
        if (settings.bubbleKeepAliveDock) {
            LunaAssistantController.showCollapsedNotch(this)
            LunaOverlayWindowManager.ensureOverlayAttached(this)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event != null && event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_ASSIST || event.keyCode == KeyEvent.KEYCODE_VOICE_ASSIST) {
                LunaOverlayService.startOrTrigger(this, startListening = true)
                return true
            }
        }
        return super.onKeyEvent(event)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        activeInstance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        activeInstance = null
        super.onDestroy()
    }
}
