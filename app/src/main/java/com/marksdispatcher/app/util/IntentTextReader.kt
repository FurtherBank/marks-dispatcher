package com.marksdispatcher.app.util

import android.content.Intent
import com.marksdispatcher.app.TextDispatchActivity

/**
 * 从 Intent 提取待派发的纯文本（分享、显式派发等）。
 */
object IntentTextReader {

    fun resolveText(
        customExtra: String?,
        standardExtra: String?,
        clipTexts: List<String>,
        processText: String?
    ): String? {
        customExtra?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        standardExtra?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        for (text in clipTexts) {
            val trimmed = text.trim()
            if (trimmed.isNotEmpty()) return trimmed
        }
        processText?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return null
    }

    fun readText(intent: Intent?): String? {
        if (intent == null) return null

        val clipTexts = buildList {
            intent.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) {
                    add(clip.getItemAt(i).text?.toString().orEmpty())
                }
            }
        }

        @Suppress("DEPRECATION")
        val processText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()

        return resolveText(
            customExtra = intent.getStringExtra(TextDispatchActivity.EXTRA_TEXT),
            standardExtra = intent.getStringExtra(Intent.EXTRA_TEXT),
            clipTexts = clipTexts,
            processText = processText
        )
    }

    fun readForce(intent: Intent?): Boolean {
        return intent?.getBooleanExtra(TextDispatchActivity.EXTRA_FORCE, false) == true
    }
}
