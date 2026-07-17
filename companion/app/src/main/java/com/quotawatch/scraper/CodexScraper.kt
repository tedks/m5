package com.quotawatch.scraper

import android.content.Context
import android.util.Log
import com.quotawatch.api.Quota
import com.quotawatch.api.QuotaResult
import org.json.JSONObject

class CodexScraper(contextProvider: () -> Context) {

    companion object {
        const val TAG = "CodexScraper"
        const val USAGE_URL = "https://chatgpt.com/codex/cloud/settings/usage"

        // This account's chatgpt.com/codex/cloud/settings/usage page (verified live 2026-07-17)
        // shows a "Weekly usage limit ... N% remaining" block plus Balance / per-model breakdowns.
        // OpenAI temporarily removed the "5 hour usage limit" section for Plus/Pro/Business on
        // 2026-07-13 (bd m5-u1d) — it's expected to return, so the extraction JS still looks for
        // it (bounded-gap regex, same style as weekly) but we settle on weekly alone: the 5h
        // section may legitimately not exist right now, and gating settle on it would poll the
        // full timeout on every scrape while the removal holds. When 5h does render, the
        // valid-and-stable poll (decoded JSON keeps changing until both fields stop moving) picks
        // it up without any change here.
        private fun isSettled(raw: String): Boolean = try {
            val json = JSONObject(raw)
            json.optDouble("weeklyRemaining", -1.0) >= 0
        } catch (e: Exception) {
            false
        }

        /**
         * Map the extraction JSON to quota results. Pure (no Android deps) so it's unit-testable.
         * The page reports "N% remaining"; we convert to used (100 - remaining). Only values
         * anchored to their labels ("Weekly usage limit" / "5 hour usage limit") reach here.
         * Weekly missing is an honest error (PR2 retains last-known-good numbers on error); 5h
         * missing is silently omitted — it's a deliberately optional section right now, not a
         * failure (bd m5-u1d).
         */
        fun parseUsage(data: String): List<QuotaResult> {
            val json = JSONObject(data)
            if (json.has("error")) {
                return listOf(QuotaResult.Error("codex", json.getString("error")))
            }

            val results = mutableListOf<QuotaResult>()

            // Weekly is the required quota — missing it is always an honest error, even if the
            // (optional) 5h section happened to parse. Keying "did we fail" off results.isEmpty()
            // would let a present 5h value silently mask a broken weekly regex.
            val weeklyRemaining = json.optDouble("weeklyRemaining", -1.0)
            if (weeklyRemaining in 0.0..100.0) {
                results.add(QuotaResult.Success("codex", Quota("Codex wk", (100.0 - weeklyRemaining).toFloat(), 100f, "%")))
            } else {
                results.add(QuotaResult.Error("codex", "Weekly usage limit not found on page"))
            }

            // 5h is optional right now (bd m5-u1d) — present it when found, otherwise say nothing.
            val fiveHourRemaining = json.optDouble("fiveHourRemaining", -1.0)
            if (fiveHourRemaining in 0.0..100.0) {
                results.add(QuotaResult.Success("codex", Quota("Codex 5h", (100.0 - fiveHourRemaining).toFloat(), 100f, "%")))
            }

            return results
        }
    }

    private val scraper = UsageScraper(contextProvider)

    fun isLoggedIn(): Boolean = scraper.hasSession("https://chatgpt.com")

    suspend fun fetchUsage(): List<QuotaResult> {
        if (!isLoggedIn()) {
            return listOf(QuotaResult.Unavailable("codex", "Tap 'Log in' next to Codex in Settings"))
        }

        return try {
            val result = scraper.scrape(USAGE_URL, JS_EXTRACT, isSettled = ::isSettled)
            if (result.sessionExpired) {
                return listOf(QuotaResult.Unavailable("codex", "Session expired — tap 'Re-login' in Settings"))
            }
            if (result.data == null) {
                return listOf(QuotaResult.Error("codex", "Page load timed out"))
            }

            Log.d(TAG, "Parsed: ${result.data.take(200)}")
            parseUsage(result.data)
        } catch (e: Exception) {
            Log.e(TAG, "Scrape failed", e)
            listOf(QuotaResult.Error("codex", e.message ?: "Scrape failed"))
        }
    }

    // The Codex usage page shows "Weekly usage limit\n\nN%\nremaining" (verified live 2026-07-17).
    // Parse that labeled percentage — anchored to "weekly usage limit" so the per-model
    // "100% remaining" rows below it can't be mistaken for the plan's weekly quota. Also look for
    // an optional "5 hour usage limit ... N% remaining" block, same bounded-gap anchoring: OpenAI
    // temporarily removed it 2026-07-13 (bd m5-u1d) but it's expected to come back, and when it
    // does this picks it up with no code change needed.
    private val JS_EXTRACT = """
        (function() {
            try {
                var text = document.body.innerText || '';
                var weeklyRemaining = -1;
                var fiveHourRemaining = -1;

                var weekMatch = text.match(/weekly\s*usage\s*limit[\s\S]{0,40}?(\d+(?:\.\d+)?)\s*%[\s\S]{0,20}?remaining/i);
                if (weekMatch) {
                    weeklyRemaining = parseFloat(weekMatch[1]);
                }

                var fiveMatch = text.match(/5\s*hour\s*usage\s*limit[\s\S]{0,40}?(\d+(?:\.\d+)?)\s*%[\s\S]{0,20}?remaining/i);
                if (fiveMatch) {
                    fiveHourRemaining = parseFloat(fiveMatch[1]);
                }

                return JSON.stringify({
                    weeklyRemaining: weeklyRemaining,
                    fiveHourRemaining: fiveHourRemaining
                });
            } catch(e) {
                return JSON.stringify({error: e.toString()});
            }
        })()
    """.trimIndent()
}
