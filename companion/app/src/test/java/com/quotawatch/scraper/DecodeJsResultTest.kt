package com.quotawatch.scraper

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `evaluateJavascript`'s callback hands back a JSON-encoded representation of the JS value —
 * for our extraction JS (which always returns via `JSON.stringify`, i.e. a JS string), that's a
 * quoted, escaped JSON string literal. These tests build that wire format with
 * [JSONObject.quote] (the same escaping WebView itself performs) so each case round-trips
 * through the real JSON escaping rules rather than a hand-picked escape sequence.
 */
class DecodeJsResultTest {

    @Test
    fun `plain string decodes unchanged`() {
        val original = "hello world"
        assertEquals(original, UsageScraper.decodeJsResult(JSONObject.quote(original)))
    }

    @Test
    fun `embedded quotes are unescaped correctly`() {
        val original = """say "hi" to them"""
        assertEquals(original, UsageScraper.decodeJsResult(JSONObject.quote(original)))
    }

    @Test
    fun `an actual newline character round-trips as a real newline`() {
        val original = "line1\nline2"
        val raw = JSONObject.quote(original)
        // Sanity-check the fixture: JSON encodes a real newline as the two-character escape \n.
        assertEquals("\"line1\\nline2\"", raw)
        assertEquals(original, UsageScraper.decodeJsResult(raw))
    }

    @Test
    fun `a literal backslash followed by the letter n is not mistaken for a newline`() {
        // This is the bug in the old chained .replace() decoder: it treated the two-character
        // sequence backslash+n as "this represents a newline" unconditionally, even when the
        // backslash was itself an escaped backslash (i.e. the *real* text contains a literal
        // '\' followed by 'n', not a newline).
        val original = "path is C:\\notes"
        val raw = JSONObject.quote(original)
        // Sanity-check the fixture: the literal backslash is doubled (\\), 'n' stays a plain char.
        assertEquals("\"path is C:\\\\notes\"", raw)
        val decoded = UsageScraper.decodeJsResult(raw)
        assertEquals(original, decoded)
        assertEquals(false, decoded!!.contains('\n'))
    }

    @Test
    fun `backslashes decode correctly`() {
        val original = """a\b\\c"""
        assertEquals(original, UsageScraper.decodeJsResult(JSONObject.quote(original)))
    }

    @Test
    fun `unicode escape sequences are decoded`() {
        // Hand-written raw JSON (rather than JSONObject.quote, which doesn't necessarily emit
        // \u escapes for ordinary printable unicode) so this specifically exercises \uXXXX
        // decoding.
        val raw = "\"caf\\u00e9\""
        assertEquals("café", UsageScraper.decodeJsResult(raw))
    }

    @Test
    fun `the bare literal null decodes to null`() {
        assertNull(UsageScraper.decodeJsResult("null"))
    }

    @Test
    fun `empty string decodes to null`() {
        assertNull(UsageScraper.decodeJsResult(""))
    }

    @Test
    fun `blank string decodes to null`() {
        assertNull(UsageScraper.decodeJsResult("   "))
    }

    @Test
    fun `null input decodes to null`() {
        assertNull(UsageScraper.decodeJsResult(null))
    }

    @Test
    fun `malformed unterminated string decodes to null instead of throwing`() {
        assertNull(UsageScraper.decodeJsResult("\"unterminated"))
    }

    @Test
    fun `malformed dangling escape decodes to null instead of throwing`() {
        assertNull(UsageScraper.decodeJsResult("\"trailing backslash\\"))
    }
}
