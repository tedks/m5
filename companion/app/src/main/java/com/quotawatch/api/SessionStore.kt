package com.quotawatch.api

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

private val Context.sessionStatusDataStore: DataStore<Preferences> by preferencesDataStore(name = "session_status")

/**
 * Outcome of the most recently completed scrape attempt for a service's login session — distinct
 * from [com.quotawatch.scraper.UsageScraper.hasSession]'s cheap cookie-presence check (bd m5-7ph:
 * Cloudflare challenge cookies like `__cf_bm` survive real session expiry, so cookie presence
 * alone can't tell "logged in" from "logged out").
 */
enum class SessionOutcome { OK, EXPIRED, UNKNOWN }

/**
 * Persists [SessionOutcome] per service id ("claude", "codex") so the Settings UI's login status
 * reflects the last actual scrape result rather than cookie presence alone.
 *
 * [ClaudeScraper]/[CodexScraper] `fetchUsage()` record the outcome after every attempt: a
 * login-page redirect -> EXPIRED, any completed parse (even one that fails to find values on an
 * otherwise-valid page) -> OK. A transient failure (timeout, exception) records nothing — it says
 * nothing about the *session*, so the previous outcome is left standing.
 *
 * Reads are synchronous ([current]) because callers (Compose recomposition in
 * [com.quotawatch.ui.MainActivity]) query "are we logged in" the same way the code already did
 * before this store existed — no coroutine scope handy at the call site. The synchronous view is
 * an in-memory cache that [recordOutcome] updates immediately; the DataStore-backed persistence
 * underneath exists so the state survives process death (e.g. the background refresh service
 * scraping with no Activity open) instead of resetting to UNKNOWN on next launch.
 */
class SessionStore(private val contextProvider: () -> Context) {

    companion object {
        private fun key(service: String) = stringPreferencesKey("outcome_$service")

        private fun decode(raw: String?): SessionOutcome = when (raw) {
            "OK" -> SessionOutcome.OK
            "EXPIRED" -> SessionOutcome.EXPIRED
            else -> SessionOutcome.UNKNOWN
        }
    }

    // App-process-lifetime scope: this store is held by QuotaFetcher, itself owned by the
    // app-scoped QuotaRepository, so it never needs to be cancelled short of process death.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cache = MutableStateFlow<Map<String, SessionOutcome>>(emptyMap())

    init {
        // applicationContext: contextProvider() can hand back either the live Activity or the
        // Application depending on what's available (see QuotaRepository), but the DataStore must
        // always resolve to the same backing file regardless of which one we got.
        contextProvider().applicationContext.sessionStatusDataStore.data
            .onEach { prefs ->
                cache.value = mapOf(
                    "claude" to decode(prefs[key("claude")]),
                    "codex" to decode(prefs[key("codex")])
                )
            }
            .launchIn(scope)
    }

    /** Last-known outcome for [service]; UNKNOWN until a scrape has recorded one. */
    fun current(service: String): SessionOutcome = cache.value[service] ?: SessionOutcome.UNKNOWN

    suspend fun recordOutcome(service: String, outcome: SessionOutcome) {
        // Update the in-memory view immediately so a caller reading `current()` right after this
        // suspend call returns sees it without waiting on the DataStore round-trip.
        cache.value = cache.value + (service to outcome)
        contextProvider().applicationContext.sessionStatusDataStore.edit { prefs ->
            prefs[key(service)] = outcome.name
        }
    }
}
