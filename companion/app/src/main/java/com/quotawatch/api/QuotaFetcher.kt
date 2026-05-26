package com.quotawatch.api

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class QuotaFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        const val TAG = "QuotaFetcher"
    }

    private val claudeOAuth = ClaudeOAuth(client)

    fun fetchAll(keys: ApiKeys): QuotaSnapshot {
        val results = mutableListOf<QuotaResult>()

        // Claude Code — OAuth usage endpoint
        if (keys.claudeOAuthToken != null) {
            results.add(claudeOAuth.fetchUsage(keys.claudeOAuthToken))
        } else {
            results.add(QuotaResult.Unavailable(
                "Claude",
                "Paste OAuth token from ~/.claude/.credentials.json (accessToken field)"
            ))
        }

        // Codex / ChatGPT Pro — no programmatic access for individual Pro subscribers
        results.add(QuotaResult.Unavailable(
            "Codex",
            "ChatGPT Pro usage not exposed via any API. Enterprise-only."
        ))

        // GitHub — Actions + Copilot from billing summary
        if (keys.githubToken != null) {
            results.addAll(fetchGitHub(keys.githubToken))
        }

        return QuotaSnapshot(results)
    }

    private fun fetchGitHub(token: String): List<QuotaResult> {
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
                return listOf(QuotaResult.Error("Actions", "$msg (needs classic token with 'user' scope)"))
            }

            val json = JSONObject(body)
            val items = json.getJSONArray("usageItems")

            var actionMinutes = 0.0
            var copilotRequests = 0.0

            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val product = item.getString("product")
                val sku = item.getString("sku")
                val unitType = item.getString("unitType")
                val quantity = item.getDouble("grossQuantity")

                when {
                    product == "Actions" && unitType == "minutes" -> actionMinutes += quantity
                    product == "Copilot" && sku.contains("Premium") -> copilotRequests += quantity
                }
            }

            val results = mutableListOf<QuotaResult>()
            results.add(QuotaResult.Success(Quota("Actions", actionMinutes.toFloat(), 3000f, "min")))
            if (copilotRequests > 0) {
                results.add(QuotaResult.Success(Quota("Copilot", copilotRequests.toFloat(), 0f, "req")))
            }
            return results

        } catch (e: Exception) {
            Log.e(TAG, "GitHub fetch failed", e)
            return listOf(QuotaResult.Error("GitHub", e.message ?: "Unknown error"))
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
        if (!response.isSuccessful) throw Exception("User lookup failed: ${response.code}")
        return JSONObject(body).getString("login")
    }
}

data class ApiKeys(
    val claudeOAuthToken: String? = null,
    val openaiAdminKey: String? = null,  // kept for future use
    val githubToken: String? = null
)
