package com.quotawatch.api

data class Quota(
    val name: String,
    val used: Float,
    val limit: Float,
    val unit: String
) {
    val percent: Float get() = if (limit > 0) (used / limit).coerceIn(0f, 1f) else 0f
    fun toBleString(): String = "$name:$used:$limit:$unit"
}

sealed class QuotaResult {
    /** Stable lowercase id for the backing service, e.g. "claude", "codex", "github". */
    abstract val service: String

    data class Success(
        override val service: String,
        val quota: Quota,
        val fetchedAt: Long = System.currentTimeMillis()
    ) : QuotaResult()

    data class Error(override val service: String, val message: String) : QuotaResult()
    data class Unavailable(override val service: String, val reason: String) : QuotaResult()
}

/** Human-facing label for a service id; falls back to capitalizing unknown ids. */
fun serviceDisplayName(service: String): String = when (service) {
    "claude" -> "Claude"
    "codex" -> "Codex"
    "github" -> "GitHub"
    else -> service.replaceFirstChar { it.uppercase() }
}

/**
 * UI-facing login status for a scraped service (bd m5-7ph). Three states instead of a plain
 * boolean so Settings can show "Session expired" distinctly from "never logged in" — the two look
 * identical if collapsed to a boolean, but need different user actions (log in vs. re-authenticate
 * a session that's gone stale).
 */
enum class LoginStatus { LOGGED_IN, SESSION_EXPIRED, NOT_LOGGED_IN }

/**
 * Pure combination of cheap cookie presence ([hasSessionCookie], from
 * [com.quotawatch.scraper.UsageScraper.hasSession]) and the last recorded scrape outcome
 * ([lastOutcome], from [SessionStore]). No cookie is always NOT_LOGGED_IN regardless of a stale
 * recorded outcome — a fresh logout wipes cookies immediately, so cookie absence is authoritative.
 * With a cookie present, an EXPIRED outcome means the last scrape hit a login-page redirect; any
 * other outcome (OK, or UNKNOWN before a scrape has ever run) reads as logged in.
 */
fun loginStatusOf(hasSessionCookie: Boolean, lastOutcome: SessionOutcome): LoginStatus = when {
    !hasSessionCookie -> LoginStatus.NOT_LOGGED_IN
    lastOutcome == SessionOutcome.EXPIRED -> LoginStatus.SESSION_EXPIRED
    else -> LoginStatus.LOGGED_IN
}

/**
 * Whether a completed scrape's results are positive evidence the session itself is still valid,
 * for recording [SessionOutcome.OK] (council review finding on bd m5-7ph). Reaching a completed
 * parse with no exception is NOT enough on its own: a login wall or interstitial that lands on a
 * URL pattern the scraper doesn't recognize as a login redirect (so `sessionExpired` never fires)
 * can still render *some* page and parse to an all-Error result — recording OK for that would flip
 * a genuinely EXPIRED outcome back to "logged in" on the strength of a page that was never the
 * real usage panel.
 *
 * [results] containing a [QuotaResult.Success] is always sufficient — a real quota value could
 * only have come from the real page. Short of that, [pageReady] is an extra service-supplied
 * signal for "the real usage panel actually rendered, just without extractable numbers" (Claude's
 * `usageReady`, from its labeled "Plan usage limits"/"Current session"/"Weekly limits" text match —
 * see ClaudeScraper's JS_EXTRACT). Codex has no equivalent signal today, so it always passes
 * `pageReady = false` and relies on Success alone.
 */
fun sessionLooksValid(results: List<QuotaResult>, pageReady: Boolean = false): Boolean =
    results.any { it is QuotaResult.Success } || pageReady

/**
 * A retained previous [QuotaResult.Success] is only worth showing for this long before it's
 * dropped outright — past this point, presenting hour-old numbers as if they were current is
 * worse than just showing the error with no stale figure attached.
 */
internal const val MAX_STALE_MS = 60 * 60 * 1000L // 60 minutes

data class QuotaSnapshot(
    val results: List<QuotaResult>,
    val timestamp: Long = System.currentTimeMillis()
) {
    val successes: List<QuotaResult.Success> get() = results.filterIsInstance<QuotaResult.Success>()
    val quotas: List<Quota> get() = successes.map { it.quota }
    val errors: List<QuotaResult.Error> get() = results.filterIsInstance<QuotaResult.Error>()
    val unavailable: List<QuotaResult.Unavailable> get() = results.filterIsInstance<QuotaResult.Unavailable>()

    fun toBlePayload(): ByteArray =
        quotas.joinToString("\n") { it.toBleString() }.toByteArray(Charsets.UTF_8)
}

/**
 * Merge a freshly-fetched snapshot (`this`) with the [previous] one so a transient per-quota
 * failure doesn't blank out that quota's numbers on the phone UI / BLE payload.
 *
 * Retention is keyed on the **(service id, quota name) pair**, not quota name alone. Keying on
 * name alone would let two different services that happen to emit the same quota label
 * cross-suppress each other's retention. Service id alone isn't enough either: one service can
 * report several distinct quotas under the same id — ClaudeScraper emits both "Claude 5h" and
 * "Claude wk" under `"claude"`. A partial settle (only one of the two came back fresh this round)
 * must retain the OTHER previous quota, not drop it because "claude" produced *a* success.
 *
 * Rules, per previous [QuotaResult.Success]:
 *  - If a fresh Success carries the same `(service, quota.name)` pair, the fresh value wins —
 *    nothing is retained.
 *  - Otherwise the previous Success is retained ONLY if the fresh snapshot shows its service still
 *    *actively reporting*: at least one [QuotaResult.Success] or [QuotaResult.Error] for that
 *    service. A service that only errored this round is transiently broken, so carrying its
 *    last-known-good numbers is honest.
 *  - If the service's fresh results are only [QuotaResult.Unavailable] (session expired / not
 *    logged in / no GitHub token), or the service is entirely absent from the fresh snapshot, the
 *    retained data is dropped. Unavailability is a *deliberate* state, and the display / BLE
 *    payload must never present deliberately-unavailable data as if it were live — showing
 *    hour-old numbers under a "logged out" service reads as current and misleads.
 *  - Retention additionally respects [MAX_STALE_MS]: a retained Success older than that relative
 *    to [now] is dropped regardless.
 *
 * Any fresh Error/Unavailable is kept as-is alongside a retained Success, so the failure stays
 * visible even while last-known-good numbers are shown.
 *
 * The merged list is also sorted into a stable, deterministic order (bd m5-73a) — see
 * [QUOTA_RESULT_ORDER].
 */
fun QuotaSnapshot.mergedWith(previous: QuotaSnapshot, now: Long): QuotaSnapshot {
    val freshSuccessKeys = successes.map { it.service to it.quota.name }.toSet()
    // Services still "actively reporting" this round: they produced a Success or an Error.
    // A service seen only as Unavailable (or not seen at all) is deliberately down — no retention.
    val activeServices = results
        .filter { it is QuotaResult.Success || it is QuotaResult.Error }
        .map { it.service }
        .toSet()

    val retainedStale = previous.successes
        .filter { (it.service to it.quota.name) !in freshSuccessKeys }
        .filter { it.service in activeServices }
        .filter { now - it.fetchedAt <= MAX_STALE_MS }

    return copy(results = (results + retainedStale).sortedWith(QUOTA_RESULT_ORDER))
}

/**
 * Canonical service order for display and the BLE payload. Unknown service ids (anything added
 * later that this list hasn't been updated for) sort after all known ones, alphabetically among
 * themselves via [QUOTA_RESULT_ORDER]'s service-id tiebreak.
 */
private val SERVICE_ORDER = listOf("claude", "codex", "github")

private fun serviceRank(service: String): Int {
    val idx = SERVICE_ORDER.indexOf(service)
    return if (idx >= 0) idx else Int.MAX_VALUE
}

/**
 * Deterministic ordering for a merged results list (bd m5-73a). Without this, [mergedWith] used
 * to append retained-stale quotas at the tail of the list (`results + retainedStale`), so a
 * carried-over quota jumped to the bottom of the UI and the tail of the BLE payload on the round
 * it went stale, then jumped back once fresh again — visible churn, and a quota that's
 * consistently retained late in a busy snapshot could fall past the firmware's 6-line display cap
 * entirely.
 *
 * Sort key is (canonical service rank, service id, quota name — empty for Error/Unavailable,
 * which have none). Deliberately depends on nothing but (service, quota name) — never `fetchedAt`
 * or any other freshness signal — so the same *set* of quotas always sorts into the same order
 * regardless of which ones arrived fresh this round vs. were retained from the previous one.
 *
 * What this ordering actually reaches: [QuotaSnapshot.toBlePayload] iterates `quotas`
 * (successes only, in this sorted order) directly, so the BLE payload's line order is exactly
 * this. The phone UI (`QuotaWatchScreen` in MainActivity) does NOT render `results` directly —
 * it renders three separate loops over `snapshot.successes`, then `snapshot.errors`, then
 * `snapshot.unavailable`, each filtered from this same sorted `results` list — so on-screen,
 * this ordering governs the successes' relative order (the actual fix for the reported churn) and
 * separately the errors' and the unavailables' relative order among themselves, but a service's
 * error/unavailable card never appears interleaved with its success cards regardless of where
 * this comparator would place it in the unfiltered list.
 */
private val QUOTA_RESULT_ORDER: Comparator<QuotaResult> =
    compareBy<QuotaResult> { serviceRank(it.service) }
        .thenBy { it.service }
        .thenBy { (it as? QuotaResult.Success)?.quota?.name ?: "" }
