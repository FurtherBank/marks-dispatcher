package com.marksdispatcher.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.marksdispatcher.app.dispatch.TextDispatchGate
import com.marksdispatcher.app.overlay.FloatingBubbleManager
import com.marksdispatcher.app.util.IntentTextReader
import kotlinx.coroutines.launch

/**
 * 通过 Intent 接收文字并派发到电脑：
 * - [Intent.ACTION_SEND] / text/plain（系统分享菜单）
 * - [ACTION_DISPATCH_TEXT]（显式调用，可带 [EXTRA_FORCE]）
 */
class TextDispatchActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private var dispatchStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bubble_dispatch)
        overridePendingTransition(0, 0)
        statusText = findViewById(R.id.dispatchStatusText)

        val text = IntentTextReader.readText(intent)
        if (text.isNullOrBlank()) {
            showToast(getString(R.string.toast_intent_text_empty), long = false)
            finish()
            overridePendingTransition(0, 0)
            return
        }

        statusText.text = getString(R.string.dispatch_gate_processing)
        startDispatch(text, IntentTextReader.readForce(intent))
    }

    private fun startDispatch(text: String, force: Boolean) {
        if (dispatchStarted) return
        dispatchStarted = true
        lifecycleScope.launch {
            val result = TextDispatchGate.dispatch(
                context = this@TextDispatchActivity,
                text = text,
                force = force,
                onStatusUpdate = { statusText.text = it }
            )
            result.feedback?.let { FloatingBubbleManager.sendFeedback(this@TextDispatchActivity, it) }
            statusText.text = result.message
            showToast(result.message, result.longToast)
            finish()
            overridePendingTransition(0, 0)
        }
    }

    private fun showToast(message: String, long: Boolean) {
        Toast.makeText(
            this,
            message,
            if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        ).show()
    }

    companion object {
        const val ACTION_DISPATCH_TEXT = "com.marksdispatcher.app.action.DISPATCH_TEXT"
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_FORCE = "extra_force"

        fun launch(context: Context, text: String, force: Boolean = false) {
            val intent = Intent(context, TextDispatchActivity::class.java).apply {
                action = ACTION_DISPATCH_TEXT
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_FORCE, force)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
