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
 * Retention is keyed on **quota name**, not service id, because one service can report several
 * distinct quotas under the same id — ClaudeScraper emits both "Claude 5h" and "Claude wk" under
 * `"claude"`. A partial settle (only one of the two came back fresh this round) must retain the
 * OTHER previous quota, not drop it because "claude" produced *a* success.
 *
 * Rules, per previous [QuotaResult.Success]:
 *  - If a fresh Success carries the same `quota.name`, the fresh value wins — nothing is retained.
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
 */
fun QuotaSnapshot.mergedWith(previous: QuotaSnapshot, now: Long): QuotaSnapshot {
    val freshSuccessNames = successes.map { it.quota.name }.toSet()
    // Services still "actively reporting" this round: they produced a Success or an Error.
    // A service seen only as Unavailable (or not seen at all) is deliberately down — no retention.
    val activeServices = results
        .filter { it is QuotaResult.Success || it is QuotaResult.Error }
        .map { it.service }
        .toSet()

    val retainedStale = previous.successes
        .filter { it.quota.name !in freshSuccessNames }
        .filter { it.service in activeServices }
        .filter { now - it.fetchedAt <= MAX_STALE_MS }

    return copy(results = results + retainedStale)
}
