package com.quotawatch.api

import android.content.Context
import android.util.Log
import com.quotawatch.scraper.ClaudeScraper
import com.quotawatch.scraper.CodexScraper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class QuotaFetcher(context: Context) {

    companion object {
        const val TAG = "QuotaFetcher"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val claudeScraper = ClaudeScraper(context)
    private val codexScraper = CodexScraper(context)

    suspend fun fetchAll(keys: ApiKeys): QuotaSnapshot {
        val results = mutableListOf<QuotaResult>()

        results.addAll(claudeScraper.fetchUsage())
        results.addAll(codexScraper.fetchUsage())

        if (keys.githubToken != null) {
            results.addAll(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                fetchGitHubActions(keys.githubToken)
            })
        }

        return QuotaSnapshot(results)
    }

    fun isClaudeLoggedIn(): Boolean = claudeScraper.isLoggedIn()
    fun isCodexLoggedIn(): Boolean = codexScraper.isLoggedIn()

    private fun fetchGitHubActions(token: String): List<QuotaResult> {
        try {
            val username = fetchGitHubUsername(token)

            val request = Request.Builder()
                .url("https://api.github.com/users/$username/settings/billing/usage/summary")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")
            Log.d(TAG, "GitHub response: ${response.code} ${body.take(500)}")

            if (!response.isSuccessful) {
                val msg = try {
                    JSONObject(body).getString("message")
                } catch (_: Exception) { "HTTP ${response.code}" }
                return listOf(QuotaResult.Error("github", "$msg (needs classic token with 'user' scope)"))
            }

            val json = JSONObject(body)
            val items = json.getJSONArray("usageItems")

            var actionMinutes = 0.0
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                if (item.getString("product") == "Actions" && item.getString("unitType") == "minutes") {
                    actionMinutes += item.getDouble("grossQuantity")
                }
            }

            return listOf(QuotaResult.Success("github", Quota("Actions", actionMinutes.toFloat(), 3000f, "min")))
        } catch (e: Exception) {
            Log.e(TAG, "GitHub fetch failed", e)
            return listOf(QuotaResult.Error("github", e.message ?: "Unknown error"))
        }
    }

    private fun fetchGitHubUsername(token: String): String {
        val request = Request.Builder()
            .url("https://api.github.com/user")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty response")
        if (!response.isSuccessful) throw Exception(
            when (response.code) {
                401 -> "Token invalid or expired — update in Settings"
                else -> try { JSONObject(body).getString("message") } catch (_: Exception) { "HTTP ${response.code}" }
            }
        )
        return JSONObject(body).getString("login")
    }
}

data class ApiKeys(
    val githubToken: String? = null
)
