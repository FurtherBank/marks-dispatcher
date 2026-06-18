package com.marksdispatcher.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.marksdispatcher.app.data.SettingsRepository
import com.marksdispatcher.app.service.ClipboardMonitorService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = SettingsRepository(context).getSettings()
        if (settings.autoStartOnBoot && settings.monitorEnabled) {
            ClipboardMonitorService.start(context)
        }
    }
}
