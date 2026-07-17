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
}
