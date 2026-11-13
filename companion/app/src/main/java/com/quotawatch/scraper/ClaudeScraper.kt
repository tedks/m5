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
            // Lines of the row itself are never a model name (a layout that drops the name line
            // must not turn "Resets Saturday 11 AM" into a row).
            if (Regex("^(?:resets|up to)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean)) return null
            return clean.take(MODEL_LABEL_MAX).trimEnd() + " wk"
        }

        /**
         * Per-model rows from the (page label, used percent) candidates of both sources, JSON
         * first, as name -> (JSON value, text value). Fails closed on collisions: a wrist name
         * produced by two different labels (e.g. "Claude Opus 4.5" and "Claude Opus 4.6" both
         * truncate to "Claude Opus wk"), or equal to an aggregate row's name, is omitted entirely,
         * since the wrist could not say whose number it is. A label repeated verbatim keeps its
         * first value. The row cap is applied in [parse], across both sources together.
         */
        internal fun modelRows(
            api: List<Pair<String, Double>>,
            dom: List<Pair<String, Double>>
        ): LinkedHashMap<String, Pair<Double?, Double?>> {
            // Collisions are judged over the labels of BOTH sources: the JSON naming "Claude Opus
            // 4.5" and the text naming "Claude Opus 4.6" is just as ambiguous as either alone.
            // Names and labels compare case-insensitively (innerText applies CSS text-transform,
            // so the panel may render "FABLE" for the JSON's "Fable"); the first spelling seen,
            // JSON first, is the one shown.
            val shown = LinkedHashMap<String, String>()
            val labels = HashMap<String, MutableSet<String>>()
            val apiFirst = HashMap<String, Double>()
            val domFirst = HashMap<String, Double>()
            for ((cands, first) in listOf(api to apiFirst, dom to domFirst)) {
                for ((label, v) in cands) {
                    val name = modelRowName(label) ?: continue
                    val key = name.lowercase()
                    shown.putIfAbsent(key, name)
                    labels.getOrPut(key) { HashSet() }.add(label.trim().replace(Regex("\\s+"), " ").lowercase())
                    first.putIfAbsent(key, v)
                }
            }
            val reserved = setOf(SESSION_NAME.lowercase(), WEEKLY_NAME.lowercase())
            val out = LinkedHashMap<String, Pair<Double?, Double?>>()
            for ((key, name) in shown) {
                if (key in reserved) continue
                if (labels[key]!!.size > 1) continue
                out[name] = apiFirst[key] to domFirst[key]
            }
            return out
        }

        /** One source's values: the aggregate rows by name, plus raw per-model (label, value). */
        internal data class Extract(
            val values: LinkedHashMap<String, Double>,
            val models: List<Pair<String, Double>>
        )

        // Label-anchored patterns over the usage panel text. Each row is line-structured: its
        // label line, then only "decoration" lines (a "Resets ..." line, or a line of icon glyphs
        // with no letters or digits), then the "N% used" line. Any other line in between (another
        // row's label, a model name, an unknown row) means no match, so a row whose value has not
        // rendered can never borrow the next row's number. There is no "first percentage
        // anywhere" fallback, which once reported sidebar junk as quota (bd m5-39u/m5-iia).
        private val NUM = """(\d+(?:\.\d+)?)\s*%\s*used"""
        private const val DECOR = """(?:[ \t]*(?:resets[^\n%]{0,60}|[^\p{L}\p{N}\n%]{0,8})[ \t]*\n){0,3}"""
        // A label line, its decoration lines and its value line; row() also anchors it to a line start.
        private fun body(label: String) = """[ \t]*$label[ \t]*\n$DECOR[ \t]*$NUM"""
        private fun row(label: String) = """(?:^|\n)${body(label)}"""
        // July 2026 layout: "Current session / Resets in N hr / 7% used".
        private val DOM_SESSION = Regex(row("current session"), RegexOption.IGNORE_CASE)
        // July 2026 layout: "Weekly limits / All models / Resets ... / 15% used".
        private val DOM_WEEKLY_OLD = Regex("""(?:^|\n)[ \t]*weekly limits[ \t]*\n$DECOR${body("all models")}""", RegexOption.IGNORE_CASE)
        // November 2026 layout: a line that is exactly "This week", then "Resets ...", then
        // "60% used". ("This week's usage by product" further down is not that line.)
        private val DOM_WEEKLY = Regex(row("this week"), RegexOption.IGNORE_CASE)
        // November 2026 per-model row: "<model>" line, "Up to N% of your weekly limit" line,
        // decoration lines, then "M% used".
        private val DOM_MODEL = Regex(
            """(?:^|\n)([^\n]{1,40})\n[ \t]*up to \d+(?:\.\d+)?\s*% of your weekly limit[ \t]*\n$DECOR[ \t]*$NUM""",
            RegexOption.IGNORE_CASE
        )

        data class Parsed(val results: List<QuotaResult>, val warnings: List<String>)

        /** [parse] without the warnings. */
        fun parseUsage(data: String): List<QuotaResult> = parse(data).results

        private fun pctOrNull(s: String?): Double? = s?.toDoubleOrNull()?.takeIf { it in 0.0..100.0 }

        /**
         * The usage panel text's values: aggregate rows (name -> used percent) and raw per-model
         * (label, used percent) candidates, which [modelRows] turns into rows.
         */
        internal fun domValues(text: String): Extract {
            val out = LinkedHashMap<String, Double>()
            pctOrNull(DOM_SESSION.find(text)?.groupValues?.get(1))?.let { out[SESSION_NAME] = it }
            val weekly = DOM_WEEKLY_OLD.find(text) ?: DOM_WEEKLY.find(text)
            pctOrNull(weekly?.groupValues?.get(1))?.let { out[WEEKLY_NAME] = it }
            val candidates = DOM_MODEL.findAll(text).mapNotNull { m ->
                pctOrNull(m.groupValues[2])?.let { m.groupValues[1] to it }
            }.toList()
            return Extract(out, candidates)
        }

        /** Capture key for the page's own usage request (see ResponseCapture). */
        const val CAPTURE_KEY = "claudeUsage"

        // The usage panel fetches GET /api/organizations/<org>/usage itself (observed 2026-11-12,
        // see the capability survey on m5-scr). The org id is in the path only; the pattern does
        // not need to know it.
        val CAPTURE_SCRIPT = ResponseCapture.hookScript(mapOf(CAPTURE_KEY to "^/api/organizations/[^/]+/usage$"))

        private fun pct(v: Any?): Double? = (v as? Number)?.toDouble()?.takeIf { it in 0.0..100.0 }

        /**
         * The page's own usage JSON's values, as an [Extract] (the subset JS_EXTRACT
         * passes through: `limits`, `five_hour`, `seven_day`), in display order.
         *
         * `limits` is the list the panel renders from: kind "session" -> Claude 5h, "weekly_all"
         * -> Claude wk, "weekly_scoped" with a `scope.model.display_name` -> "<model> wk".
         * Unknown kinds, and scoped limits without a model (e.g. a surface scope), are ignored
         * rather than shown under a guessed name. The older `five_hour.utilization` /
         * `seven_day.utilization` fields fill in only what `limits` did not provide.
         */
        internal fun apiValues(api: JSONObject?): Extract {
            val out = LinkedHashMap<String, Double>()
            if (api == null) return Extract(out, emptyList())
            val limits = api.optJSONArray("limits")
            val scoped = mutableListOf<Pair<String, Double>>()
            if (limits != null) {
                for (i in 0 until limits.length()) {
                    val l = limits.optJSONObject(i) ?: continue
                    val v = pct(l.opt("percent")) ?: continue
                    when (l.optString("kind")) {
                        "session" -> if (SESSION_NAME !in out) out[SESSION_NAME] = v
                        "weekly_all" -> if (WEEKLY_NAME !in out) out[WEEKLY_NAME] = v
                        "weekly_scoped" -> {
                            // Only a limit scoped to a model alone; a model-and-surface limit
                            // (e.g. one model on one product) is a different quota.
                            val scope = l.optJSONObject("scope")
                            val surface = scope?.opt("surface")
                            // `as? String`: Android's org.json turns a JSON null into "null"
                            // through optString, which would become a row named "null wk".
                            val name = scope?.optJSONObject("model")?.opt("display_name") as? String
                            if ((surface == null || surface == JSONObject.NULL) && !name.isNullOrBlank()) scoped.add(name to v)
                        }
                        else -> {}
                    }
                }
            }
            if (SESSION_NAME !in out) pct(api.optJSONObject("five_hour")?.opt("utilization"))?.let { out[SESSION_NAME] = it }
            if (WEEKLY_NAME !in out) pct(api.optJSONObject("seven_day")?.opt("utilization"))?.let { out[WEEKLY_NAME] = it }
            return Extract(out, scoped)
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
            // Aggregate rows first, then model rows; model rows are capped across both sources
            // together (in page-JSON order, then text order).
            val models = modelRows(api.models, dom.models)
            val names = LinkedHashSet<String>().apply { addAll(api.values.keys); addAll(dom.values.keys) }
            models.keys.take(MAX_MODEL_ROWS).forEach { names.add(it) }
            for (name in names) {
                val a = api.values[name] ?: models[name]?.first
                val d = dom.values[name] ?: models[name]?.second
                if (a != null && d != null && kotlin.math.abs(a - d) > CROSS_CHECK_TOLERANCE) {
                    warnings.add("$name: api=$a dom=$d disagree; using api")
                }
                val (v, src) = if (a != null) a to "api" else d!! to "dom"
                results.add(QuotaResult.Success(SERVICE, Quota(name, v.toFloat(), 100f, "%"), source = src,
                    // A per-model row that is missing next round is gone (its model left the plan,
                    // or its name changed), not late: it is never carried over (see mergedWith).
                    retainable = name == SESSION_NAME || name == WEEKLY_NAME))
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
    // as a dialog over /new#settings/usage:
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
                    // Each limit is cut down to the fields parse() reads; ids, resets and any
                    // future fields stay in the page.
                    var lim = Array.isArray(cap.body.limits) ? cap.body.limits.map(function(l) {
                        var sc = l && l.scope, m = sc && sc.model;
                        return l && {kind: l.kind, percent: l.percent,
                            scope: sc ? {model: m ? {display_name: m.display_name} : null, surface: sc.surface == null ? null : true} : null};
                    }) : null;
                    var fh = cap.body.five_hour, sd = cap.body.seven_day;
                    api = {limits: lim, five_hour: fh ? {utilization: fh.utilization} : null, seven_day: sd ? {utilization: sd.utilization} : null};
                }
                return JSON.stringify({usageReady: usageReady, api: api, text: text.slice(0, 20000)});
            } catch(e) {
                return JSON.stringify({error: e.toString()});
            }
        })()
    """.trimIndent()
}
