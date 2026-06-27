package com.quotawatch.scraper

import android.content.Context
import android.util.Log
import com.quotawatch.api.Quota
import com.quotawatch.api.QuotaResult
import org.json.JSONObject

class CodexScraper(context: Context) {

    companion object {
        const val TAG = "CodexScraper"
        const val USAGE_URL = "https://chatgpt.com/codex/cloud/settings/usage"
    }

    private val scraper = UsageScraper(context)

    fun isLoggedIn(): Boolean = scraper.hasSession("https://chatgpt.com")

    suspend fun fetchUsage(): QuotaResult {
        if (!isLoggedIn()) {
            return QuotaResult.Unavailable("Codex", "Tap 'Log in' next to Codex in Settings")
        }

        return try {
            val result = scraper.scrape(USAGE_URL, JS_EXTRACT)
            if (result.sessionExpired) {
                return QuotaResult.Unavailable("Codex", "Session expired — tap 'Re-login' in Settings")
            }
            if (result.data == null) {
                return QuotaResult.Error("Codex", "Page load timed out")
            }

            val jsonStr = result.data.trim().removeSurrounding("\"").replace("\\\"", "\"")
                .replace("\\n", "\n").replace("\\\\", "\\")
            Log.d(TAG, "Parsed: ${jsonStr.take(300)}")

            val json = JSONObject(jsonStr)

            if (json.has("error")) {
                return QuotaResult.Error("Codex", json.getString("error"))
            }

            // Page shows "X% remaining" — we want "used"
            val fiveHourRemaining = json.optDouble("fiveHourRemaining", -1.0)
            if (fiveHourRemaining >= 0) {
                val used = (100.0 - fiveHourRemaining).toFloat()
                return QuotaResult.Success(Quota("Codex", used, 100f, "%"))
            }

            val text = json.optString("text", "")
            QuotaResult.Error("Codex", "Could not parse. Text: ${text.take(200)}")
        } catch (e: Exception) {
            Log.e(TAG, "Scrape failed", e)
            QuotaResult.Error("Codex", e.message ?: "Scrape failed")
        }
    }

    // The Codex analytics page shows:
    //   "5 hour usage limit\n\n100%\nremaining\n\nWeekly usage limit\n\n94%\nremaining"
    // Parse these "remaining" percentages.
    private val JS_EXTRACT = """
        (function() {
            try {
                var text = document.body.innerText || '';
                var fiveHourRemaining = -1;
                var weeklyRemaining = -1;

                // Parse "5 hour usage limit\n\nX%\nremaining"
                var fiveMatch = text.match(/5\s*hour\s*usage\s*limit\s*[\n\s]*(\d+(?:\.\d+)?)\s*%\s*[\n\s]*remaining/i);
                if (fiveMatch) {
                    fiveHourRemaining = parseFloat(fiveMatch[1]);
                }

                var weekMatch = text.match(/weekly\s*usage\s*limit\s*[\n\s]*(\d+(?:\.\d+)?)\s*%\s*[\n\s]*remaining/i);
                if (weekMatch) {
                    weeklyRemaining = parseFloat(weekMatch[1]);
                }

                return JSON.stringify({
                    fiveHourRemaining: fiveHourRemaining,
                    weeklyRemaining: weeklyRemaining,
                    text: text.substring(0, 1000)
                });
            } catch(e) {
                return JSON.stringify({error: e.toString()});
            }
        })()
    """.trimIndent()
}
