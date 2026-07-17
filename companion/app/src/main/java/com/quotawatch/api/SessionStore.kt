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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
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
        //
        // This collector fully REPLACES cache.value from persisted prefs on every DataStore
        // emission — including one triggered by a write for a service other than the one
        // recordOutcome's caller cares about. That interacts with recordOutcome's optimistic
        // update below: if two recordOutcome calls for *different* services were ever in flight
        // concurrently, the first one's DataStore emission could land before the second's `edit`
        // has persisted, momentarily dropping the second service's optimistic value from `cache`
        // until its own edit's emission arrives a beat later and restores it. Self-healing, and
        // not reachable today — QuotaFetcher.fetchAll scrapes Claude and Codex sequentially, so
        // their recordOutcome calls never overlap — but would apply if scraping became parallel
        // (bd m5-ti3).
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

    /**
     * Reactive counterpart of [current] — emits [SessionOutcome.UNKNOWN] immediately (the cache's
     * seed value) and then every actual change, including the very first persisted load
     * completing. That first post-load emission is what fixes cold-start staleness: a UI that
     * collects this (rather than reading [current] once) sees a persisted EXPIRED as soon as
     * DataStore finishes its initial read, instead of momentarily reading UNKNOWN-therefore-
     * logged-in until some unrelated recomposition happens to call [current] again.
     */
    fun outcomeFlow(service: String): Flow<SessionOutcome> = cache.map { it[service] ?: SessionOutcome.UNKNOWN }

    suspend fun recordOutcome(service: String, outcome: SessionOutcome) {
        // Update the in-memory view immediately so a caller reading `current()` right after this
        // suspend call returns sees it without waiting on the DataStore round-trip. See the
        // `init` block's comment for how this interacts with the persisted-data collector above.
        cache.value = cache.value + (service to outcome)
        contextProvider().applicationContext.sessionStatusDataStore.edit { prefs ->
            prefs[key(service)] = outcome.name
        }
    }
}
