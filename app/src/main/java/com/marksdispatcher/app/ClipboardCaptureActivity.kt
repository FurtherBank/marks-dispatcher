package com.marksdispatcher.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.marksdispatcher.app.dispatch.TextDispatchGate
import com.marksdispatcher.app.overlay.FloatingBubbleManager
import com.marksdispatcher.app.util.ClipboardReader
import kotlinx.coroutines.launch

/**
 * 浮标点击后拉回前台：读取剪贴板 → 同步派发 → Toast 提示 → 退出回到原 App。
 */
class ClipboardCaptureActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private var dispatchStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bubble_dispatch)
        overridePendingTransition(0, 0)
        statusText = findViewById(R.id.dispatchStatusText)
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
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val text = ClipboardReader.readText(this@ClipboardCaptureActivity, clipboard).orEmpty()
            statusText.text = getString(R.string.dispatch_gate_processing)

            val result = TextDispatchGate.dispatch(
                context = this@ClipboardCaptureActivity,
                text = text,
                onStatusUpdate = { statusText.text = it }
            )
            result.feedback?.let { FloatingBubbleManager.sendFeedback(this@ClipboardCaptureActivity, it) }
            statusText.text = result.message
            showToast(result.message, result.longToast)
            finishAndReturn()
        }
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
