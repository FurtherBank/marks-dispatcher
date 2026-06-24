package com.marksdispatcher.app.util

import com.marksdispatcher.app.detector.LinkSourceDetector
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 去重键使用规范化 URL，与 LinkSourceDetector 一致。
 */
class DispatchDeduperKeyTest {

    @Test
    fun analyzeClipboardText_sameUrlDifferentWrapper_producesSameUrl() {
        val a = LinkSourceDetector.analyzeClipboardText("https://b23.tv/abc")!!.first
        val b = LinkSourceDetector.analyzeClipboardText("分享 https://b23.tv/abc 快来看")!!.first
        assertEquals(a, b)
    }
}
