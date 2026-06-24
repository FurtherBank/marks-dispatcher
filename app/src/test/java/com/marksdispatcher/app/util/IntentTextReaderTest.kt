package com.marksdispatcher.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntentTextReaderTest {

    @Test
    fun resolveText_prefersCustomExtra() {
        assertEquals(
            "custom",
            IntentTextReader.resolveText("custom", "send", emptyList(), null)
        )
    }

    @Test
    fun resolveText_fallsBackToStandardExtra() {
        assertEquals(
            "https://b23.tv/abc",
            IntentTextReader.resolveText(null, "https://b23.tv/abc", emptyList(), null)
        )
    }

    @Test
    fun resolveText_readsClipTexts() {
        assertEquals(
            "clip",
            IntentTextReader.resolveText(null, null, listOf("", "clip"), null)
        )
    }

    @Test
    fun resolveText_returnsNullWhenEmpty() {
        assertNull(IntentTextReader.resolveText(null, "  ", emptyList(), null))
    }
}
