package com.marksdispatcher.app.util

import android.content.ClipboardManager
import android.content.Context
import com.marksdispatcher.app.detector.LinkSourceDetector

object ClipboardReader {

    fun readText(context: Context, clipboardManager: ClipboardManager): String? {
        if (!clipboardManager.hasPrimaryClip()) return null
        val clip = clipboardManager.primaryClip ?: return null
        if (clip.itemCount == 0) return null

        val item = clip.getItemAt(0)

        item.coerceToText(context)?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

        item.uri?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { uri ->
            if (LinkSourceDetector.extractFirstUrl(uri) != null || uri.startsWith("http", ignoreCase = true)) {
                return uri
            }
        }

        item.htmlText?.trim()?.takeIf { it.isNotEmpty() }?.let { html ->
            LinkSourceDetector.extractFirstUrl(html)?.let { return html }
        }

        return null
    }
}
