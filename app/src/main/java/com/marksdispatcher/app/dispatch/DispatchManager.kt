package com.marksdispatcher.app.dispatch

import android.content.Context
import com.marksdispatcher.app.api.DispatchApiClient
import com.marksdispatcher.app.data.SettingsRepository
import com.marksdispatcher.app.discovery.DeviceResolver
import com.marksdispatcher.app.model.DispatchPayload
import com.marksdispatcher.app.model.DispatchRecord
import com.marksdispatcher.app.worker.DispatchRetryWorker

class DispatchManager(context: Context) {

    private val appContext = context.applicationContext
    private val settingsRepository = SettingsRepository(appContext)
    private val apiClient = DispatchApiClient()
    private val deviceResolver = DeviceResolver(appContext, settingsRepository)

    data class DispatchOutcome(
        val success: Boolean,
        val message: String,
        val queuedForRetry: Boolean = false
    )

    suspend fun dispatchPayload(payload: DispatchPayload): DispatchOutcome {
        val resolved = deviceResolver.resolve()
        if (resolved == null) {
            settingsRepository.enqueuePending(payload)
            DispatchRetryWorker.schedule(appContext)
            return DispatchOutcome(
                success = false,
                message = "接收端离线，已加入重发队列（${settingsRepository.pendingCount()} 条待发送）",
                queuedForRetry = true
            )
        }

        val result = apiClient.dispatch(
            endpoint = resolved.url,
            token = resolved.token,
            url = payload.url,
            sourceId = payload.sourceId,
            sourceLabel = payload.sourceLabel,
            rawText = payload.rawText
        )

        if (result.success) {
            return DispatchOutcome(success = true, message = result.message)
        }

        val pending = settingsRepository.enqueuePending(payload)
        settingsRepository.updatePendingAttempt(pending.id, result.message)
        DispatchRetryWorker.schedule(appContext)
        return DispatchOutcome(
            success = false,
            message = "${result.message}，已加入重发队列",
            queuedForRetry = true
        )
    }

    suspend fun retryPendingQueue(): Int {
        val pending = settingsRepository.getPendingDispatches()
        if (pending.isEmpty()) return 0

        val resolved = deviceResolver.resolve() ?: return 0
        var successCount = 0

        for (item in pending) {
            val result = apiClient.dispatch(
                endpoint = resolved.url,
                token = resolved.token,
                url = item.payload.url,
                sourceId = item.payload.sourceId,
                sourceLabel = item.payload.sourceLabel,
                rawText = item.payload.rawText
            )

            if (result.success) {
                settingsRepository.removePending(item.id)
                settingsRepository.appendHistory(
                    DispatchRecord(
                        payload = item.payload,
                        success = true,
                        message = "重发成功 (${result.httpCode})"
                    )
                )
                successCount++
            } else {
                settingsRepository.updatePendingAttempt(item.id, result.message)
            }
        }

        if (settingsRepository.pendingCount() == 0) {
            DispatchRetryWorker.cancel(appContext)
        }

        return successCount
    }
}
