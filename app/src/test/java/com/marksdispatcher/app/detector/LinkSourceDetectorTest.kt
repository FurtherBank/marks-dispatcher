package com.marksdispatcher.app.detector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LinkSourceDetectorTest {

    @Test
    fun extractUrl_fromMixedText() {
        val text = "快来看看这个视频 https://www.bilibili.com/video/BV1xx 太棒了"
        val url = LinkSourceDetector.extractFirstUrl(text)
        assertEquals("https://www.bilibili.com/video/BV1xx", url)
    }

    @Test
    fun detectSource_bilibili() {
        val source = LinkSourceDetector.detectSource("https://b23.tv/abc123")
        assertEquals("bilibili", source.id)
        assertEquals("哔哩哔哩", source.label)
    }

    @Test
    fun detectSource_unknownWeb() {
        val source = LinkSourceDetector.detectSource("https://example.com/article/1")
        assertEquals("web", source.id)
    }

    @Test
    fun analyzeClipboardText_returnsNullForPlainText() {
        assertNull(LinkSourceDetector.analyzeClipboardText("只是一段普通文字"))
    }

    @Test
    fun analyzeClipboardText_detectsZhihu() {
        val result = LinkSourceDetector.analyzeClipboardText("https://www.zhihu.com/question/123")
        assertNotNull(result)
        assertEquals("zhihu", result!!.second.id)
    }
}
