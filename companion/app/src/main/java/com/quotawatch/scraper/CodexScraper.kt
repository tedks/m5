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
        // shows a "Weekly usage limit ... N% remaining" block plus Balance / per-model breakdowns —
        // there is NO "5 hour usage limit" section (bd m5-hf9/m5-iia; the old 5h regex was a
        // phantom that never matched). We settle as soon as the weekly value is present; the
        // per-model "100% remaining" rows are deliberately not captured (they're not a plan quota).
        private fun isSettled(raw: String): Boolean = try {
            val json = JSONObject(raw)
            json.optDouble("weeklyRemaining", -1.0) >= 0
        } catch (e: Exception) {
            false
        }

        /**
         * Map the extraction JSON to quota results. Pure (no Android deps) so it's unit-testable.
         * The page reports "N% remaining"; we convert to used (100 - remaining). Only the value
         * anchored to the "Weekly usage limit" label reaches here; if it's absent we fail honestly
         * (PR2 retains last-known-good numbers on error).
         */
        fun parseUsage(data: String): List<QuotaResult> {
            val json = JSONObject(data)
            if (json.has("error")) {
                return listOf(QuotaResult.Error("codex", json.getString("error")))
            }

            val results = mutableListOf<QuotaResult>()

            val weeklyRemaining = json.optDouble("weeklyRemaining", -1.0)
            if (weeklyRemaining in 0.0..100.0) {
                results.add(QuotaResult.Success("codex", Quota("Codex wk", (100.0 - weeklyRemaining).toFloat(), 100f, "%")))
            }

            if (results.isEmpty()) {
                results.add(QuotaResult.Error("codex", "Weekly usage limit not found on page"))
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
    // Parse only that labeled percentage — anchored to "weekly usage limit" so the per-model
    // "100% remaining" rows below it can't be mistaken for the plan's weekly quota.
    private val JS_EXTRACT = """
        (function() {
            try {
                var text = document.body.innerText || '';
                var weeklyRemaining = -1;

                var weekMatch = text.match(/weekly\s*usage\s*limit[\s\S]{0,40}?(\d+(?:\.\d+)?)\s*%[\s\S]{0,20}?remaining/i);
                if (weekMatch) {
                    weeklyRemaining = parseFloat(weekMatch[1]);
                }

                return JSON.stringify({
                    weeklyRemaining: weeklyRemaining
                });
            } catch(e) {
                return JSON.stringify({error: e.toString()});
            }
        })()
    """.trimIndent()
}
