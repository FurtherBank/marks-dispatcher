package com.marksdispatcher.app.util

import android.content.Context
import com.marksdispatcher.app.data.SettingsRepository
import com.marksdispatcher.app.detector.LinkSourceDetector

/**
 * 按 URL 去重，避免浮标连点或同一链接重复派发。
 */
object DispatchDeduper {

    /** 同一 URL 在此时间内不再重复派发 */
    private const val URL_DEDUP_MS = 10 * 60 * 1000L

    enum class Decision {
        Proceed,
        DuplicateSkipped,
        NotALink,
        Empty
    }

    fun evaluate(context: Context, text: String): Decision {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Decision.Empty

        val url = LinkSourceDetector.analyzeClipboardText(trimmed)?.first ?: return Decision.NotALink
        if (isDuplicateUrl(context, url)) return Decision.DuplicateSkipped
        return Decision.Proceed
    }

    fun markDispatched(context: Context, url: String) {
        SettingsRepository(context.applicationContext).setLastDispatchedUrl(url)
    }

    fun isDuplicateUrl(context: Context, url: String): Boolean {
        val repo = SettingsRepository(context.applicationContext)
        val lastUrl = repo.getLastDispatchedUrl() ?: return false
        val lastAt = repo.getLastDispatchedAt()
        return url == lastUrl && System.currentTimeMillis() - lastAt < URL_DEDUP_MS
    }
}
