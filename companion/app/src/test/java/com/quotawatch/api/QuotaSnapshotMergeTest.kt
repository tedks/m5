package com.quotawatch.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaSnapshotMergeTest {

    private fun success(service: String, name: String, fetchedAt: Long) =
        QuotaResult.Success(service, Quota(name, 10f, 100f, "%"), fetchedAt)

    private fun error(service: String, message: String = "boom") =
        QuotaResult.Error(service, message)

    @Test
    fun `new success replaces old success for the same service`() {
        val now = 1_000_000L
        val previous = QuotaSnapshot(
            results = listOf(success("claude", "Claude 5h", now - 5_000)),
            timestamp = now - 5_000
        )
        val fresh = QuotaSnapshot(
            results = listOf(success("claude", "Claude 5h", now)),
            timestamp = now
        )

        val merged = fresh.mergedWith(previous, now)

        assertEquals(1, merged.results.size)
        val onlySuccess = merged.results.single() as QuotaResult.Success
        assertEquals(now, onlySuccess.fetchedAt)
    }

    @Test
    fun `service that only errors this round retains previous success and shows the error`() {
        val now = 1_000_000L
        val previous = QuotaSnapshot(
            results = listOf(success("codex", "Codex 5h", now - 60_000)),
            timestamp = now - 60_000
        )
        val fresh = QuotaSnapshot(
            results = listOf(error("codex", "timed out")),
            timestamp = now
        )

        val merged = fresh.mergedWith(previous, now)

        assertEquals(2, merged.results.size)
        assertTrue(merged.results.any { it is QuotaResult.Success && it.service == "codex" })
        assertTrue(merged.results.any { it is QuotaResult.Error && it.service == "codex" && it.message == "timed out" })
    }

    @Test
    fun `retention respects the MAX_STALE_MS cutoff`() {
        val now = 10_000_000L
        val staleFetchedAt = now - MAX_STALE_MS - 1
        val previous = QuotaSnapshot(
            results = listOf(success("claude", "Claude 5h", staleFetchedAt)),
            timestamp = staleFetchedAt
        )
        val fresh = QuotaSnapshot(
            results = listOf(error("claude", "still broken")),
            timestamp = now
        )

        val merged = fresh.mergedWith(previous, now)

        // The stale Success is dropped outright; only the fresh Error survives.
        assertEquals(1, merged.results.size)
        assertTrue(merged.results.single() is QuotaResult.Error)
    }

    @Test
    fun `service absent from the new snapshot entirely retains its previous success`() {
        val now = 1_000_000L
        val previous = QuotaSnapshot(
            results = listOf(
                success("claude", "Claude 5h", now - 10_000),
                success("github", "Actions", now - 10_000)
            ),
            timestamp = now - 10_000
        )
        // e.g. the GitHub token was cleared this round, so fetchAll emits nothing for it at all.
        val fresh = QuotaSnapshot(
            results = listOf(success("claude", "Claude 5h", now)),
            timestamp = now
        )

        val merged = fresh.mergedWith(previous, now)

        assertEquals(2, merged.results.size)
        assertTrue(merged.results.any { it is QuotaResult.Success && it.service == "github" })
    }

    @Test
    fun `first-ever snapshot with empty previous is unchanged`() {
        val now = 1_000_000L
        val previous = QuotaSnapshot(results = emptyList(), timestamp = 0L)
        val fresh = QuotaSnapshot(
            results = listOf(success("claude", "Claude 5h", now), error("codex", "not logged in")),
            timestamp = now
        )

        val merged = fresh.mergedWith(previous, now)

        assertEquals(fresh.results, merged.results)
        assertEquals(fresh.timestamp, merged.timestamp)
    }
}
