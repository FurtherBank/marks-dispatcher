package com.marksdispatcher.app.dispatch

import android.content.Context
import android.content.Intent
import com.marksdispatcher.app.R
import com.marksdispatcher.app.data.SettingsRepository
import com.marksdispatcher.app.detector.LinkSourceDetector
import com.marksdispatcher.app.model.DispatchPayload
import com.marksdispatcher.app.model.DispatchRecord
import com.marksdispatcher.app.overlay.FloatingBubbleManager
import com.marksdispatcher.app.service.ClipboardMonitorService
import com.marksdispatcher.app.util.DispatchDeduper
import java.time.Instant

/**
 * 将文本识别为链接并同步派发到电脑，供浮标、分享 Intent 等入口复用。
 */
object TextDispatchGate {

    data class Result(
        val message: String,
        val feedback: FloatingBubbleManager.Feedback?,
        val longToast: Boolean
    )

    suspend fun dispatch(
        context: Context,
        text: String,
        force: Boolean = false,
        onStatusUpdate: ((String) -> Unit)? = null
    ): Result {
        val appContext = context.applicationContext
        val dispatchManager = DispatchManager(appContext)
        val settingsRepository = SettingsRepository(appContext)
        val trimmed = text.trim()

        if (!force) {
            when (val decision = DispatchDeduper.evaluate(appContext, trimmed)) {
                DispatchDeduper.Decision.Empty -> {
                    return Result(
                        message = context.getString(R.string.toast_clipboard_empty),
                        feedback = FloatingBubbleManager.Feedback.Empty,
                        longToast = false
                    )
                }
                DispatchDeduper.Decision.NotALink -> {
                    return Result(
                        message = context.getString(R.string.toast_clipboard_not_link),
                        feedback = FloatingBubbleManager.Feedback.NotLink,
                        longToast = false
                    )
                }
                DispatchDeduper.Decision.DuplicateSkipped -> {
                    return Result(
                        message = context.getString(R.string.toast_dispatch_duplicate),
                        feedback = FloatingBubbleManager.Feedback.Duplicate,
                        longToast = false
                    )
                }
                DispatchDeduper.Decision.Proceed -> Unit
            }
        } else if (trimmed.isEmpty()) {
            return Result(
                message = context.getString(R.string.toast_clipboard_empty),
                feedback = FloatingBubbleManager.Feedback.Empty,
                longToast = false
            )
        }

        val analysis = LinkSourceDetector.analyzeClipboardText(trimmed)
            ?: return Result(
                message = context.getString(R.string.toast_clipboard_not_link),
                feedback = FloatingBubbleManager.Feedback.NotLink,
                longToast = false
            )

        val (url, source) = analysis
        if (!force) {
            DispatchDeduper.markDispatched(appContext, url)
        }

        onStatusUpdate?.invoke(context.getString(R.string.dispatch_gate_dispatching, source.label))

        val payload = DispatchPayload(
            url = url,
            sourceId = source.id,
            sourceLabel = source.label,
            rawText = trimmed,
            detectedAt = Instant.now().toString()
        )

        val outcome = dispatchManager.dispatchPayload(payload)
        settingsRepository.appendHistory(
            DispatchRecord(
                payload = payload,
                success = outcome.success,
                message = outcome.message
            )
        )
        appContext.sendBroadcast(
            Intent(ClipboardMonitorService.ACTION_DISPATCH_UPDATED).setPackage(appContext.packageName)
        )

        val feedback = if (outcome.success) {
            FloatingBubbleManager.Feedback.Success
        } else {
            FloatingBubbleManager.Feedback.NotLink
        }
        val message = if (outcome.success) {
            context.getString(R.string.toast_dispatch_success, source.label)
        } else {
            context.getString(R.string.toast_dispatch_failed, outcome.message)
        }

        return Result(
            message = message,
            feedback = feedback,
            longToast = !outcome.success
        )
    }
}
