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

class ClaudeScraper(contextProvider: () -> Context, private val sessionStore: SessionStore) {

    companion object {
        const val TAG = "ClaudeScraper"
        const val USAGE_URL = "https://claude.ai/settings/usage"
        const val ORIGIN = "https://claude.ai"
        const val SERVICE = "claude"

        const val SESSION_NAME = "Claude 5h"
        const val WEEKLY_NAME = "Claude wk"

        // Firmware parses a row name with sscanf "%15[^:]" — at most 15 bytes, no ':'. A
        // per-model row is "<model> wk", so the model part gets at most 12 characters.
        private const val MODEL_LABEL_MAX = 12
        private const val MAX_MODEL_ROWS = 4

        // ~1s of extra polling after the first settle so a source that lands a beat later (the
        // JSON vs. the rendered panel) is still included. Bounded; see UsageScraper.decidePoll.
        private const val GRACE_POLLS = 2

        // Disagreement (percentage points) between api and dom above which a warning is logged.
        private const val CROSS_CHECK_TOLERANCE = 1.0

        // Settled once any weekly or session value is present (from either source); the poll
        // loop's stable-twice check and grace window wait out the rest of the panel rendering.
        private fun isSettled(raw: String): Boolean = try {
            parse(raw).results.any { it is QuotaResult.Success }
        } catch (e: Exception) {
            false
        }

        /**
         * Short wrist name for a per-model weekly row, or null if [label] doesn't look like a
         * model name. Keeps letters, digits, space, '.', '-'; collapses whitespace; truncates.
         */
        fun modelRowName(label: String): String? {
            val clean = label.trim().replace(Regex("\\s+"), " ")
            if (clean.isEmpty() || !clean.matches(Regex("[A-Za-z][A-Za-z0-9 .\\-]*"))) return null
            return clean.take(MODEL_LABEL_MAX).trimEnd() + " wk"
        }

        // Label-anchored patterns over the usage panel text. Every value must follow its own
        // label within a bounded gap; there is no "first percentage anywhere" fallback, which
        // once reported sidebar junk as quota (bd m5-39u/m5-iia).
        private val NUM = """(\d+(?:\.\d+)?)\s*%\s*used"""
        // July 2026 layout: "Current session · Resets in N hr · 7% used".
        private val DOM_SESSION = Regex("""current session[\s\S]{0,160}?$NUM""", RegexOption.IGNORE_CASE)
        // July 2026 layout: "Weekly limits / All models · Resets ... · 15% used".
        private val DOM_WEEKLY_OLD = Regex("""weekly limits[\s\S]{0,40}?all models[\s\S]{0,160}?$NUM""", RegexOption.IGNORE_CASE)
        // November 2026 layout: a line that is exactly "This week", then "Resets ...", then
        // "60% used". ("This week's usage by product" further down is not that line.)
        private val DOM_WEEKLY = Regex("""(?:^|\n)[ \t]*this week[ \t]*\n[\s\S]{0,120}?$NUM""", RegexOption.IGNORE_CASE)
        // November 2026 per-model row: "<model>" line, "Up to N% of your weekly limit" line,
        // up to two more lines (the reset, an icon glyph), then "M% used".
        private val DOM_MODEL = Regex(
            """(?:^|\n)([^\n]{1,40})\n[ \t]*up to \d+(?:\.\d+)?\s*% of your weekly limit[ \t]*\n(?:[^\n]{0,80}\n){0,2}[ \t]*$NUM""",
            RegexOption.IGNORE_CASE
        )

        data class Parsed(val results: List<QuotaResult>, val warnings: List<String>)

        /** [parse] without the warnings. */
        fun parseUsage(data: String): List<QuotaResult> = parse(data).results

        private fun pctOrNull(s: String?): Double? = s?.toDoubleOrNull()?.takeIf { it in 0.0..100.0 }

        /** Quota name -> used percent from the usage panel text, in display order. */
        internal fun domValues(text: String): LinkedHashMap<String, Double> {
            val out = LinkedHashMap<String, Double>()
            pctOrNull(DOM_SESSION.find(text)?.groupValues?.get(1))?.let { out[SESSION_NAME] = it }
            val weekly = DOM_WEEKLY_OLD.find(text) ?: DOM_WEEKLY.find(text)
            pctOrNull(weekly?.groupValues?.get(1))?.let { out[WEEKLY_NAME] = it }
            var models = 0
            for (m in DOM_MODEL.findAll(text)) {
                if (models >= MAX_MODEL_ROWS) break
                val name = modelRowName(m.groupValues[1]) ?: continue
                val v = pctOrNull(m.groupValues[2]) ?: continue
                if (name in out) continue
                out[name] = v
                models++
            }
            return out
        }

        /** Capture key for the page's own usage request (see ResponseCapture). */
        const val CAPTURE_KEY = "claudeUsage"

        // The usage panel fetches GET /api/organizations/<org>/usage itself (observed 2026-11-12,
        // see the capability survey on m5-scr). The org id is in the path only; the pattern does
        // not need to know it.
        val CAPTURE_SCRIPT = ResponseCapture.hookScript(mapOf(CAPTURE_KEY to "^/api/organizations/[^/]+/usage$"))

        private fun pct(v: Any?): Double? = (v as? Number)?.toDouble()?.takeIf { it in 0.0..100.0 }

        /**
         * Quota name -> used percent from the page's own usage JSON (the subset JS_EXTRACT
         * passes through: `limits`, `five_hour`, `seven_day`), in display order.
         *
         * `limits` is the list the panel renders from: kind "session" -> Claude 5h, "weekly_all"
         * -> Claude wk, "weekly_scoped" with a `scope.model.display_name` -> "<model> wk".
         * Unknown kinds, and scoped limits without a model (e.g. a surface scope), are ignored
         * rather than shown under a guessed name. The older `five_hour.utilization` /
         * `seven_day.utilization` fields fill in only what `limits` did not provide.
         */
        internal fun apiValues(api: JSONObject?): LinkedHashMap<String, Double> {
            val out = LinkedHashMap<String, Double>()
            if (api == null) return out
            val limits = api.optJSONArray("limits")
            var models = 0
            if (limits != null) {
                for (i in 0 until limits.length()) {
                    val l = limits.optJSONObject(i) ?: continue
                    val v = pct(l.opt("percent")) ?: continue
                    val name = when (l.optString("kind")) {
                        "session" -> SESSION_NAME
                        "weekly_all" -> WEEKLY_NAME
                        "weekly_scoped" -> {
                            if (models >= MAX_MODEL_ROWS) null
                            else l.optJSONObject("scope")?.optJSONObject("model")
                                ?.optString("display_name", "")?.let { modelRowName(it) }
                        }
                        else -> null
                    } ?: continue
                    if (name in out) continue
                    if (name != SESSION_NAME && name != WEEKLY_NAME) models++
                    out[name] = v
                }
            }
            if (SESSION_NAME !in out) pct(api.optJSONObject("five_hour")?.opt("utilization"))?.let { out[SESSION_NAME] = it }
            if (WEEKLY_NAME !in out) pct(api.optJSONObject("seven_day")?.opt("utilization"))?.let { out[WEEKLY_NAME] = it }
            return out
        }

        /**
         * Map the extraction JSON (see JS_EXTRACT) to quota results. Pure, so it is unit-tested.
         *
         * Input: {"usageReady": bool, "api": null | {...}, "text": <usage panel text>}.
         * Per quota the api value wins and the label-anchored text is the fallback; a
         * disagreement above [CROSS_CHECK_TOLERANCE] keeps api and returns a warning. Values
         * outside 0..100 are absent. Nothing found is an honest error, worded by whether the
         * panel rendered.
         */
        fun parse(data: String): Parsed {
            val json = JSONObject(data)
            if (json.has("error")) {
                return Parsed(listOf(QuotaResult.Error(SERVICE, json.getString("error"))), emptyList())
            }
            val api = apiValues(json.optJSONObject("api"))
            val dom = domValues(json.optString("text", ""))

            val results = mutableListOf<QuotaResult>()
            val warnings = mutableListOf<String>()
            val names = LinkedHashSet<String>().apply { addAll(api.keys); addAll(dom.keys) }
            for (name in names) {
                val a = api[name]
                val d = dom[name]
                if (a != null && d != null && kotlin.math.abs(a - d) > CROSS_CHECK_TOLERANCE) {
                    warnings.add("$name: api=$a dom=$d disagree; using api")
                }
                val (v, src) = if (a != null) a to "api" else d!! to "dom"
                results.add(QuotaResult.Success(SERVICE, Quota(name, v.toFloat(), 100f, "%"), source = src))
            }

            if (results.isEmpty()) {
                val msg = if (json.optBoolean("usageReady", false)) {
                    "Usage values not found on page"
                } else {
                    "Usage panel did not render"
                }
                results.add(QuotaResult.Error(SERVICE, msg))
            }
            return Parsed(results, warnings)
        }

        /** Pulls the raw `usageReady` signal straight from the extraction JSON, defensively. */
        private fun extractUsageReady(data: String): Boolean = try {
            JSONObject(data).optBoolean("usageReady", false)
        } catch (e: Exception) {
            false
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
            return listOf(QuotaResult.Unavailable("claude", "Tap 'Log in' next to Claude Code in Settings"))
        }

        return try {
            val result = scraper.scrape(
                USAGE_URL, JS_EXTRACT, isSettled = ::isSettled, graceExtraPolls = GRACE_POLLS,
                captureScript = CAPTURE_SCRIPT
            )
            if (result.sessionExpired) {
                sessionStore.recordOutcome(SERVICE, SessionOutcome.EXPIRED)
                return listOf(QuotaResult.Unavailable("claude", "Session expired — tap 'Re-login' in Settings"))
            }
            if (result.data == null) {
                // Timeout without a login redirect — a network/render problem, not evidence about
                // the session either way, so the last recorded outcome is left untouched.
                return listOf(QuotaResult.Error("claude", "Page load timed out"))
            }

            val detailed = parse(result.data)
            detailed.warnings.forEach { Log.w(TAG, "Cross-check: $it") }
            val parsed = detailed.results
            parsed.forEach {
                if (it is QuotaResult.Success) Log.i(TAG, "Quota ${it.quota.name}=${it.quota.used}% source=${it.source}")
            }
            // A completed parse alone isn't proof the session is fine — a login wall/interstitial
            // that isn't caught by the URL-based sessionExpired check could render *some* page and
            // parse to an all-Error result. Only record OK when there's positive evidence: an
            // actual quota value, or (short of that) usageReady — the real usage panel rendered,
            // just without extractable numbers. See sessionLooksValid's doc.
            if (sessionLooksValid(parsed, pageReady = extractUsageReady(result.data))) {
                sessionStore.recordOutcome(SERVICE, SessionOutcome.OK)
            }
            parsed
        } catch (e: Exception) {
            Log.e(TAG, "Scrape failed", e)
            listOf(QuotaResult.Error("claude", e.message ?: "Scrape failed"))
        }
    }

    // Hands Kotlin the usage panel's text (parse() does the label-anchored extraction) and the
    // `limits` / `five_hour` / `seven_day` fields of the page's own usage JSON (CAPTURE_SCRIPT). Layout observed 2026-11-12
    // (Ultra plan), as a dialog over /new#settings/usage:
    //   Your usage / <plan> / <pace note>
    //   This week / Resets Saturday 11:00 AM / 60% used                               <- weekly
    //   Fable / Up to 50% of your weekly limit / Resets Saturday 11:00 AM / 64% used  <- model
    //   Limit resets ... / Usage credits $0 ... / This week's usage by product ...
    // The text is taken from the usage dialog only when one exists (it never includes the chat
    // list); body text is the fallback for a future non-dialog settings page. Never logged.
    private val JS_EXTRACT = """
        (function() {
            try {
                var READY = /plan usage limits|current session|weekly limits|your usage/i;
                var text = '';
                var dialogs = document.querySelectorAll('[role="dialog"],[aria-modal="true"]');
                for (var i = 0; i < dialogs.length; i++) {
                    var dt = dialogs[i].innerText || '';
                    if (READY.test(dt)) { text = dt; break; }
                }
                var usageReady = !!text;
                if (!text) text = (document.body && document.body.innerText) || '';
                // Only these three fields of the captured body leave the page.
                var api = null;
                var cap = window.${ResponseCapture.GLOBAL} && window.${ResponseCapture.GLOBAL}['$CAPTURE_KEY'];
                if (cap && cap.body && typeof cap.body === 'object') {
                    api = {limits: cap.body.limits || null, five_hour: cap.body.five_hour || null, seven_day: cap.body.seven_day || null};
                }
                return JSON.stringify({usageReady: usageReady, api: api, text: text.slice(0, 20000)});
            } catch(e) {
                return JSON.stringify({error: e.toString()});
            }
        })()
    """.trimIndent()
}
