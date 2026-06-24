package com.marksdispatcher.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.marksdispatcher.app.data.SettingsRepository
import com.marksdispatcher.app.detector.LinkSourceDetector
import com.marksdispatcher.app.dispatch.DispatchManager
import com.marksdispatcher.app.model.DispatchPayload
import com.marksdispatcher.app.model.DispatchRecord
import com.marksdispatcher.app.overlay.FloatingBubbleManager
import com.marksdispatcher.app.service.ClipboardMonitorService
import com.marksdispatcher.app.util.ClipboardReader
import com.marksdispatcher.app.util.DispatchDeduper
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * 浮标点击后拉回前台：读取剪贴板 → 同步派发 → Toast 提示 → 退出回到原 App。
 */
class ClipboardCaptureActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var dispatchManager: DispatchManager
    private lateinit var settingsRepository: SettingsRepository

    private var dispatchStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bubble_dispatch)
        overridePendingTransition(0, 0)

        statusText = findViewById(R.id.dispatchStatusText)
        dispatchManager = DispatchManager(this)
        settingsRepository = SettingsRepository(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            startDispatchIfNeeded()
        }
    }

    private fun startDispatchIfNeeded() {
        if (dispatchStarted) return
        dispatchStarted = true
        lifecycleScope.launch {
            runDispatch()
            finishAndReturn()
        }
    }

    private suspend fun runDispatch() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text = ClipboardReader.readText(this, clipboard).orEmpty()

        when (val decision = DispatchDeduper.evaluate(this, text)) {
            DispatchDeduper.Decision.Empty -> {
                showResult(
                    message = getString(R.string.toast_clipboard_empty),
                    feedback = FloatingBubbleManager.Feedback.Empty,
                    long = false
                )
            }
            DispatchDeduper.Decision.NotALink -> {
                showResult(
                    message = getString(R.string.toast_clipboard_not_link),
                    feedback = FloatingBubbleManager.Feedback.NotLink,
                    long = false
                )
            }
            DispatchDeduper.Decision.DuplicateSkipped -> {
                showResult(
                    message = getString(R.string.toast_dispatch_duplicate),
                    feedback = FloatingBubbleManager.Feedback.Duplicate,
                    long = false
                )
            }
            DispatchDeduper.Decision.Proceed -> {
                val analysis = LinkSourceDetector.analyzeClipboardText(text)!!
                val (url, source) = analysis
                DispatchDeduper.markDispatched(this, url)

                statusText.text = getString(R.string.dispatch_gate_dispatching, source.label)

                val payload = DispatchPayload(
                    url = url,
                    sourceId = source.id,
                    sourceLabel = source.label,
                    rawText = text.trim(),
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
                sendBroadcast(
                    Intent(ClipboardMonitorService.ACTION_DISPATCH_UPDATED).setPackage(packageName)
                )
                FloatingBubbleManager.sendFeedback(
                    this,
                    if (outcome.success) FloatingBubbleManager.Feedback.Success
                    else FloatingBubbleManager.Feedback.NotLink
                )

                val toastMessage = if (outcome.success) {
                    getString(R.string.toast_dispatch_success, source.label)
                } else {
                    getString(R.string.toast_dispatch_failed, outcome.message)
                }
                showToast(toastMessage, long = !outcome.success)
            }
        }
    }

    private fun showResult(
        message: String,
        feedback: FloatingBubbleManager.Feedback,
        long: Boolean
    ) {
        statusText.text = message
        FloatingBubbleManager.sendFeedback(this, feedback)
        showToast(message, long)
    }

    private fun showToast(message: String, long: Boolean) {
        Toast.makeText(
            this,
            message,
            if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        ).show()
    }

    private fun finishAndReturn() {
        finish()
        overridePendingTransition(0, 0)
        moveTaskToBack(true)
    }

    companion object {
        fun launch(context: Context) {
            val intent = Intent(context, ClipboardCaptureActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            }
            context.startActivity(intent)
        }
    }
}
