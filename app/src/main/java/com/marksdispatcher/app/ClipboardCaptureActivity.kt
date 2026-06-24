package com.marksdispatcher.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.marksdispatcher.app.service.ClipboardMonitorService
import com.marksdispatcher.app.util.ClipboardReader

/**
 * Android 10+ 仅允许「当前拥有焦点的 App」读取剪贴板。
 * 前台 Service 在其它 App 中复制时无法直接读剪贴板，需通过透明 Activity 短暂获取焦点后读取。
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
        if (!text.isNullOrBlank()) {
            ClipboardMonitorService.dispatchNow(this, text)
        }

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
