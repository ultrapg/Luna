package com.ultrapg.luna

import android.app.Application
import com.ultrapg.luna.core.database.LunaDatabase
import com.ultrapg.luna.core.hardware.NpuHardwareManager
import com.ultrapg.luna.core.settings.LunaSettingsRepository

class LunaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LunaSettingsRepository.init(this)
        LunaDatabase.getInstance(this)
        NpuHardwareManager.detectHardwareProfile(this)
    }
}
