package com.quotawatch.scraper

import android.content.Context
import android.util.Log
import com.quotawatch.api.Quota
import com.quotawatch.api.QuotaResult
import com.quotawatch.api.SessionOutcome
import com.quotawatch.api.SessionStore
import com.quotawatch.api.LoginStatus
import com.quotawatch.api.loginStatusOf
import com.quotawatch.api.sessionLooksValid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

class CodexScraper(contextProvider: () -> Context, private val sessionStore: SessionStore) {

    companion object {
        const val TAG = "CodexScraper"
        // The old /codex/cloud/settings/usage URL now client-redirects here (observed 2026-11-12).
        const val USAGE_URL = "https://chatgpt.com/settings/usage"
        const val ORIGIN = "https://chatgpt.com"
        const val SERVICE = "codex"

        // ~1s at UsageScraper.POLL_INTERVAL_MS (500ms) of extra polling after the first settle,
        // so a value that arrives a beat later (the page's usage JSON after the DOM, or the
        // optional 5h block, bd m5-u1d) is still picked up. Bounded; see UsageScraper.decidePoll.
        private const val GRACE_POLLS = 2

        /** Capture key for the page's own usage request (see ResponseCapture). */
        const val CAPTURE_KEY = "codexUsage"

        // The usage page fetches GET /backend-api/wham/usage itself (observed 2026-11-12, see the
        // capability survey on m5-scr). Its rate_limit.{primary,secondary}_window objects carry
        // used_percent and limit_window_seconds.
        val CAPTURE_SCRIPT = ResponseCapture.hookScript(mapOf(CAPTURE_KEY to "^/backend-api/wham/usage$"))

        // Window lengths we know how to name. Any other window length is ignored (never shown
        // under a guessed name).
        private const val WEEK_SECONDS = 7 * 24 * 3600
        private const val FIVE_HOUR_SECONDS = 5 * 3600
        const val WEEKLY_NAME = "Codex wk"
        const val FIVE_HOUR_NAME = "Codex 5h"

        // Disagreement (in percentage points) between the API value and the rendered text above
        // which a warning is logged. The page rounds what it renders, so allow 1 point.
        private const val CROSS_CHECK_TOLERANCE = 1.0

        private fun isSettled(raw: String): Boolean = try {
            val parsed = parse(raw)
            parsed.results.any { it is QuotaResult.Success && it.quota.name == WEEKLY_NAME }
        } catch (e: Exception) {
            false
        }

        // Label-anchored DOM patterns, applied in Kotlin to the page text. Today (2026-11-12):
        // "Weekly limit / Resets in 5d 23h / 85% left"; older: "Weekly usage limit ... N%
        // remaining". Each value must follow its OWN label within a bounded gap, and the gap may
        // not cross a '%' or the word "limit": otherwise a label with no value of its own (or a
        // value in another unit, like "30% used") would borrow the next quota's number.
        private const val GAP = """(?:(?!limit|%)[\s\S]){0,80}?"""
        private const val PCT_LEFT = """(\d+(?:\.\d+)?)\s*%\s*(?:left|remaining)"""
        private val DOM_WEEKLY = Regex(
            """(?:^|\n)[ \t]*weekly\s*(?:usage\s*)?limit$GAP$PCT_LEFT""",
            RegexOption.IGNORE_CASE
        )
        // Labels must start a line, as the real ones do; the reset blurb ("Use a reset to restore
        // your 5-hour limit, weekly limit, or both") names both mid-sentence. "5-hour", "5 hour",
        // "5hour" match; "25-hour" does not.
        private val DOM_FIVE_HOUR = Regex(
            """(?:^|\n)[ \t]*5[\s-]*hour\s*(?:usage\s*)?limit$GAP$PCT_LEFT""",
            RegexOption.IGNORE_CASE
        )

        data class Parsed(val results: List<QuotaResult>, val warnings: List<String>)

        /** [parse] without the warnings. */
        fun parseUsage(data: String): List<QuotaResult> = parse(data).results

        /** Used-percent per known window from the page's `rate_limit` object (wham/usage). */
        internal fun apiValues(rateLimit: JSONObject?): Map<String, Double> {
            val out = LinkedHashMap<String, Double>()
            if (rateLimit == null) return out
            for (key in listOf("primary_window", "secondary_window")) {
                val w = rateLimit.optJSONObject(key) ?: continue
                val name = when (w.optInt("limit_window_seconds", -1)) {
                    WEEK_SECONDS -> WEEKLY_NAME
                    FIVE_HOUR_SECONDS -> FIVE_HOUR_NAME
                    else -> null
                } ?: continue
                val used = w.optDouble("used_percent", Double.NaN)
                if (used in 0.0..100.0 && name !in out) out[name] = used
            }
            return out
        }

        /** Used-percent per quota from the rendered page text (the page shows percent LEFT). */
        internal fun domValues(text: String): Map<String, Double> {
            val out = LinkedHashMap<String, Double>()
            DOM_WEEKLY.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
                ?.takeIf { it in 0.0..100.0 }?.let { out[WEEKLY_NAME] = 100.0 - it }
            DOM_FIVE_HOUR.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
                ?.takeIf { it in 0.0..100.0 }?.let { out[FIVE_HOUR_NAME] = 100.0 - it }
            return out
        }

        /**
         * Map the extraction JSON (see JS_EXTRACT) to quota results. Pure, so it is unit-tested.
         *
         * Input: {"api": null | {"rate_limit": <the page's wham/usage rate_limit object>},
         *         "text": <page innerText>}.
         *
         * The page's own JSON wins per quota; the label-anchored text is the fallback for any
         * quota the JSON did not provide. If both exist and differ by more than
         * [CROSS_CHECK_TOLERANCE] the API value is kept and a warning returned. Values outside
         * 0..100 and windows of unknown length are absent, never guessed. Weekly missing from both
         * is an honest error; 5h missing is silently omitted (optional, bd m5-u1d).
         */
        fun parse(data: String): Parsed {
            val json = JSONObject(data)
            if (json.has("error")) {
                return Parsed(listOf(QuotaResult.Error(SERVICE, json.getString("error"))), emptyList())
            }
            val api = apiValues(json.optJSONObject("api")?.optJSONObject("rate_limit"))
            val dom = domValues(json.optString("text", ""))

            val results = mutableListOf<QuotaResult>()
            val warnings = mutableListOf<String>()
            for (name in listOf(WEEKLY_NAME, FIVE_HOUR_NAME)) {
                val a = api[name]
                val d = dom[name]
                if (a != null && d != null && kotlin.math.abs(a - d) > CROSS_CHECK_TOLERANCE) {
                    warnings.add("$name: api=$a dom=$d disagree; using api")
                }
                when {
                    a != null -> results.add(QuotaResult.Success(SERVICE, Quota(name, a.toFloat(), 100f, "%"), source = "api"))
                    d != null -> results.add(QuotaResult.Success(SERVICE, Quota(name, d.toFloat(), 100f, "%"), source = "dom"))
                    name == WEEKLY_NAME -> results.add(QuotaResult.Error(SERVICE, "Weekly usage limit not found on page"))
                }
            }
            return Parsed(results, warnings)
        }
    }

    private val scraper = UsageScraper(contextProvider)

    /**
     * Cheap prerequisite check used to gate whether a scrape is even attempted — cookie presence
     * only, deliberately not the stricter [loginStatus]. A stale EXPIRED outcome must not stop us
     * from re-attempting a scrape once a fresh cookie shows up (e.g. the user just re-logged in);
     * the scrape itself is what updates the outcome.
     */
    private fun hasSession(): Boolean = scraper.hasSession(ORIGIN)

    /** UI-facing status: cookie present AND the last completed scrape wasn't a login redirect. */
    fun loginStatus(): LoginStatus = loginStatusOf(hasSession(), sessionStore.current(SERVICE))

    /**
     * Reactive counterpart of [loginStatus] — re-evaluates cookie presence every time the
     * recorded outcome changes (a scrape recording a fresh OK or EXPIRED), rather than only
     * whenever a Composable happens to recompose for some unrelated reason. This is what closes
     * the "cold-start / never-recomposes" staleness gap [loginStatus] alone has.
     */
    fun loginStatusFlow(): Flow<LoginStatus> =
        sessionStore.outcomeFlow(SERVICE).map { outcome -> loginStatusOf(hasSession(), outcome) }

    /** Back-compat convenience for callers that only care about the binary case. */
    fun isLoggedIn(): Boolean = loginStatus() == LoginStatus.LOGGED_IN

    suspend fun fetchUsage(): List<QuotaResult> {
        if (!hasSession()) {
            return listOf(QuotaResult.Unavailable("codex", "Tap 'Log in' next to Codex in Settings"))
        }

        return try {
            val result = scraper.scrape(
                USAGE_URL, JS_EXTRACT, isSettled = ::isSettled, graceExtraPolls = GRACE_POLLS,
                captureScript = CAPTURE_SCRIPT
            )
            if (result.sessionExpired) {
                sessionStore.recordOutcome(SERVICE, SessionOutcome.EXPIRED)
                return listOf(QuotaResult.Unavailable("codex", "Session expired — tap 'Re-login' in Settings"))
            }
            if (result.data == null) {
                // Timeout without a login redirect — a network/render problem, not evidence about
                // the session either way, so the last recorded outcome is left untouched.
                return listOf(QuotaResult.Error("codex", "Page load timed out"))
            }

            val detailed = parse(result.data)
            detailed.warnings.forEach { Log.w(TAG, "Cross-check: $it") }
            val parsed = detailed.results
            parsed.forEach {
                if (it is QuotaResult.Success) Log.i(TAG, "Quota ${it.quota.name}=${it.quota.used}% source=${it.source}")
            }
            // A completed parse alone isn't proof the session is fine — a login wall/interstitial
            // that isn't caught by the URL-based sessionExpired check could render *some* page and
            // parse to an all-Error result. Codex has no "page actually rendered" signal like
            // Claude's usageReady, so only an actual Success counts as evidence. See
            // sessionLooksValid's doc.
            if (sessionLooksValid(parsed)) {
                sessionStore.recordOutcome(SERVICE, SessionOutcome.OK)
            }
            parsed
        } catch (e: Exception) {
            Log.e(TAG, "Scrape failed", e)
            listOf(QuotaResult.Error("codex", e.message ?: "Scrape failed"))
        }
    }

    // Hands Kotlin two independent inputs; parse() extracts, picks and cross-checks.
    //  api: the rate_limit object of the page's own GET /backend-api/wham/usage response,
    //       captured by CAPTURE_SCRIPT. Only rate_limit leaves the page: the rest of the body
    //       holds account ids and the email.
    //  text: the rendered page text, for the label-anchored fallback. Never logged.
    private val JS_EXTRACT = """
        (function() {
            try {
                var api = null;
                var cap = window.${ResponseCapture.GLOBAL} && window.${ResponseCapture.GLOBAL}['$CAPTURE_KEY'];
                if (cap && cap.body && typeof cap.body === 'object') {
                    var rl = cap.body.rate_limit;
                    api = {rate_limit: (rl && typeof rl === 'object') ? rl : null};
                }
                var text = ((document.body && document.body.innerText) || '').slice(0, 20000);
                return JSON.stringify({api: api, text: text});
            } catch(e) {
                return JSON.stringify({error: e.toString()});
            }
        })()
    """.trimIndent()
}
