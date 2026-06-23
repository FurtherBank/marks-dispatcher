package com.marksdispatcher.app.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.marksdispatcher.app.dispatch.DispatchManager
import com.marksdispatcher.app.service.ClipboardMonitorService
import java.util.concurrent.TimeUnit

class DispatchRetryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val manager = DispatchManager(applicationContext)
        val successCount = manager.retryPendingQueue()
        if (successCount > 0) {
            applicationContext.sendBroadcast(
                android.content.Intent(ClipboardMonitorService.ACTION_DISPATCH_UPDATED)
                    .setPackage(applicationContext.packageName)
            )
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "dispatch_retry_worker"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<DispatchRetryWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
