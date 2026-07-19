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
        // produced *a* success. Retention keys on (service, quota name), not quota name alone.
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
    fun `fresh success on one service does not suppress retention of a same-named quota on another`() {
        // Retention keys on (service, quota name), not quota name alone — two services that
        // happen to emit the same label must not cross-suppress each other's retention.
        val now = 1_000_000L
        val previous = QuotaSnapshot(
            results = listOf(
                success("serviceA", "Quota X", now - 10_000),
                success("serviceB", "Quota X", now - 10_000)
            ),
            timestamp = now - 10_000
        )
        val fresh = QuotaSnapshot(
            results = listOf(
                success("serviceA", "Quota X", now),
                error("serviceB", "boom")
            ),
            timestamp = now
        )

        val merged = fresh.mergedWith(previous, now)

        assertEquals(3, merged.results.size)
        val freshA = merged.results.single { it is QuotaResult.Success && it.service == "serviceA" } as QuotaResult.Success
        assertEquals(now, freshA.fetchedAt)
        val retainedB = merged.results.single { it is QuotaResult.Success && it.service == "serviceB" } as QuotaResult.Success
        assertEquals("Quota X", retainedB.quota.name)
        assertEquals(now - 10_000, retainedB.fetchedAt)
        assertTrue(merged.results.any { it is QuotaResult.Error && it.service == "serviceB" })
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

    // ---- Stable ordering (bd m5-73a) ----

    private fun nameOf(r: QuotaResult): String = when (r) {
        is QuotaResult.Success -> r.quota.name
        is QuotaResult.Error -> "${r.service}:error"
        is QuotaResult.Unavailable -> "${r.service}:unavailable"
    }

    @Test
    fun `retained item is slotted adjacent to its service, not appended at the tail`() {
        val now = 1_000_000L
        val previous = QuotaSnapshot(
            results = listOf(
                success("claude", "Claude 5h", now - 10_000),
                success("claude", "Claude wk", now - 10_000),
                success("codex", "Codex wk", now - 10_000)
            ),
            timestamp = now - 10_000
        )
        // This round: Claude wk fails to parse (retained), Codex wk and GitHub Actions are fresh.
        val fresh = QuotaSnapshot(
            results = listOf(
                success("claude", "Claude 5h", now),
                error("claude", "timed out"),
                success("codex", "Codex wk", now),
                success("github", "Actions", now)
            ),
            timestamp = now
        )

        val merged = fresh.mergedWith(previous, now)

        // The old behavior appended retained items at the tail: [..., github Actions, claude wk].
        // The retained "Claude wk" must instead land next to the other claude entries, ahead of
        // codex/github — not at the very end of the list.
        val order = merged.results.map { nameOf(it) }
        val claudeWkIndex = order.indexOf("Claude wk")
        val githubIndex = order.indexOf("Actions")
        assertTrue("retained Claude wk ($claudeWkIndex) must sort before github Actions ($githubIndex)",
            claudeWkIndex < githubIndex)
        // Exact full order: within the claude group, sort key is (quota name, or "" for the
        // Error) — "" sorts before any non-empty name, so "claude:error" (empty tiebreak) comes
        // BEFORE "Claude 5h", which then comes before "Claude wk" ("5h" < "wk" lexicographically).
        // The retained "Claude wk" lands third overall, immediately after the other two claude
        // entries — not appended after codex/github.
        assertEquals(
            listOf("claude:error", "Claude 5h", "Claude wk", "Codex wk", "Actions"),
            order
        )
    }

    @Test
    fun `merged order is stable across two rounds regardless of which quota is retained each time`() {
        val t0 = 1_000_000L
        val snapshot0 = QuotaSnapshot(
            results = listOf(
                success("claude", "Claude 5h", t0),
                success("claude", "Claude wk", t0),
                success("codex", "Codex wk", t0),
                success("github", "Actions", t0)
            ),
            timestamp = t0
        )

        // Round 1: Claude wk fails and is retained from snapshot0; everything else is fresh.
        val t1 = t0 + 60_000
        val fresh1 = QuotaSnapshot(
            results = listOf(
                success("claude", "Claude 5h", t1),
                error("claude", "timed out"),
                success("codex", "Codex wk", t1),
                success("github", "Actions", t1)
            ),
            timestamp = t1
        )
        val merged1 = fresh1.mergedWith(snapshot0, t1)

        // Round 2: this time Codex wk fails instead and is retained from merged1; Claude wk is
        // fresh again.
        val t2 = t1 + 60_000
        val fresh2 = QuotaSnapshot(
            results = listOf(
                success("claude", "Claude 5h", t2),
                success("claude", "Claude wk", t2),
                error("codex", "timed out"),
                success("github", "Actions", t2)
            ),
            timestamp = t2
        )
        val merged2 = fresh2.mergedWith(merged1, t2)

        // Same set of quota names both rounds -> same order, even though a different quota was
        // the one retained-from-previous in each round.
        val order1 = merged1.successes.map { it.quota.name }
        val order2 = merged2.successes.map { it.quota.name }
        assertEquals(order1, order2)
        assertEquals(listOf("Claude 5h", "Claude wk", "Codex wk", "Actions"), order1)
    }
}
