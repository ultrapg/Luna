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
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ultrapg.luna.core.settings.BubbleEdge
import com.ultrapg.luna.core.settings.LunaSettingsRepository
import com.ultrapg.luna.core.speech.LunaTtsEngine
import com.ultrapg.luna.overlay.ComposeOverlayLifecycleOwner
import com.ultrapg.luna.overlay.GeminiSidePillOverlay
import com.ultrapg.luna.overlay.LunaOverlayService
import com.ultrapg.luna.overlay.LunaOverlayWindowManager

/**
 * Helper to check and request Android's Default Digital Assistant role (`ROLE_ASSISTANT`)
 * so holding the physical side/power button invokes Luna just like Google Gemini.
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
 * Non-blocking `VoiceInteractionSession` that spawns Luna's Gemini-style pill/notch
 * anchored directly next to the physical side button without dimming or blocking the screen.
 */
class LunaVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    private var pillContainerView: View? = null
    private var lifecycleOwner: ComposeOverlayLifecycleOwner? = null

    override fun onCreate() {
        super.onCreate()
        window?.window?.let { win ->
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val params = win.attributes
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            params.flags = (params.flags
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS) and
                WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
            params.dimAmount = 0f
            win.attributes = params
        }
    }

    override fun onCreateContentView(): View {
        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val owner = ComposeOverlayLifecycleOwner().apply {
            performRestore(null)
            handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            handleLifecycleEvent(Lifecycle.Event.ON_START)
            handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        lifecycleOwner = owner

        val settings = LunaSettingsRepository.current(context)
        val screenHeight = context.resources.displayMetrics.heightPixels

        val composePill = ComposeView(context).apply {
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
                topMargin = (screenHeight * settings.bubbleVerticalFraction).toInt()
            }
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                window?.window?.decorView?.requestLayout()
            }
            setContent {
                val liveState by LunaAssistantController.liveState.collectAsState()
                val curSettings by LunaSettingsRepository.settingsFlow.collectAsState()
                val micRms by LunaAssistantController.audioRecorder.rmsLevel.collectAsState()
                val waveBands by LunaAssistantController.audioRecorder.waveBands.collectAsState()
                val ttsEngine = LunaTtsEngine.getInstance(context)
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
                        onDragDelta = { _, dy ->
                            val newFrac = (curSettings.bubbleVerticalFraction + dy / screenHeight).coerceIn(0.05f, 0.88f)
                            LunaSettingsRepository.update(context) { it.copy(bubbleVerticalFraction = newFrac) }
                            (layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                                lp.topMargin = (screenHeight * newFrac).toInt()
                                layoutParams = lp
                            }
                        },
                        onToggleExpand = {},
                        onDismiss = {
                            LunaAssistantController.dismissOverlay(context)
                            hide()
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
        // If global system overlay is available, delegate to unified overlay window manager
        // so the pill persists smoothly even after VoiceInteractionSession closes
        if (LunaOverlayWindowManager.canShowSystemOverlay(context)) {
            LunaOverlayService.startOrTrigger(context, startListening = true)
            hide()
        } else {
            LunaAssistantController.triggerFromSideButton(context, startListeningImmediately = true)
        }
    }

    override fun onDestroy() {
        lifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        lifecycleOwner = null
        super.onDestroy()
    }
}

/**
 * Fallback Assist Trampoline Activity for `android.intent.action.ASSIST` and `ACTION_VOICE_COMMAND`.
 * Immediately triggers the floating side-button pill overlay and finishes with zero animation
 * so the underlying app stays in focus.
 */
class AssistTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        LunaOverlayService.startOrTrigger(applicationContext, startListening = true)

        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
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
