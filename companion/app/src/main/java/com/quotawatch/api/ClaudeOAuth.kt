package com.quotawatch.api

import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Handles Claude Code OAuth usage polling.
 *
 * Two paths to get a token:
 * 1. User pastes token directly (from ~/.claude/.credentials.json on their machine)
 * 2. WebView login flow captures the token (future enhancement)
 *
 * Once we have a token, we poll GET /api/oauth/usage every 3 minutes.
 */
class ClaudeOAuth(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        const val TAG = "ClaudeOAuth"
        const val USAGE_URL = "https://api.anthropic.com/api/oauth/usage"
        const val REFRESH_URL = "https://console.anthropic.com/v1/oauth/token"
    }

    data class UsageData(
        val fiveHourPct: Float,
        val fiveHourResetsAt: String?,
        val sevenDayPct: Float,
        val sevenDayResetsAt: String?
    )

    fun fetchUsage(accessToken: String): QuotaResult {
        return try {
            val request = Request.Builder()
                .url(USAGE_URL)
                .header("Authorization", "Bearer $accessToken")
                .header("anthropic-beta", "oauth-2025-04-20")
                .header("Content-Type", "application/json")
                .header("User-Agent", "claude-code/2.1.80")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")

            Log.d(TAG, "Usage response: ${response.code} $body")

            if (response.code == 429) {
                return QuotaResult.Error("Claude", "Rate limited — try again in 3 min")
            }

            if (!response.isSuccessful) {
                return QuotaResult.Error("Claude", "HTTP ${response.code}: ${body.take(100)}")
            }

            val json = JSONObject(body)
            val fiveHour = json.optJSONObject("five_hour")
            val pct = fiveHour?.optDouble("utilization", 0.0)?.toFloat() ?: 0f

            QuotaResult.Success(Quota("Claude", pct, 100f, "%"))
        } catch (e: Exception) {
            Log.e(TAG, "Claude usage fetch failed", e)
            QuotaResult.Error("Claude", e.message ?: "Unknown error")
        }
    }
}
