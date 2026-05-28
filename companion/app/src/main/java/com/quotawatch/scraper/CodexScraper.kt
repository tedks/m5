package com.quotawatch.scraper

import android.content.Context
import android.util.Log
import com.quotawatch.api.Quota
import com.quotawatch.api.QuotaResult
import org.json.JSONObject

class CodexScraper(context: Context) {

    companion object {
        const val TAG = "CodexScraper"
        const val USAGE_URL = "https://chatgpt.com/codex/cloud/settings/analytics#usage"
    }

    private val scraper = UsageScraper(context)

    fun isLoggedIn(): Boolean = scraper.hasSession("https://chatgpt.com")

    suspend fun fetchUsage(): QuotaResult {
        if (!isLoggedIn()) {
            return QuotaResult.Unavailable("Codex", "Tap 'Log in to Codex' in Settings")
        }

        return try {
            val raw = scraper.scrape(USAGE_URL, JS_EXTRACT)
            if (raw == null) {
                return QuotaResult.Error("Codex", "Page load timed out")
            }

            val jsonStr = raw.trim().removeSurrounding("\"").replace("\\\"", "\"")
                .replace("\\n", "\n").replace("\\\\", "\\")
            Log.d(TAG, "Parsed: $jsonStr")

            val json = JSONObject(jsonStr)

            if (json.has("error")) {
                return QuotaResult.Error("Codex", json.getString("error"))
            }

            val pct = json.optDouble("usagePct", -1.0)
            if (pct >= 0) {
                QuotaResult.Success(Quota("Codex", pct.toFloat(), 100f, "%"))
            } else {
                val text = json.optString("text", "")
                QuotaResult.Error("Codex", "Could not parse usage. Page text: ${text.take(200)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Scrape failed", e)
            QuotaResult.Error("Codex", e.message ?: "Scrape failed")
        }
    }

    private val JS_EXTRACT = """
        (function() {
            try {
                var text = document.body.innerText || '';
                var bars = document.querySelectorAll('[role="progressbar"]');
                var barData = [];
                bars.forEach(function(b) {
                    barData.push({
                        value: b.getAttribute('aria-valuenow'),
                        max: b.getAttribute('aria-valuemax'),
                        label: b.getAttribute('aria-label') || ''
                    });
                });
                var pctMatches = text.match(/(\d+(?:\.\d+)?)\s*%/g) || [];
                var usagePct = -1;
                for (var i = 0; i < barData.length; i++) {
                    var val = parseFloat(barData[i].value);
                    if (!isNaN(val) && usagePct < 0) {
                        usagePct = val;
                    }
                }
                if (usagePct < 0) {
                    var lines = text.split('\n');
                    for (var j = 0; j < lines.length; j++) {
                        var line = lines[j].toLowerCase();
                        var m = line.match(/(\d+(?:\.\d+)?)\s*%/);
                        if (m && (line.includes('usage') || line.includes('limit') || line.includes('used') || line.includes('quota'))) {
                            usagePct = parseFloat(m[1]);
                            break;
                        }
                    }
                    if (usagePct < 0 && pctMatches.length > 0) {
                        usagePct = parseFloat(pctMatches[0]);
                    }
                }
                return JSON.stringify({
                    usagePct: usagePct,
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
