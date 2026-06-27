package com.quotawatch.scraper

import android.content.Context
import android.util.Log
import com.quotawatch.api.Quota
import com.quotawatch.api.QuotaResult
import org.json.JSONObject

class ClaudeScraper(context: Context) {

    companion object {
        const val TAG = "ClaudeScraper"
        const val USAGE_URL = "https://claude.ai/settings/usage"
    }

    private val scraper = UsageScraper(context)

    fun isLoggedIn(): Boolean = scraper.hasSession("https://claude.ai")

    suspend fun fetchUsage(): List<QuotaResult> {
        if (!isLoggedIn()) {
            return listOf(QuotaResult.Unavailable("Claude", "Tap 'Log in' next to Claude Code in Settings"))
        }

        return try {
            val result = scraper.scrape(USAGE_URL, JS_EXTRACT)
            if (result.sessionExpired) {
                return listOf(QuotaResult.Unavailable("Claude", "Session expired — tap 'Re-login' in Settings"))
            }
            if (result.data == null) {
                return listOf(QuotaResult.Error("Claude", "Page load timed out"))
            }

            val jsonStr = result.data.trim().removeSurrounding("\"").replace("\\\"", "\"")
                .replace("\\n", "\n").replace("\\\\", "\\")
            Log.d(TAG, "Parsed: ${jsonStr.take(200)}")

            val json = JSONObject(jsonStr)

            if (json.has("error")) {
                return listOf(QuotaResult.Error("Claude", json.getString("error")))
            }

            val results = mutableListOf<QuotaResult>()

            val fiveHourPct = json.optDouble("fiveHourPct", -1.0)
            if (fiveHourPct >= 0) {
                results.add(QuotaResult.Success(Quota("Claude 5h", fiveHourPct.toFloat(), 100f, "%")))
            }

            val sevenDayPct = json.optDouble("sevenDayPct", -1.0)
            if (sevenDayPct >= 0) {
                results.add(QuotaResult.Success(Quota("Claude 7d", sevenDayPct.toFloat(), 100f, "%")))
            }

            if (results.isEmpty()) {
                val text = json.optString("text", "")
                results.add(QuotaResult.Error("Claude", "Could not parse usage. Page text: ${text.take(200)}"))
            }

            results
        } catch (e: Exception) {
            Log.e(TAG, "Scrape failed", e)
            listOf(QuotaResult.Error("Claude", e.message ?: "Scrape failed"))
        }
    }

    private val JS_EXTRACT = """
        (function() {
            try {
                var text = document.body.innerText || '';

                // Look for progress bars with aria attributes
                var bars = document.querySelectorAll('[role="progressbar"]');
                var barData = [];
                bars.forEach(function(b) {
                    barData.push({
                        value: b.getAttribute('aria-valuenow'),
                        max: b.getAttribute('aria-valuemax'),
                        label: b.getAttribute('aria-label') || ''
                    });
                });

                // Look for percentage patterns in text
                // Patterns like "17%" or "17% used" or "17% of" or "Usage: 17%"
                var pctMatches = text.match(/(\d+(?:\.\d+)?)\s*%/g) || [];

                // Try to find 5-hour specific data
                var fiveHourPct = -1;
                var sevenDayPct = -1;

                // Check aria bars first (most reliable)
                for (var i = 0; i < barData.length; i++) {
                    var val = parseFloat(barData[i].value);
                    if (!isNaN(val)) {
                        var label = (barData[i].label || '').toLowerCase();
                        if (label.includes('5') || label.includes('five') || label.includes('hour')) {
                            fiveHourPct = val;
                        } else if (label.includes('7') || label.includes('seven') || label.includes('day') || label.includes('week')) {
                            sevenDayPct = val;
                        } else if (fiveHourPct < 0) {
                            fiveHourPct = val;
                        } else if (sevenDayPct < 0) {
                            sevenDayPct = val;
                        }
                    }
                }

                // Fall back to text parsing if no bars found
                if (fiveHourPct < 0 && pctMatches.length > 0) {
                    // Look for "5-hour" near a percentage
                    var lines = text.split('\\n');
                    for (var j = 0; j < lines.length; j++) {
                        var line = lines[j].toLowerCase();
                        var m = line.match(/(\d+(?:\.\d+)?)\s*%/);
                        if (m) {
                            var pval = parseFloat(m[1]);
                            if (line.includes('5') && line.includes('hour') && fiveHourPct < 0) {
                                fiveHourPct = pval;
                            } else if ((line.includes('7') || line.includes('week') || line.includes('daily')) && sevenDayPct < 0) {
                                sevenDayPct = pval;
                            }
                        }
                    }
                    // If still nothing, take first percentage as 5h
                    if (fiveHourPct < 0 && pctMatches.length > 0) {
                        fiveHourPct = parseFloat(pctMatches[0]);
                    }
                }

                return JSON.stringify({
                    fiveHourPct: fiveHourPct,
                    sevenDayPct: sevenDayPct,
                    bars: barData,
                    percentages: pctMatches,
                    text: text.substring(0, 1000)
                });
            } catch(e) {
                return JSON.stringify({error: e.toString()});
            }
        })()
    """.trimIndent()
}
