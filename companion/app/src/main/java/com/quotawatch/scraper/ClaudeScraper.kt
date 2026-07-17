package com.quotawatch.scraper

import android.content.Context
import android.util.Log
import com.quotawatch.api.Quota
import com.quotawatch.api.QuotaResult
import org.json.JSONObject

class ClaudeScraper(contextProvider: () -> Context) {

    companion object {
        const val TAG = "ClaudeScraper"
        const val USAGE_URL = "https://claude.ai/settings/usage"

        // claude.ai/settings/usage client-redirects to /new#settings/usage and renders usage as a
        // modal ([role="dialog"]) over the app shell. The 5-hour ("Current session") and weekly
        // ("Weekly limits") sections can render on separate ticks, so only require ONE of them to
        // be present — the scraper's valid-and-stable check waits out the other section catching up
        // as long as there's time left, and the timeout covers the modal never rendering. A false
        // predicate (both values absent) keeps polling; it never accepts unlabeled junk because the
        // extraction JS only emits values anchored to the "Current session"/"Weekly limits" labels.
        private fun isSettled(raw: String): Boolean = try {
            val json = JSONObject(raw)
            json.optDouble("fiveHourPct", -1.0) >= 0 || json.optDouble("sevenDayPct", -1.0) >= 0
        } catch (e: Exception) {
            false
        }

        /**
         * Map the extraction JSON to quota results. Pure (no Android deps) so it's unit-testable.
         *
         * Only values the JS extracted from explicitly-labeled usage elements ("Current session",
         * "Weekly limits") reach here as `fiveHourPct` / `sevenDayPct`; anything else is absent
         * (-1). When neither is present we fail honestly with a distinguishable message — since PR2
         * a failed scrape retains last-known-good numbers rather than blanking the display, so an
         * honest error is strictly better than a fabricated percentage.
         */
        fun parseUsage(data: String): List<QuotaResult> {
            val json = JSONObject(data)
            if (json.has("error")) {
                return listOf(QuotaResult.Error("claude", json.getString("error")))
            }

            val results = mutableListOf<QuotaResult>()

            val fiveHourPct = json.optDouble("fiveHourPct", -1.0)
            if (fiveHourPct in 0.0..100.0) {
                results.add(QuotaResult.Success("claude", Quota("Claude 5h", fiveHourPct.toFloat(), 100f, "%")))
            }

            val sevenDayPct = json.optDouble("sevenDayPct", -1.0)
            if (sevenDayPct in 0.0..100.0) {
                results.add(QuotaResult.Success("claude", Quota("Claude wk", sevenDayPct.toFloat(), 100f, "%")))
            }

            if (results.isEmpty()) {
                val msg = if (json.optBoolean("usageReady", false)) {
                    "Usage values not found on page"
                } else {
                    "Usage panel did not render"
                }
                results.add(QuotaResult.Error("claude", msg))
            }

            return results
        }
    }

    private val scraper = UsageScraper(contextProvider)

    fun isLoggedIn(): Boolean = scraper.hasSession("https://claude.ai")

    suspend fun fetchUsage(): List<QuotaResult> {
        if (!isLoggedIn()) {
            return listOf(QuotaResult.Unavailable("claude", "Tap 'Log in' next to Claude Code in Settings"))
        }

        return try {
            val result = scraper.scrape(USAGE_URL, JS_EXTRACT, isSettled = ::isSettled)
            if (result.sessionExpired) {
                return listOf(QuotaResult.Unavailable("claude", "Session expired — tap 'Re-login' in Settings"))
            }
            if (result.data == null) {
                return listOf(QuotaResult.Error("claude", "Page load timed out"))
            }

            Log.d(TAG, "Parsed: ${result.data.take(200)}")
            parseUsage(result.data)
        } catch (e: Exception) {
            Log.e(TAG, "Scrape failed", e)
            listOf(QuotaResult.Error("claude", e.message ?: "Scrape failed"))
        }
    }

    // Extraction targets the real usage modal on claude.ai/new#settings/usage (mobile WebView),
    // verified live 2026-07-17. The modal renders (as [role="dialog"]) within ~3s of the redirect
    // and reads:
    //   Plan usage limits
    //   Current session  · Resets in N hr M min · 7% used         <- 5-hour rolling window
    //   Weekly limits
    //     All models     · Resets Sat 12:00 PM  · 15% used        <- overall weekly limit
    //     <model name>   · Resets Sat 12:00 PM  · 11% used        <- per-model weekly limit
    //   Usage credits    · 0% used
    // We pull ONLY values anchored to these labels — no "first percentage anywhere" fallback, which
    // previously grabbed sidebar/chat-list junk and silently reported ~2-3% (bd m5-39u/m5-iia).
    private val JS_EXTRACT = """
        (function() {
            try {
                // Prefer the usage modal's text; fall back to body text (the anchors below are
                // label-specific and appear only inside the usage panel, so either is safe).
                var searchText = '';
                var dialogs = document.querySelectorAll('[role="dialog"],[aria-modal="true"]');
                for (var i = 0; i < dialogs.length; i++) {
                    var dt = dialogs[i].innerText || '';
                    if (/plan usage limits|current session|weekly limits/i.test(dt)) {
                        searchText = dt;
                        break;
                    }
                }
                if (!searchText) searchText = document.body.innerText || '';

                var usageReady = /plan usage limits|current session|weekly limits/i.test(searchText);

                // 5-hour rolling window: the "% used" value that follows the "Current session"
                // label. Bounded gap so a stray later "% used" can't be captured.
                var fiveHourPct = -1;
                var fiveMatch = searchText.match(/current session[\s\S]{0,160}?(\d+(?:\.\d+)?)\s*%\s*used/i);
                if (fiveMatch) fiveHourPct = parseFloat(fiveMatch[1]);

                // Weekly: prefer the "All models" (overall) limit under "Weekly limits"; fall back
                // to the first weekly "% used" if that specific label isn't present.
                var sevenDayPct = -1;
                var weekMatch = searchText.match(/weekly limits[\s\S]*?all models[\s\S]{0,160}?(\d+(?:\.\d+)?)\s*%\s*used/i);
                if (!weekMatch) {
                    weekMatch = searchText.match(/weekly limits[\s\S]{0,200}?(\d+(?:\.\d+)?)\s*%\s*used/i);
                }
                if (weekMatch) sevenDayPct = parseFloat(weekMatch[1]);

                return JSON.stringify({
                    usageReady: usageReady,
                    fiveHourPct: fiveHourPct,
                    sevenDayPct: sevenDayPct
                });
            } catch(e) {
                return JSON.stringify({error: e.toString()});
            }
        })()
    """.trimIndent()
}
