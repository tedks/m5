package com.quotawatch.scraper

import com.quotawatch.api.QuotaResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CodexScraper.parse tests, against sanitized fixtures captured from chatgpt.com on 2026-11-12
 * (src/test/resources/fixtures): the page text and the rate_limit object of the page's own
 * GET /backend-api/wham/usage response. Each "X is absent" assertion has a twin.
 */
class CodexParseUsageTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("fixtures/$name")!!.readText()

    private fun successes(results: List<QuotaResult>) = results.filterIsInstance<QuotaResult.Success>()
    private fun byName(results: List<QuotaResult>, name: String): QuotaResult.Success? =
        successes(results).firstOrNull { it.quota.name == name }

    // ---------------------------------------------------------------- Codex

    private val codexText = fixture("codex_usage_page_2026-11-12.txt")
    private val codexRateLimit = JSONObject(fixture("codex_wham_usage_rate_limit_2026-11-12.json"))

    private fun codexInput(text: String, rateLimit: JSONObject?, captured: Boolean = rateLimit != null): String {
        val api: Any = if (captured) JSONObject().put("rate_limit", rateLimit ?: JSONObject.NULL) else JSONObject.NULL
        return JSONObject().put("api", api).put("text", text).toString()
    }

    private fun window(seconds: Int, used: Number) =
        JSONObject().put("used_percent", used).put("limit_window_seconds", seconds)
            .put("reset_after_seconds", 1000).put("reset_at", 1800000000)

    @Test
    fun `codex today's page json and text agree on weekly and the json wins`() {
        val p = CodexScraper.parse(codexInput(codexText, codexRateLimit))
        val wk = byName(p.results, "Codex wk")!!
        assertEquals(15f, wk.quota.used, 0.001f)
        // Mutation-checked: api/dom preference swapped -> red (source would be "dom").
        assertEquals("api", wk.source)
        assertTrue(p.warnings.isEmpty())
        // 5h is absent today (secondary_window null, no 5h block); twin: wk above.
        assertNull(byName(p.results, "Codex 5h"))
        assertEquals(1, p.results.size)
    }

    @Test
    fun `codex today's page text alone yields weekly from the label-anchored fallback`() {
        val r = CodexScraper.parse(codexInput(codexText, null)).results
        val wk = byName(r, "Codex wk")!!
        // Mutation-checked: DOM_WEEKLY's "(?:left|remaining)" narrowed to "remaining" (the old
        // regex, which broke on this page) -> red.
        assertEquals(15f, wk.quota.used, 0.001f)
        assertEquals("dom", wk.source)
    }

    @Test
    fun `codex reset-credit text naming the 5-hour and weekly limits is not a value`() {
        // "Use a reset to restore your 5-hour limit, weekly limit, or both" is on today's page.
        // Neither label is followed by a percentage, so nothing may be read from it.
        val resetBlurb = "Usage limit resets\nUse a reset to restore your 5-hour limit, weekly limit, or both\nAvailable\n2\nHistory\n"
        val r = CodexScraper.parse(codexInput(resetBlurb, null)).results
        assertEquals("Weekly usage limit not found on page", (r.single() as QuotaResult.Error).message)
        // Twin: today's page (which contains the same blurb) yields no 5h but does yield weekly.
        val today = CodexScraper.parse(codexInput(codexText, null)).results
        assertNull(byName(today, "Codex 5h"))
        assertTrue(byName(today, "Codex wk") != null)
    }

    @Test
    fun `codex json alone yields weekly`() {
        val r = CodexScraper.parse(codexInput("", codexRateLimit)).results
        assertEquals(15f, byName(r, "Codex wk")!!.quota.used, 0.001f)
    }

    @Test
    fun `codex json and text disagreeing keeps json and warns`() {
        val rl = JSONObject(codexRateLimit.toString()).put("primary_window", window(604800, 30))
        val p = CodexScraper.parse(codexInput(codexText, rl))
        assertEquals(30f, byName(p.results, "Codex wk")!!.quota.used, 0.001f)
        // Mutation-checked: CROSS_CHECK_TOLERANCE raised to 100 -> red.
        assertEquals(1, p.warnings.size)
    }

    @Test
    fun `codex five-hour window in the json is named by its length`() {
        val rl = JSONObject(codexRateLimit.toString()).put("secondary_window", window(18000, 40))
        val r = CodexScraper.parse(codexInput(codexText, rl)).results
        // Mutation-checked: FIVE_HOUR_SECONDS changed to 3600 -> red.
        assertEquals(40f, byName(r, "Codex 5h")!!.quota.used, 0.001f)
        assertEquals(15f, byName(r, "Codex wk")!!.quota.used, 0.001f)
    }

    @Test
    fun `codex window of unknown length is never shown under a guessed name`() {
        val rl = JSONObject().put("primary_window", window(86400, 70))
        // Mutation-checked: unknown-length branch mapped to WEEKLY_NAME instead of null -> red.
        val r = CodexScraper.parse(codexInput("", rl)).results
        assertEquals("Weekly usage limit not found on page", (r.single() as QuotaResult.Error).message)
        // Twin: a week-long window in the same slot is read.
        val rl2 = JSONObject().put("primary_window", window(604800, 70))
        assertEquals(70f, byName(CodexScraper.parse(codexInput("", rl2)).results, "Codex wk")!!.quota.used, 0.001f)
    }

    @Test
    fun `codex json percentage out of range is absent and the text fallback is used`() {
        val rl = JSONObject().put("primary_window", window(604800, 150))
        val r = CodexScraper.parse(codexInput(codexText, rl)).results
        val wk = byName(r, "Codex wk")!!
        assertEquals("dom", wk.source)
        assertEquals(15f, wk.quota.used, 0.001f)
    }

    @Test
    fun `codex older weekly usage limit remaining layout is still read`() {
        val r = CodexScraper.parse(codexInput("Weekly usage limit\n\n98%\nremaining\n", null)).results
        assertEquals(2f, byName(r, "Codex wk")!!.quota.used, 0.001f)
    }

    @Test
    fun `codex nothing found is an honest error`() {
        val r = CodexScraper.parse(codexInput("Settings\nAccount\n", null)).results
        assertEquals("Weekly usage limit not found on page", (r.single() as QuotaResult.Error).message)
    }

    @Test
    fun `codex captured body without rate_limit falls back to text`() {
        val r = CodexScraper.parse(codexInput(codexText, null, captured = true)).results
        assertEquals("dom", byName(r, "Codex wk")!!.source)
    }

    @Test
    fun `codex explicit error field is surfaced`() {
        val r = CodexScraper.parse("""{"error":"boom"}""").results
        assertEquals("boom", (r.single() as QuotaResult.Error).message)
    }

    // ------------------------------------------------ DOM values never bleed across labels
    // Council round 01bb15178930 (anthropic seat): with an unconstrained gap, a label without a
    // value of its own read the NEXT quota's percentage. The gap may not cross '%' or "limit".

    private fun domOnly(text: String) = CodexScraper.parse(codexInput(text, null)).results

    @Test
    fun `codex 5h shown as used does not borrow the weekly value`() {
        val r = domOnly("5-hour limit\nResets in 2h 10m\n30% used\nWeekly limit\nResets in 5d 23h\n85% left\n")
        // Mutation-checked: GAP back to [\s\S]{0,80}? -> red (5h would read 15).
        assertNull(byName(r, "Codex 5h"))
        assertEquals(15f, byName(r, "Codex wk")!!.quota.used, 0.001f)
    }

    @Test
    fun `codex 5h label with no value yet does not borrow the weekly value`() {
        val r = domOnly("5-hour limit\nResets in 2h 10m\nWeekly limit\nResets in 5d 23h\n85% left\n")
        assertNull(byName(r, "Codex 5h"))
        assertEquals(15f, byName(r, "Codex wk")!!.quota.used, 0.001f)
    }

    @Test
    fun `codex reset blurb above the plan limits does not lend weekly to 5h`() {
        val r = domOnly("Use a reset to restore your 5-hour limit, weekly limit, or both\nAvailable\n2\nWeekly limit\nResets in 5d 23h\n85% left\n")
        assertNull(byName(r, "Codex 5h"))
        assertEquals(15f, byName(r, "Codex wk")!!.quota.used, 0.001f)
    }

    @Test
    fun `codex weekly shown as used does not borrow the 5h value`() {
        val r = domOnly("Weekly limit\nResets in 5d 23h\n15% used\n5-hour limit\nResets in 2h\n70% left\n")
        // Mutation-checked: GAP back to [\s\S]{0,80}? -> red (weekly would read 30).
        assertEquals("Weekly usage limit not found on page", (r.first { it is QuotaResult.Error } as QuotaResult.Error).message)
        assertNull(byName(r, "Codex wk"))
        // Twin: the 5h block's own value is read.
        assertEquals(30f, byName(r, "Codex 5h")!!.quota.used, 0.001f)
    }

    @Test
    fun `codex 5h block in the text is read when the json has none`() {
        val r = domOnly("5-hour limit\nResets in 2h 10m\n70% left\nWeekly limit\nResets in 5d 23h\n85% left\n")
        // Mutation-checked: DOM_FIVE_HOUR made to never match -> red.
        val h = byName(r, "Codex 5h")!!
        assertEquals(30f, h.quota.used, 0.001f)
        assertEquals("dom", h.source)
        assertEquals(15f, byName(r, "Codex wk")!!.quota.used, 0.001f)
    }

    @Test
    fun `codex older 5 hour usage limit remaining layout is read`() {
        val r = domOnly("5 hour usage limit\n\n60%\nremaining\nWeekly usage limit\n\n98%\nremaining\n")
        assertEquals(40f, byName(r, "Codex 5h")!!.quota.used, 0.001f)
        assertEquals(2f, byName(r, "Codex wk")!!.quota.used, 0.001f)
    }

    @Test
    fun `codex 25-hour is not a 5-hour limit`() {
        val r = domOnly("25-hour limit\n10% left\nWeekly limit\n85% left\n")
        // Mutation-checked: line-start anchor removed -> red.
        assertNull(byName(r, "Codex 5h"))
        // Twin: the same text with a real 5-hour label is read.
        assertEquals(90f, byName(domOnly("5-hour limit\n10% left\nWeekly limit\n85% left\n"), "Codex 5h")!!.quota.used, 0.001f)
    }

    @Test
    fun `codex five hour present but weekly missing still reports the weekly error`() {
        val rl = JSONObject().put("secondary_window", window(18000, 40))
        val r = CodexScraper.parse(codexInput("", rl)).results
        // Mutation-checked: the weekly-missing Error branch removed -> red.
        assertEquals("Weekly usage limit not found on page", (r.first { it is QuotaResult.Error } as QuotaResult.Error).message)
        assertEquals(40f, byName(r, "Codex 5h")!!.quota.used, 0.001f)
        assertEquals(2, r.size)
    }

    @Test
    fun `codex rounding difference within tolerance does not warn`() {
        // The page renders 85% left for a used_percent of 15.4; that is rounding, not disagreement.
        val rl = JSONObject().put("primary_window", window(604800, 15.4))
        val p = CodexScraper.parse(codexInput(codexText, rl))
        // Mutation-checked: CROSS_CHECK_TOLERANCE lowered to 0 -> red.
        assertTrue(p.warnings.isEmpty())
        assertEquals(15.4f, byName(p.results, "Codex wk")!!.quota.used, 0.001f)
    }

    @Test
    fun `codex reset blurb beside an unrelated percentage is not a value`() {
        // Council round 01bb15178930 (openai seat): the blurb names both limits mid-sentence; a
        // percentage that follows must not be read as either of them.
        val r = domOnly("Use a reset to restore your 5-hour limit, weekly limit, or both\nAvailable\n2\n85% left\n")
        // Mutation-checked: line-start anchor removed from DOM_WEEKLY -> red.
        assertEquals("Weekly usage limit not found on page", (r.single() as QuotaResult.Error).message)
        // Twin: the same percentage under a real "Weekly limit" line is read.
        val r2 = domOnly("Use a reset to restore your 5-hour limit, weekly limit, or both\nAvailable\n2\nWeekly limit\n85% left\n")
        assertEquals(15f, byName(r2, "Codex wk")!!.quota.used, 0.001f)
        assertNull(byName(r2, "Codex 5h"))
    }
}
