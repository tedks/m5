package com.quotawatch.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `js string literal escapes line terminators`() {
        // A raw CR, U+2028 or U+2029 would end a JS string literal early.
        assertEquals("'a\\rb'", ResponseCapture.jsString("a\rb"))
        assertEquals("'a\\u2028b\\u2029c'", ResponseCapture.jsString("a\u2028b\u2029c"))
    }

    @Test
    fun `generated hook embeds every key and pattern and no leftover template`() {
        val js = ResponseCapture.hookScript(mapOf("kA" to "^/a/[^/]+/usage$", "kB" to "^/b$"))
        assertTrue(js.contains("['kA',new RegExp('^/a/[^/]+/usage$')]"))
        assertTrue(js.contains("['kB',new RegExp('^/b$')]"))
        assertTrue(js.contains("window.${ResponseCapture.GLOBAL}"))
        // Twin: the "absent" check below is meaningful only because the script is non-trivial.
        assertTrue(js.length > 500)
        // No unexpanded Kotlin template (a "$" followed by an identifier or brace).
        assertFalse(Regex("\\$[A-Za-z{]").containsMatchIn(js))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty pattern map is rejected`() {
        ResponseCapture.hookScript(emptyMap())
    }
}
