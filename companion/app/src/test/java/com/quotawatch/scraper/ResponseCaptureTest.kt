package com.quotawatch.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capture hook itself runs in the page (verified on the device); this pins how patterns are
 * embedded in it, since a mis-escaped pattern would silently capture nothing.
 */
class ResponseCaptureTest {

    @Test
    fun `js string literal escapes backslash and quote`() {
        // Mutation-checked: backslash escaping removed -> red.
        assertEquals("'a\\\\d+'", ResponseCapture.jsString("a\\d+"))
        assertEquals("'it\\'s'", ResponseCapture.jsString("it's"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty pattern map is rejected`() {
        ResponseCapture.hookScript(emptyMap())
    }
}
