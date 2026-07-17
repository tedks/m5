package com.quotawatch.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaSnapshotMergeTest {

    private fun success(service: String, name: String, fetchedAt: Long) =
        QuotaResult.Success(service, Quota(name, 10f, 100f, "%"), fetchedAt)

    private fun error(service: String, message: String = "boom") =
        QuotaResult.Error(service, message)

    private fun unavailable(service: String, reason: String = "not logged in") =
        QuotaResult.Unavailable(service, reason)

    @Test
    fun `new success replaces old success for the same quota name`() {
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
    fun `partial success retains the missing sub-quota under the same service`() {
        // I2: ClaudeScraper emits two quotas under "claude"; a partial settle returns only one.
        // The other, previously-good sub-quota must be retained, not dropped because "claude"
        // produced *a* success. Retention keys on quota name, not service id.
        val now = 1_000_000L
        val previous = QuotaSnapshot(
            results = listOf(
                success("claude", "Claude 5h", now - 10_000),
                success("claude", "Claude wk", now - 10_000)
            ),
            timestamp = now - 10_000
        )
        val fresh = QuotaSnapshot(
            results = listOf(success("claude", "Claude 5h", now)),
            timestamp = now
        )

        val merged = fresh.mergedWith(previous, now)

        assertEquals(2, merged.results.size)
        val fresh5h = merged.results.single { it is QuotaResult.Success && it.quota.name == "Claude 5h" } as QuotaResult.Success
        assertEquals(now, fresh5h.fetchedAt)
        val retainedWk = merged.results.single { it is QuotaResult.Success && it.quota.name == "Claude wk" } as QuotaResult.Success
        assertEquals(now - 10_000, retainedWk.fetchedAt)
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
    fun `service absent from the new snapshot drops its previous success`() {
        // I3 (inverted from the old behavior): a service the fresh snapshot doesn't mention at all
        // (e.g. the GitHub token was cleared, so fetchAll emits nothing for it) is deliberately
        // gone. Carrying its last numbers would present stale data as live, so retention is
        // dropped — deliberate unavailability must show honestly.
        val now = 1_000_000L
        val previous = QuotaSnapshot(
            results = listOf(
                success("claude", "Claude 5h", now - 10_000),
                success("github", "Actions", now - 10_000)
            ),
            timestamp = now - 10_000
        )
        val fresh = QuotaSnapshot(
            results = listOf(success("claude", "Claude 5h", now)),
            timestamp = now
        )

        val merged = fresh.mergedWith(previous, now)

        assertEquals(1, merged.results.size)
        assertFalse(merged.results.any { it.service == "github" })
    }

    @Test
    fun `fresh Unavailable drops the retained success but stays visible itself`() {
        // I3: Unavailable is a deliberate state (session expired / logged out / no token). The
        // service is not actively reporting, so its previous numbers are dropped — but the
        // Unavailable itself remains so the UI can show the honest "logged out" state.
        val now = 1_000_000L
        val previous = QuotaSnapshot(
            results = listOf(success("claude", "Claude 5h", now - 10_000)),
            timestamp = now - 10_000
        )
        val fresh = QuotaSnapshot(
            results = listOf(unavailable("claude", "session expired")),
            timestamp = now
        )

        val merged = fresh.mergedWith(previous, now)

        assertEquals(1, merged.results.size)
        assertTrue(merged.results.single() is QuotaResult.Unavailable)
        assertFalse(merged.results.any { it is QuotaResult.Success })
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
