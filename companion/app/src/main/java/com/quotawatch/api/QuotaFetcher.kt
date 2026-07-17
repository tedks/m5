package com.quotawatch.api

import android.content.Context
import android.util.Log
import com.quotawatch.scraper.ClaudeScraper
import com.quotawatch.scraper.CodexScraper
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class QuotaFetcher(contextProvider: () -> Context) {

    companion object {
        const val TAG = "QuotaFetcher"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Shared across both scrapers — one DataStore file backs both services' recorded outcomes.
    private val sessionStore = SessionStore(contextProvider)
    private val claudeScraper = ClaudeScraper(contextProvider, sessionStore)
    private val codexScraper = CodexScraper(contextProvider, sessionStore)

    suspend fun fetchAll(keys: ApiKeys): QuotaSnapshot {
        val results = mutableListOf<QuotaResult>()

        results.addAll(claudeScraper.fetchUsage())
        results.addAll(codexScraper.fetchUsage())

        if (keys.githubToken != null) {
            results.addAll(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                fetchGitHubActions(keys.githubToken)
            })
        } else {
            // Emit an explicit Unavailable so "github" is never silently absent from the
            // snapshot. The merge policy treats an absent service as deliberately gone and drops
            // any retained last-known-good; making the "no token" state a first-class result keeps
            // that decision honest and lets the UI show why GitHub has no numbers.
            results.add(QuotaResult.Unavailable("github", "No GitHub token — add one in Settings"))
        }

        return QuotaSnapshot(results)
    }

    fun isClaudeLoggedIn(): Boolean = claudeScraper.isLoggedIn()
    fun isCodexLoggedIn(): Boolean = codexScraper.isLoggedIn()
    fun claudeLoginStatus(): LoginStatus = claudeScraper.loginStatus()
    fun codexLoginStatus(): LoginStatus = codexScraper.loginStatus()
    fun claudeLoginStatusFlow(): Flow<LoginStatus> = claudeScraper.loginStatusFlow()
    fun codexLoginStatusFlow(): Flow<LoginStatus> = codexScraper.loginStatusFlow()

    /**
     * Clears a just-logged-in-again service's recorded outcome back to UNKNOWN (bd m5-7ph finding:
     * without this, tapping "Re-login" and getting a fresh cookie still left Settings showing
     * "Session expired" until the next scrape completed, up to 45s later). UNKNOWN + a fresh
     * cookie reads as LOGGED_IN immediately via loginStatusOf, and the caller is expected to
     * trigger a refresh right after this so the real outcome supersedes it soon after.
     */
    suspend fun resetSessionOutcome(service: String) {
        sessionStore.recordOutcome(service, SessionOutcome.UNKNOWN)
    }

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
