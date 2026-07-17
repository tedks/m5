package com.quotawatch.scraper

import com.quotawatch.api.QuotaResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure JSON→QuotaResult mapping in both scrapers. The extraction JS runs in a
 * WebView (not testable here), but everything downstream of its JSON output is pure Kotlin: these
 * lock in that only labeled values become Successes and that the absence of usage data fails
 * honestly rather than fabricating a number.
 */
class ParseUsageTest {

    private fun successByName(results: List<QuotaResult>, name: String): QuotaResult.Success =
        results.filterIsInstance<QuotaResult.Success>().first { it.quota.name == name }

    // ---- Claude ----

    @Test
    fun `claude both sections present yields two quotas`() {
        val results = ClaudeScraper.parseUsage(
            """{"usageReady":true,"fiveHourPct":7,"sevenDayPct":15}"""
        )
        assertEquals(2, results.size)
        assertEquals(7f, successByName(results, "Claude 5h").quota.used, 0.001f)
        assertEquals(15f, successByName(results, "Claude wk").quota.used, 0.001f)
    }

    @Test
    fun `claude only five hour present yields one quota`() {
        val results = ClaudeScraper.parseUsage(
            """{"usageReady":true,"fiveHourPct":7,"sevenDayPct":-1}"""
        )
        assertEquals(1, results.size)
        assertEquals(7f, successByName(results, "Claude 5h").quota.used, 0.001f)
    }

    @Test
    fun `claude only weekly present yields one quota`() {
        val results = ClaudeScraper.parseUsage(
            """{"usageReady":true,"fiveHourPct":-1,"sevenDayPct":15}"""
        )
        assertEquals(1, results.size)
        assertEquals(15f, successByName(results, "Claude wk").quota.used, 0.001f)
    }

    @Test
    fun `claude panel rendered but no values is an honest error`() {
        val results = ClaudeScraper.parseUsage(
            """{"usageReady":true,"fiveHourPct":-1,"sevenDayPct":-1}"""
        )
        assertEquals(1, results.size)
        val err = results[0] as QuotaResult.Error
        assertEquals("Usage values not found on page", err.message)
    }

    @Test
    fun `claude panel not rendered is a distinguishable error`() {
        val results = ClaudeScraper.parseUsage(
            """{"usageReady":false,"fiveHourPct":-1,"sevenDayPct":-1}"""
        )
        assertEquals(1, results.size)
        val err = results[0] as QuotaResult.Error
        assertEquals("Usage panel did not render", err.message)
    }

    @Test
    fun `claude explicit error field is surfaced`() {
        val results = ClaudeScraper.parseUsage("""{"error":"boom"}""")
        assertEquals(1, results.size)
        assertEquals("boom", (results[0] as QuotaResult.Error).message)
    }

    @Test
    fun `claude out-of-range percentage is rejected as garbage`() {
        // A value outside 0..100 can't be a real percentage — treat it as absent, don't report it.
        val results = ClaudeScraper.parseUsage(
            """{"usageReady":true,"fiveHourPct":150,"sevenDayPct":-1}"""
        )
        assertTrue(results.all { it is QuotaResult.Error })
    }

    // ---- Codex ----

    @Test
    fun `codex weekly present converts remaining to used`() {
        val results = CodexScraper.parseUsage("""{"weeklyRemaining":98}""")
        assertEquals(1, results.size)
        val s = results[0] as QuotaResult.Success
        assertEquals("Codex wk", s.quota.name)
        assertEquals(2f, s.quota.used, 0.001f)
    }

    @Test
    fun `codex full remaining is zero used`() {
        val results = CodexScraper.parseUsage("""{"weeklyRemaining":100}""")
        val s = results[0] as QuotaResult.Success
        assertEquals(0f, s.quota.used, 0.001f)
    }

    @Test
    fun `codex missing weekly is an honest error`() {
        val results = CodexScraper.parseUsage("""{"weeklyRemaining":-1}""")
        assertEquals(1, results.size)
        val err = results[0] as QuotaResult.Error
        assertEquals("Weekly usage limit not found on page", err.message)
    }

    @Test
    fun `codex explicit error field is surfaced`() {
        val results = CodexScraper.parseUsage("""{"error":"boom"}""")
        assertEquals("boom", (results[0] as QuotaResult.Error).message)
    }

    // ---- Codex 5h (bd m5-u1d: tolerant re-add — OpenAI's removal is expected to be temporary) ----

    @Test
    fun `codex weekly and five hour both present yields two quotas`() {
        val results = CodexScraper.parseUsage("""{"weeklyRemaining":98,"fiveHourRemaining":90}""")
        assertEquals(2, results.size)
        assertEquals(2f, successByName(results, "Codex wk").quota.used, 0.001f)
        assertEquals(10f, successByName(results, "Codex 5h").quota.used, 0.001f)
    }

    @Test
    fun `codex five hour absent yields weekly only with no error`() {
        val results = CodexScraper.parseUsage("""{"weeklyRemaining":98,"fiveHourRemaining":-1}""")
        assertEquals(1, results.size)
        assertEquals("Codex wk", (results[0] as QuotaResult.Success).quota.name)
    }

    @Test
    fun `codex five hour missing from json entirely yields weekly only with no error`() {
        // The field may not even be in the payload (e.g. an older cached extraction JS) —
        // optDouble's default handles this the same as an explicit -1.
        val results = CodexScraper.parseUsage("""{"weeklyRemaining":98}""")
        assertEquals(1, results.size)
        assertEquals("Codex wk", (results[0] as QuotaResult.Success).quota.name)
    }

    @Test
    fun `codex five hour out of range is ignored, not reported`() {
        val results = CodexScraper.parseUsage("""{"weeklyRemaining":98,"fiveHourRemaining":150}""")
        assertEquals(1, results.size)
        assertEquals("Codex wk", (results[0] as QuotaResult.Success).quota.name)
    }

    @Test
    fun `codex five hour present but weekly missing still reports the weekly error`() {
        // A present 5h value must not mask a broken/missing weekly extraction.
        val results = CodexScraper.parseUsage("""{"weeklyRemaining":-1,"fiveHourRemaining":90}""")
        assertEquals(2, results.size)
        val err = results.filterIsInstance<QuotaResult.Error>().single()
        assertEquals("Weekly usage limit not found on page", err.message)
        assertEquals("Codex 5h", successByName(results, "Codex 5h").quota.name)
    }
}
