package com.quotawatch.scraper

import com.quotawatch.api.QuotaResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ClaudeScraper.parse tests. The in-page JS only collects inputs (the usage panel text and the
 * page's own usage JSON); all extraction is in the pure Kotlin tested here, against sanitized
 * fixtures captured from claude.ai on 2026-11-12 (src/test/resources/fixtures). Each "X is
 * absent" assertion has a twin on the same input proving the input is live.
 */
class ClaudeParseUsageTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("fixtures/$name")!!.readText()

    private fun successes(results: List<QuotaResult>) = results.filterIsInstance<QuotaResult.Success>()
    private fun byName(results: List<QuotaResult>, name: String): QuotaResult.Success? =
        successes(results).firstOrNull { it.quota.name == name }

    // ---------------------------------------------------------------- Claude

    private fun claudeInput(text: String, usageReady: Boolean = true, api: JSONObject? = null): String =
        JSONObject().put("usageReady", usageReady).put("api", api ?: JSONObject.NULL).put("text", text).toString()

    private val claudeNov = fixture("claude_usage_dialog_2026-11-12.txt")

    // A July 2026 panel, from the layout recorded in ClaudeScraper's old extraction comment.
    private val claudeJuly = "Plan usage limits\nCurrent session\nResets in 3 hr 12 min\n7% used\n" +
        "Weekly limits\nAll models\nResets Sat 12:00 PM\n15% used\nSonnet\nResets Sat 12:00 PM\n11% used\n" +
        "Usage credits\n0% used\n"

    @Test
    fun `claude november layout yields weekly and the per-model row from the page text`() {
        // Mutation-checked: DOM_WEEKLY's "this week" anchor changed to "next week" -> red.
        val r = ClaudeScraper.parse(claudeInput(claudeNov)).results
        assertEquals(60f, byName(r, "Claude wk")!!.quota.used, 0.001f)
        assertEquals("dom", byName(r, "Claude wk")!!.source)
        // Mutation-checked: DOM_MODEL's "of your weekly limit" anchor removed from the match -> red.
        assertEquals(64f, byName(r, "Fable wk")!!.quota.used, 0.001f)
        assertEquals(2, r.size)
    }

    @Test
    fun `claude november layout has no session row and nothing invented for it`() {
        // Twin: the same input yields Claude wk (previous test), so the fixture is live.
        val r = ClaudeScraper.parse(claudeInput(claudeNov)).results
        assertNull(byName(r, "Claude 5h"))
        assertTrue(r.none { it is QuotaResult.Error })
    }

    @Test
    fun `claude per-product breakdown is never read as the weekly limit`() {
        // "This week's usage by product / Claude Code / 100%" sits in the same panel. The weekly
        // value must be the 60% under the exact "This week" line, not 100.
        val r = ClaudeScraper.parse(claudeInput(claudeNov)).results
        assertTrue(successes(r).none { it.quota.used == 100f })
        // Even if the breakdown ever said "% used", it is not under an exact "This week" line.
        val onlyBreakdown = "Your usage\nThis week’s usage by product\nClaude Code\n100% used\nChats\n0% used\n"
        // Mutation-checked: DOM_WEEKLY's exact-line anchor replaced by a bare "this week" -> red.
        val r2 = ClaudeScraper.parse(claudeInput(onlyBreakdown)).results
        assertEquals("Usage values not found on page", (r2.single() as QuotaResult.Error).message)
    }

    @Test
    fun `claude july layout still yields session and all-models weekly`() {
        val r = ClaudeScraper.parse(claudeInput(claudeJuly)).results
        // Mutation-checked: DOM_SESSION removed -> red.
        assertEquals(7f, byName(r, "Claude 5h")!!.quota.used, 0.001f)
        // Mutation-checked: DOM_WEEKLY_OLD removed (falls to nothing; the July text has no
        // "This week" line) -> red.
        assertEquals(15f, byName(r, "Claude wk")!!.quota.used, 0.001f)
        // The July per-model row has no "of your weekly limit" label, so it is not taken.
        assertEquals(2, r.size)
    }

    @Test
    fun `claude unlabeled percentage is never taken`() {
        val junk = "Recents\nSomething 42% used\nOther chat\n"
        val r = ClaudeScraper.parse(claudeInput(junk, usageReady = false)).results
        assertEquals("Usage panel did not render", (r.single() as QuotaResult.Error).message)
        // Twin: the same number under the real label is read.
        val labeled = "Your usage\nThis week\nResets Saturday 11:00 AM\n42% used\n"
        assertEquals(42f, byName(ClaudeScraper.parse(claudeInput(labeled)).results, "Claude wk")!!.quota.used, 0.001f)
    }

    @Test
    fun `claude panel rendered without values is an honest error`() {
        val r = ClaudeScraper.parse(claudeInput("Your usage\nLoading…\n")).results
        assertEquals("Usage values not found on page", (r.single() as QuotaResult.Error).message)
    }

    @Test
    fun `claude out-of-range percentage is absent, not reported`() {
        val r = ClaudeScraper.parse(claudeInput("Your usage\nThis week\nResets Saturday\n150% used\n")).results
        assertTrue(r.single() is QuotaResult.Error)
        val ok = ClaudeScraper.parse(claudeInput("Your usage\nThis week\nResets Saturday\n100% used\n")).results
        assertEquals(100f, byName(ok, "Claude wk")!!.quota.used, 0.001f)
    }

    @Test
    fun `claude explicit error field is surfaced`() {
        val r = ClaudeScraper.parse("""{"error":"boom"}""").results
        assertEquals("boom", (r.single() as QuotaResult.Error).message)
    }

    @Test
    fun `claude model rows are capped and names fit the firmware name field`() {
        val rows = (1..6).joinToString("") { "Model$it\nUp to 50% of your weekly limit\nResets Saturday\n${it * 10}% used\n" }
        val r = ClaudeScraper.parse(claudeInput("Your usage\nThis week\nResets Saturday\n5% used\n$rows")).results
        val models = successes(r).filter { it.quota.name != "Claude wk" }
        // Mutation-checked: MAX_MODEL_ROWS raised to 10 -> red.
        assertEquals(4, models.size)
        assertEquals(listOf("Model1 wk", "Model2 wk", "Model3 wk", "Model4 wk"), models.map { it.quota.name })
    }

    @Test
    fun `model row names are short, colon-free, or rejected`() {
        assertEquals("Fable wk", ClaudeScraper.modelRowName("Fable"))
        assertEquals("Opus 4.5 wk", ClaudeScraper.modelRowName("  Opus   4.5 "))
        val long = ClaudeScraper.modelRowName("Extremely Long Model Name")!!
        // Mutation-checked: MODEL_LABEL_MAX raised to 20 -> red.
        assertTrue(long.length <= 15)
        assertEquals("Extremely Lo wk", long)
        // Mutation-checked: allowed-character check removed -> red (colon would split the BLE row).
        assertNull(ClaudeScraper.modelRowName("a:b"))
        assertNull(ClaudeScraper.modelRowName("42% used"))
        assertNull(ClaudeScraper.modelRowName(""))
    }

    // -- the page's own usage JSON (GET /api/organizations/<org>/usage, fields limits /
    //    five_hour / seven_day as JS_EXTRACT passes them through)

    private val claudeApi = JSONObject(fixture("claude_usage_api_projection_2026-11-12.json"))

    @Test
    fun `claude today's json and panel text disagree slightly and the json wins`() {
        val p = ClaudeScraper.parse(claudeInput(claudeNov, api = claudeApi))
        // Today's JSON says 62/66 while the fixture text (captured a few minutes earlier) says
        // 60/64: a real 2-point drift, so a warning per row is expected and the JSON is used.
        val wk = byName(p.results, "Claude wk")!!
        assertEquals(62f, wk.quota.used, 0.001f)
        // Mutation-checked: api/dom preference swapped -> red.
        assertEquals("api", wk.source)
        assertEquals(66f, byName(p.results, "Fable wk")!!.quota.used, 0.001f)
        assertEquals(2, p.warnings.size)
        assertEquals(2, p.results.size)
    }

    @Test
    fun `claude json alone yields weekly and the scoped model row`() {
        val r = ClaudeScraper.parse(claudeInput("", usageReady = false, api = claudeApi)).results
        assertEquals(62f, byName(r, "Claude wk")!!.quota.used, 0.001f)
        // Mutation-checked: "weekly_scoped" kind mapping removed -> red.
        assertEquals(66f, byName(r, "Fable wk")!!.quota.used, 0.001f)
        assertNull(byName(r, "Claude 5h"))
    }

    @Test
    fun `claude json limits list wins over the legacy seven_day field`() {
        val api = JSONObject(claudeApi.toString())
        api.getJSONObject("seven_day").put("utilization", 10.0)
        val r = ClaudeScraper.parse(claudeInput("", usageReady = false, api = api)).results
        // Mutation-checked: "weekly_all" kind mapping removed (falls back to seven_day=10) -> red.
        assertEquals(62f, byName(r, "Claude wk")!!.quota.used, 0.001f)
    }

    @Test
    fun `claude legacy five_hour and seven_day fields are read when limits is absent`() {
        val api = JSONObject().put("limits", JSONObject.NULL)
            .put("five_hour", JSONObject().put("utilization", 22.0))
            .put("seven_day", JSONObject().put("utilization", 41.0))
        val r = ClaudeScraper.parse(claudeInput("", usageReady = false, api = api)).results
        // Mutation-checked: five_hour fallback removed -> red.
        assertEquals(22f, byName(r, "Claude 5h")!!.quota.used, 0.001f)
        assertEquals(41f, byName(r, "Claude wk")!!.quota.used, 0.001f)
    }

    @Test
    fun `claude session kind in limits becomes the 5h row`() {
        // The "session" kind is in claude.ai's own bundle (seen 2026-11-12) though this account's
        // plan does not show one today.
        val api = JSONObject().put("limits", org.json.JSONArray()
            .put(JSONObject().put("kind", "session").put("group", "session").put("percent", 38))
            .put(JSONObject().put("kind", "weekly_all").put("group", "weekly").put("percent", 41)))
        val r = ClaudeScraper.parse(claudeInput("", usageReady = false, api = api)).results
        // Mutation-checked: "session" kind mapping removed -> red.
        assertEquals(38f, byName(r, "Claude 5h")!!.quota.used, 0.001f)
        assertEquals(41f, byName(r, "Claude wk")!!.quota.used, 0.001f)
    }

    @Test
    fun `claude unknown or model-less limits are never shown under a guessed name`() {
        val api = JSONObject().put("limits", org.json.JSONArray()
            .put(JSONObject().put("kind", "monthly_mystery").put("percent", 77))
            .put(JSONObject().put("kind", "weekly_scoped").put("percent", 55)
                .put("scope", JSONObject().put("model", JSONObject.NULL).put("surface", JSONObject().put("display_name", "Cowork"))))
            .put(JSONObject().put("kind", "weekly_scoped").put("percent", 150)
                .put("scope", JSONObject().put("model", JSONObject().put("display_name", "Opus")))))
        val r = ClaudeScraper.parse(claudeInput("", usageReady = true, api = api)).results
        // Mutation-checked: unknown-kind branch mapped to WEEKLY_NAME -> red.
        assertEquals("Usage values not found on page", (r.single() as QuotaResult.Error).message)
        // Twin: a well-formed scoped model limit in the same list is read.
        api.getJSONArray("limits").put(JSONObject().put("kind", "weekly_scoped").put("percent", 12)
            .put("scope", JSONObject().put("model", JSONObject().put("display_name", "Opus"))))
        assertEquals(12f, byName(ClaudeScraper.parse(claudeInput("", api = api)).results, "Opus wk")!!.quota.used, 0.001f)
    }

    // ------------------------------------------------ DOM values never bleed across rows
    // Same class as the Codex council finding (PR #27, round 01bb15178930): a label whose value
    // has not rendered must not borrow the next row's "N% used".

    private fun dom(text: String) = ClaudeScraper.parse(claudeInput(text)).results

    @Test
    fun `claude weekly row without a value does not borrow the model row value`() {
        val r = dom("Your usage\nThis week\nResets Saturday 11:00 AM\nFable\nUp to 50% of your weekly limit\nResets Saturday 11:00 AM\n69% used\n")
        // Mutation-checked: gap() back to [\s\S]{0,N}? -> red (Claude wk would read 69).
        assertNull(byName(r, "Claude wk"))
        // Twin: the model row keeps its own value.
        assertEquals(69f, byName(r, "Fable wk")!!.quota.used, 0.001f)
    }

    @Test
    fun `claude session row without a value does not borrow the weekly value`() {
        val r = dom("Plan usage limits\nCurrent session\nResets in 3 hr\nWeekly limits\nAll models\nResets Sat\n15% used\n")
        // Mutation-checked: gap() back to [\s\S]{0,N}? -> red (Claude 5h would read 15).
        assertNull(byName(r, "Claude 5h"))
        assertEquals(15f, byName(r, "Claude wk")!!.quota.used, 0.001f)
    }

    @Test
    fun `claude model row without a value does not borrow the next model row value`() {
        val r = dom("Your usage\nThis week\nResets Sat\n5% used\nFable\nUp to 50% of your weekly limit\nSpark\nUp to 30% of your weekly limit\nResets Sat\n12% used\n")
        assertNull(byName(r, "Fable wk"))
        // Twin: the row that has a value is read, under its own name.
        assertEquals(12f, byName(r, "Spark wk")!!.quota.used, 0.001f)
        assertEquals(5f, byName(r, "Claude wk")!!.quota.used, 0.001f)
    }

    @Test
    fun `claude rows never read past another percentage`() {
        // An unlisted line carrying its own percentage sits between a label and a later "% used".
        val wk = dom("Your usage\nThis week\nResets Sat\nExtra 30% of credits\n20% used\n")
        // Mutation-checked: '%' dropped from gap()'s exclusions -> red.
        assertNull(byName(wk, "Claude wk"))
        val model = dom("Your usage\nFable\nUp to 50% of your weekly limit\nExtra 30% of credits\n20% used\n")
        // Mutation-checked: '%' dropped from the model row's intermediate lines -> red.
        assertNull(byName(model, "Fable wk"))
        // Twin: without the intervening percentage both rows are read.
        assertEquals(20f, byName(dom("Your usage\nThis week\nResets Sat\n20% used\n"), "Claude wk")!!.quota.used, 0.001f)
        assertEquals(20f, byName(dom("Your usage\nFable\nUp to 50% of your weekly limit\nResets Sat\n20% used\n"), "Fable wk")!!.quota.used, 0.001f)
    }

    @Test
    fun `claude july all-models row without a value does not borrow a model row value`() {
        // Council round af493653dfd7 (openai seat), Critical: "Sonnet" is a plain model-name line,
        // not a known label, so only a line-structured row (label, decoration, value) stops it.
        val r = dom("Plan usage limits\nWeekly limits\nAll models\nResets Sat 12:00 PM\nSonnet\nResets Sat 12:00 PM\n11% used\n")
        // Mutation-checked: DECOR widened to any line without '%' -> red (Claude wk would read 11).
        assertNull(byName(r, "Claude wk"))
        // Twin: the same rows with the all-models value present are read.
        assertEquals(15f, byName(dom(claudeJuly), "Claude wk")!!.quota.used, 0.001f)
    }

    @Test
    fun `claude rows tolerate icon-glyph and reset lines but nothing else`() {
        val glyph = "\uE027"
        assertEquals(60f, byName(dom("Your usage\nThis week\n$glyph\nResets Saturday\n60% used\n"), "Claude wk")!!.quota.used, 0.001f)
        // A line with letters that is not a reset line is not decoration.
        assertNull(byName(dom("Your usage\nThis week\nSomething else\n60% used\n"), "Claude wk"))
    }

    private fun scoped(name: String, pct: Int) = JSONObject().put("kind", "weekly_scoped").put("percent", pct)
        .put("scope", JSONObject().put("model", JSONObject().put("display_name", name)))

    @Test
    fun `claude model names that collide after truncation are omitted, not merged`() {
        // Council round af493653dfd7 (openai seat), Important: both become "Claude Opus wk".
        val api = JSONObject().put("limits", org.json.JSONArray()
            .put(JSONObject().put("kind", "weekly_all").put("percent", 40))
            .put(scoped("Claude Opus 4.5", 10)).put(scoped("Claude Opus 4.6", 90)).put(scoped("Fable", 30)))
        val r = ClaudeScraper.parse(claudeInput("", usageReady = false, api = api)).results
        // Mutation-checked: the collision check in modelRows removed -> red.
        assertTrue(successes(r).none { it.quota.name == "Claude Opus wk" })
        // Twin: a non-colliding model row in the same response is kept.
        assertEquals(30f, byName(r, "Fable wk")!!.quota.used, 0.001f)
        assertEquals(40f, byName(r, "Claude wk")!!.quota.used, 0.001f)
        // The same collision in the page text is omitted too.
        val text = "Your usage\nClaude Opus 4.5\nUp to 50% of your weekly limit\n10% used\nClaude Opus 4.6\nUp to 50% of your weekly limit\n90% used\n"
        assertNull(byName(dom(text), "Claude Opus wk"))
        assertEquals(10f, byName(dom(text.substringBefore("Claude Opus 4.6")), "Claude Opus wk")!!.quota.used, 0.001f)
    }

    @Test
    fun `claude model named like an aggregate row is omitted`() {
        // A scoped model whose name is "Claude" would become "Claude wk", the all-models row.
        val api = JSONObject().put("limits", org.json.JSONArray()
            .put(JSONObject().put("kind", "weekly_all").put("percent", 40)).put(scoped("Claude", 99)))
        val r = ClaudeScraper.parse(claudeInput("", usageReady = false, api = api)).results
        // Mutation-checked: the reserved-name check in modelRows removed -> red (99 could win).
        assertEquals(40f, byName(r, "Claude wk")!!.quota.used, 0.001f)
        // Twin: order reversed; the aggregate still keeps its own value.
        val api2 = JSONObject().put("limits", org.json.JSONArray()
            .put(scoped("Claude", 99)).put(JSONObject().put("kind", "weekly_all").put("percent", 40)))
        assertEquals(40f, byName(ClaudeScraper.parse(claudeInput("", usageReady = false, api = api2)).results, "Claude wk")!!.quota.used, 0.001f)
    }

    @Test
    fun `claude model with a null display name is no row`() {
        // PR #28 council (anthropic I1). On Android, optString would turn this into "null".
        val m = JSONObject().put("kind", "weekly_scoped").put("percent", 50)
            .put("scope", JSONObject().put("model", JSONObject().put("display_name", JSONObject.NULL)).put("surface", JSONObject.NULL))
        val r = ClaudeScraper.parse(claudeInput("", usageReady = false, api = JSONObject().put("limits", org.json.JSONArray().put(m)))).results
        assertTrue(successes(r).isEmpty())
        // A JVM test cannot reproduce Android's coercion; this pins that only a real string is read.
        assertNull(byName(r, "null wk"))
    }

    @Test
    fun `claude model-and-surface limit is not the model's weekly row`() {
        // PR #28 council (anthropic I2c).
        val withSurface = scoped("Fable", 80).also { it.getJSONObject("scope").put("surface", JSONObject().put("name", "Cowork")) }
        val api = JSONObject().put("limits", org.json.JSONArray().put(withSurface).put(scoped("Fable", 30)))
        val r = ClaudeScraper.parse(claudeInput("", usageReady = false, api = api)).results
        // Mutation-checked: the surface check removed -> red (80 would win as first).
        assertEquals(30f, byName(r, "Fable wk")!!.quota.used, 0.001f)
        // Twin: alone, the surface-scoped limit yields no Fable row at all.
        val only = JSONObject().put("limits", org.json.JSONArray().put(withSurface))
        assertNull(byName(ClaudeScraper.parse(claudeInput("", usageReady = false, api = only)).results, "Fable wk"))
    }

    @Test
    fun `claude model rows are capped across json and text together`() {
        // PR #28 council (anthropic N1).
        val arr = org.json.JSONArray().put(JSONObject().put("kind", "weekly_all").put("percent", 5))
        listOf("Aa", "Bb", "Cc", "Dd", "Ee").forEachIndexed { i, n -> arr.put(scoped(n, 10 + i)) }
        val apiOnly = ClaudeScraper.parse(claudeInput("", usageReady = false, api = JSONObject().put("limits", arr))).results
        // Mutation-checked: the union cap in parse removed -> red (5 rows).
        assertEquals(4, successes(apiOnly).count { it.quota.name != "Claude wk" })
        val text = listOf("Ff", "Gg", "Hh").joinToString("") { "$it\nUp to 50% of your weekly limit\n20% used\n" }
        val both = ClaudeScraper.parse(claudeInput("Your usage\n$text", api = JSONObject().put("limits", arr))).results
        // Mutation-checked: the union cap in parse removed -> red (7 model rows).
        assertEquals(4, successes(both).count { it.quota.name != "Claude wk" })
        assertTrue(successes(both).none { it.quota.name == "Ff wk" })
    }

    @Test
    fun `row lines are never taken as a model name`() {
        // PR #28 council (anthropic N4): a layout that drops the name line.
        assertNull(ClaudeScraper.modelRowName("Resets Saturday 11 AM"))
        assertNull(ClaudeScraper.modelRowName("Up to 50 of your weekly limit"))
        // Twin: a model name starting with similar letters is still a name.
        assertEquals("Reset wk", ClaudeScraper.modelRowName("Reset"))
        assertEquals("Upton wk", ClaudeScraper.modelRowName("Upton"))
    }
}
