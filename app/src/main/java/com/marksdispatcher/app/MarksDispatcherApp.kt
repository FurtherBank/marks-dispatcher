package com.marksdispatcher.app

import android.app.Application
import com.marksdispatcher.app.data.SettingsRepository
import com.marksdispatcher.app.worker.DispatchRetryWorker

class MarksDispatcherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val repo = SettingsRepository(this)
        if (repo.pendingCount() > 0 || repo.getSettings().monitorEnabled) {
            DispatchRetryWorker.schedule(this)
        }
    }
}
