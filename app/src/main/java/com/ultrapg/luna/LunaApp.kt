package com.ultrapg.luna

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.ultrapg.luna.core.database.LunaDatabase
import com.ultrapg.luna.core.hardware.NpuHardwareManager
import com.ultrapg.luna.core.settings.LunaSettingsRepository
import com.ultrapg.luna.core.speech.LunaTtsEngine

class LunaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LunaSettingsRepository.init(this)
        LunaDatabase.getInstance(this)
        NpuHardwareManager.detectHardwareProfile(this)
        // Warm up TTS engine immediately on the main thread
        runCatching {
            LunaTtsEngine.getInstance(this)
        }
        // Clean up legacy notification channel so the status bar star icon never appears
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.deleteNotificationChannel("luna_assistant_overlay_channel")
            }
        }
    }
}
