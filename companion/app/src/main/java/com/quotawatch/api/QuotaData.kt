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
 * Merge a freshly-fetched snapshot (`this`) with the [previous] one so a transient per-service
 * failure doesn't blank out that service's numbers on the phone UI / BLE payload.
 *
 * Rules, per service id:
 *  - If this snapshot has at least one [QuotaResult.Success] for a service, its results for that
 *    service win outright — nothing from [previous] is carried over for it.
 *  - Otherwise (the service only errored/is unavailable this round, or is entirely absent from
 *    this snapshot — e.g. a removed GitHub token), retain [previous]'s Success results for that
 *    service as long as they're not older than [MAX_STALE_MS] relative to [now]. Any fresh
 *    Error/Unavailable this snapshot has for that service is kept as-is alongside the retained
 *    Success, so the failure stays visible even while last-known-good numbers are shown.
 */
fun QuotaSnapshot.mergedWith(previous: QuotaSnapshot, now: Long): QuotaSnapshot {
    val freshSuccessServices = successes.map { it.service }.toSet()

    val retainedStale = previous.successes
        .filter { it.service !in freshSuccessServices }
        .filter { now - it.fetchedAt <= MAX_STALE_MS }

    return copy(results = results + retainedStale)
}
