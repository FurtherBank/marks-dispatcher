package com.marksdispatcher.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.marksdispatcher.app.detector.LinkSourceDetector
import com.marksdispatcher.app.overlay.FloatingBubbleManager
import com.marksdispatcher.app.service.ClipboardMonitorService
import com.marksdispatcher.app.util.ClipboardReader
import com.marksdispatcher.app.util.DispatchDeduper

/**
 * 浮标点击后启动，短暂获取焦点以合法读取剪贴板（Android 10+）。
 */
class ClipboardCaptureActivity : AppCompatActivity() {

    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            captureAndFinish()
        }
    }

    override fun onResume() {
        super.onResume()
        captureAndFinish()
    }

    private fun captureAndFinish() {
        if (handled) return
        handled = true

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text = ClipboardReader.readText(this, clipboard)

        val feedback = when (val decision = DispatchDeduper.evaluate(this, text.orEmpty())) {
            DispatchDeduper.Decision.Empty -> FloatingBubbleManager.Feedback.Empty
            DispatchDeduper.Decision.NotALink -> FloatingBubbleManager.Feedback.NotLink
            DispatchDeduper.Decision.DuplicateSkipped -> FloatingBubbleManager.Feedback.Duplicate
            DispatchDeduper.Decision.Proceed -> {
                val url = LinkSourceDetector.analyzeClipboardText(text!!)!!.first
                DispatchDeduper.markDispatched(this, url)
                ClipboardMonitorService.dispatchNow(this, text)
                FloatingBubbleManager.Feedback.Success
            }
        }

        FloatingBubbleManager.sendFeedback(this, feedback)
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        fun launch(context: Context) {
            val intent = Intent(context, ClipboardCaptureActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            context.startActivity(intent)
        }
    }
}
