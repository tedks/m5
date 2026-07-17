package com.quotawatch.api

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the pure [loginStatusOf] mapping (bd m5-7ph). The store side (DataStore-backed
 * persistence in [SessionStore]) isn't exercised here — see its class doc — this locks in only
 * the state-combination logic: cookie presence combined with the last recorded scrape outcome.
 */
class LoginStatusTest {

    @Test
    fun `no cookie is not logged in regardless of last outcome`() {
        assertEquals(LoginStatus.NOT_LOGGED_IN, loginStatusOf(hasSessionCookie = false, lastOutcome = SessionOutcome.OK))
        assertEquals(LoginStatus.NOT_LOGGED_IN, loginStatusOf(hasSessionCookie = false, lastOutcome = SessionOutcome.EXPIRED))
        assertEquals(LoginStatus.NOT_LOGGED_IN, loginStatusOf(hasSessionCookie = false, lastOutcome = SessionOutcome.UNKNOWN))
    }

    @Test
    fun `cookie present and last outcome OK is logged in`() {
        assertEquals(LoginStatus.LOGGED_IN, loginStatusOf(hasSessionCookie = true, lastOutcome = SessionOutcome.OK))
    }

    @Test
    fun `cookie present and last outcome EXPIRED is session expired`() {
        assertEquals(LoginStatus.SESSION_EXPIRED, loginStatusOf(hasSessionCookie = true, lastOutcome = SessionOutcome.EXPIRED))
    }

    @Test
    fun `cookie present and never-yet-scraped outcome defaults to logged in`() {
        // UNKNOWN means "no scrape has recorded an outcome" (fresh install, or DataStore hasn't
        // finished its first load yet) — cookie presence alone drives this case, same as the old
        // behavior before this store existed. It only downgrades once a scrape actually detects a
        // login redirect.
        assertEquals(LoginStatus.LOGGED_IN, loginStatusOf(hasSessionCookie = true, lastOutcome = SessionOutcome.UNKNOWN))
    }

    // ---- sessionLooksValid (council review finding on bd m5-7ph: recording OK on any completed
    // parse could flip a genuinely EXPIRED outcome back to "logged in" off a login-wall page that
    // happened to parse to an all-Error result) ----

    private fun success(name: String = "Quota") = QuotaResult.Success("svc", Quota(name, 1f, 100f, "%"))
    private fun error(message: String = "boom") = QuotaResult.Error("svc", message)

    @Test
    fun `a Success is always sufficient evidence, pageReady or not`() {
        assertEquals(true, sessionLooksValid(listOf(success()), pageReady = false))
        assertEquals(true, sessionLooksValid(listOf(success()), pageReady = true))
    }

    @Test
    fun `all-Error results with pageReady false are not evidence — e g a login wall`() {
        // This is the exact scenario the finding was about: some page rendered (parseUsage ran to
        // completion, no exception) but it wasn't the real usage panel, so pageReady is false and
        // the only result is an Error.
        assertEquals(false, sessionLooksValid(listOf(error()), pageReady = false))
    }

    @Test
    fun `all-Error results with pageReady true are still valid — real panel rendered but no numbers`() {
        // Claude-specific case: usageReady true means the labeled "Plan usage limits" panel
        // actually rendered, just without extractable fiveHourPct/sevenDayPct — a real page
        // layout hiccup, not a login wall. Still positive evidence of a valid session.
        assertEquals(true, sessionLooksValid(listOf(error()), pageReady = true))
    }

    @Test
    fun `pageReady defaults to false — Codex has no such signal and must rely on Success alone`() {
        assertEquals(false, sessionLooksValid(listOf(error())))
    }

    @Test
    fun `empty results with pageReady false is not evidence`() {
        assertEquals(false, sessionLooksValid(emptyList(), pageReady = false))
    }
}
