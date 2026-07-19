package com.quotawatch.scraper

import com.quotawatch.scraper.UsageScraper.Companion.PollDecision
import com.quotawatch.scraper.UsageScraper.Companion.PollState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure settle/grace decision (bd m5-u1d finding: Codex's optional 5h section
 * can render a beat after weekly settles, and finalizing the instant `isSettled` first goes true
 * can permanently miss it). No WebView/Handler involved — decidePoll is a pure function of the
 * decoded payload and prior [PollState].
 *
 * "Weekly-only" / "weekly+5h" strings below stand in for CodexScraper's decoded JSON; the actual
 * JSON shape doesn't matter to decidePoll, only string identity/equality.
 */
class PollDecisionTest {

    private val weeklyOnly = "weekly"
    private val weeklyAndFiveHour = "weekly+5h"
    private val notSettled = "not-settled"

    // isSettled treats anything other than notSettled as "the required field is present".
    private val isSettled: (String) -> Boolean = { it != notSettled }

    @Test
    fun `zero grace finalizes on the very first settled and stable tick`() {
        // graceExtraPolls = 0 must reproduce the original always-finalize-immediately behavior
        // (this is what every caller except CodexScraper uses).
        var state = PollState()

        // Tick 1: first sighting, nothing to compare against yet -> not stable -> keep polling.
        val d1 = UsageScraper.Companion.decidePoll(weeklyOnly, state, isSettled, graceExtraPolls = 0)
        assertTrue(d1 is PollDecision.Continue)
        state = (d1 as PollDecision.Continue).next

        // Tick 2: same value again -> stable and settled -> finalize immediately, no grace.
        val d2 = UsageScraper.Companion.decidePoll(weeklyOnly, state, isSettled, graceExtraPolls = 0)
        assertEquals(PollDecision.Finalize(weeklyOnly), d2)
    }

    @Test
    fun `richer value that appears and stabilizes within the grace window is captured`() {
        var state = PollState()
        // Tick 1: first sighting.
        state = continueState(UsageScraper.Companion.decidePoll(weeklyOnly, state, isSettled, graceExtraPolls = 2))
        // Tick 2: weekly stable -> first settle, enters the 2-tick grace window.
        state = continueState(UsageScraper.Companion.decidePoll(weeklyOnly, state, isSettled, graceExtraPolls = 2))
        // Tick 3 (grace 1/2): 5h shows up -> value changed, not itself stable yet.
        state = continueState(UsageScraper.Companion.decidePoll(weeklyAndFiveHour, state, isSettled, graceExtraPolls = 2))
        // Tick 4 (grace 2/2, last allotted tick): 5h value repeats -> stable+settled -> captured
        // as the richer bestSettled, and the grace window's countdown hits zero -> finalize.
        val finalDecision = UsageScraper.Companion.decidePoll(weeklyAndFiveHour, state, isSettled, graceExtraPolls = 2)

        assertEquals(PollDecision.Finalize(weeklyAndFiveHour), finalDecision)
    }

    @Test
    fun `grace window is bounded — expires and finalizes on weekly-only when 5h never appears`() {
        var state = PollState()
        state = continueState(UsageScraper.Companion.decidePoll(weeklyOnly, state, isSettled, graceExtraPolls = 2))
        state = continueState(UsageScraper.Companion.decidePoll(weeklyOnly, state, isSettled, graceExtraPolls = 2)) // first settle
        state = continueState(UsageScraper.Companion.decidePoll(weeklyOnly, state, isSettled, graceExtraPolls = 2)) // grace 1/2

        // Grace 2/2: still weekly-only. The window is exhausted regardless — must finalize now,
        // not keep polling indefinitely waiting for a 5h value that's never coming.
        val finalDecision = UsageScraper.Companion.decidePoll(weeklyOnly, state, isSettled, graceExtraPolls = 2)

        assertEquals(PollDecision.Finalize(weeklyOnly), finalDecision)
    }

    @Test
    fun `5h arriving too late to restabilize before the window closes falls back to the last settled value, not an error`() {
        var state = PollState()
        state = continueState(UsageScraper.Companion.decidePoll(weeklyOnly, state, isSettled, graceExtraPolls = 2))
        state = continueState(UsageScraper.Companion.decidePoll(weeklyOnly, state, isSettled, graceExtraPolls = 2)) // first settle
        state = continueState(UsageScraper.Companion.decidePoll(weeklyOnly, state, isSettled, graceExtraPolls = 2)) // grace 1/2

        // Grace 2/2 (the last allotted tick): 5h shows up right now, but showing up alone isn't
        // "stable" (needs to repeat once more) — the window closes before it gets the chance.
        // Must fall back to the last genuinely settled value (weekly-only), never surface an
        // incomplete/unstable payload and never emit an error.
        val finalDecision = UsageScraper.Companion.decidePoll(weeklyAndFiveHour, state, isSettled, graceExtraPolls = 2)

        assertEquals(PollDecision.Finalize(weeklyOnly), finalDecision)
    }

    @Test
    fun `never finalizes while nothing has settled yet, regardless of grace`() {
        var state = PollState()
        repeat(5) {
            val decision = UsageScraper.Companion.decidePoll(notSettled, state, isSettled, graceExtraPolls = 2)
            assertTrue("must keep polling while isSettled is false", decision is PollDecision.Continue)
            state = continueState(decision)
        }
    }

    private fun continueState(decision: PollDecision): PollState =
        (decision as PollDecision.Continue).next
}
