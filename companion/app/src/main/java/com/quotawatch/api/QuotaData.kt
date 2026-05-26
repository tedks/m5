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
    data class Success(val quota: Quota) : QuotaResult()
    data class Error(val service: String, val message: String) : QuotaResult()
    data class Unavailable(val service: String, val reason: String) : QuotaResult()
}

data class QuotaSnapshot(
    val results: List<QuotaResult>,
    val timestamp: Long = System.currentTimeMillis()
) {
    val quotas: List<Quota> get() = results.filterIsInstance<QuotaResult.Success>().map { it.quota }
    val errors: List<QuotaResult.Error> get() = results.filterIsInstance<QuotaResult.Error>()
    val unavailable: List<QuotaResult.Unavailable> get() = results.filterIsInstance<QuotaResult.Unavailable>()

    fun toBlePayload(): ByteArray =
        quotas.joinToString("\n") { it.toBleString() }.toByteArray(Charsets.UTF_8)
}
