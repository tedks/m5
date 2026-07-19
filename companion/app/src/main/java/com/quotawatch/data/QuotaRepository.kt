package com.quotawatch.data

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.quotawatch.api.ApiKeys
import com.quotawatch.api.KeyStore
import com.quotawatch.api.LoginStatus
import com.quotawatch.api.QuotaFetcher
import com.quotawatch.api.QuotaSnapshot
import com.quotawatch.api.mergedWith
import com.quotawatch.ble.BleClient
import com.quotawatch.wear.WearSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * App-scoped owner of the quota-fetching pipeline and its state.
 *
 * Previously all of this lived in [com.quotawatch.ui.QuotaViewModel], which is torn down with the
 * last Activity. Hoisting it to a process-lifetime singleton (held by [com.quotawatch.QuotaWatchApp])
 * lets the foreground refresh service keep refreshing — and keep the BLE-connected M5 current —
 * while no Activity exists. The ViewModel is now a thin adapter that delegates here.
 *
 * The merge/keys-await semantics in [doRefresh] are carried over from the ViewModel unchanged.
 */
class QuotaRepository(private val app: Application) {

    companion object {
        const val TAG = "QuotaRepository"
        const val AUTO_REFRESH_INTERVAL_MS = 5 * 60 * 1000L
    }

    // App-lifetime scope for the key-store flow and refresh coroutines. Main dispatcher because
    // the WebView scrapers must run on the main thread; doRefresh itself hops to IO where needed.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val bleClient = BleClient(app)
    private val wearSync = WearSync(app)
    private val keyStore = KeyStore(app)

    // Weak ref to the live Activity so scrapers can render WebViews with an Activity context
    // (Application context fails on some devices, see 046ec8c) without holding the destroyed
    // Activity alive. The provider prefers the live Activity and only falls back to the
    // Application context when none is set (e.g. a purely-background service refresh).
    // A destroyed-but-not-yet-collected Activity is skipped too — inflating a WebView against a
    // destroyed Activity context crashes, so fall back to the app context in that window.
    private var activityRef: WeakReference<Context> = WeakReference<Context>(null)
    val fetcher = QuotaFetcher { (activityRef.get() as? Activity)?.takeUnless { it.isDestroyed } ?: app }

    val apiKeys: StateFlow<ApiKeys> = keyStore.keys
        .stateIn(scope, SharingStarted.Eagerly, ApiKeys())

    // Reactive login status per service (bd m5-7ph council finding): the Compose UI previously
    // read fetcher.claudeLoginStatus()/codexLoginStatus() synchronously inside the Composable
    // body, which only re-evaluates on some UNRELATED recomposition — a cold start could show
    // "Logged in" for a persisted EXPIRED outcome until something else happened to recompose, and
    // an outcome change (a scrape completing and recording OK/EXPIRED) never itself triggered a
    // redraw. stateIn'd here so SettingsCard can collectAsStateWithLifecycle instead. The seed
    // value is the synchronous read at construction time (same as the old behavior) so there's no
    // flash of a wrong default before the flow's first real emission arrives.
    val claudeLoginStatus: StateFlow<LoginStatus> = fetcher.claudeLoginStatusFlow()
        .stateIn(scope, SharingStarted.Eagerly, fetcher.claudeLoginStatus())
    val codexLoginStatus: StateFlow<LoginStatus> = fetcher.codexLoginStatusFlow()
        .stateIn(scope, SharingStarted.Eagerly, fetcher.codexLoginStatus())

    private val _quotas = MutableStateFlow(QuotaSnapshot(emptyList()))
    val quotas: StateFlow<QuotaSnapshot> = _quotas

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val _autoRefreshEnabled = MutableStateFlow(true)
    val autoRefreshEnabled: StateFlow<Boolean> = _autoRefreshEnabled

    // Refresh when the app is brought to the foreground if the data is stale, so opening the app
    // after it sat backgrounded (past a tick, or with the process frozen) shows current numbers
    // rather than waiting for the next periodic tick to fire. Registered once for the process
    // lifetime — it covers the case where the auto-refresh service is disabled.
    private val foregroundObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) = refreshIfStale()
    }

    init {
        // ProcessLifecycleOwner.get() must be touched from the main thread, so the repository's
        // lazy first init must happen on the main thread — true today, since it's constructed from
        // QuotaViewModel init and QuotaRefreshService.onCreate, both on the main thread.
        ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundObserver)
        observeBleConnectionsForPush()
    }

    /**
     * Push the current snapshot to the M5 the instant it (re)connects (gh #17). Without this,
     * doRefresh() is the only thing that ever sends the BLE payload, and only when a refresh
     * happens to complete while already Connected — a connection established *between* refresh
     * cycles (the normal case: the user opens the app, taps Connect, and the M5 pairs) left the
     * watch showing nothing until the next scheduled or manual refresh.
     *
     * Runs for the repository's whole process lifetime, same as [foregroundObserver] — BLE can
     * connect/reconnect at any point regardless of whether an Activity is in front.
     */
    private fun observeBleConnectionsForPush() {
        scope.launch {
            var previousState: BleClient.State? = null
            bleClient.state.collect { current ->
                if (isFreshBleConnection(previousState, current)) {
                    val snapshot = _quotas.value
                    if (snapshot.quotas.isNotEmpty()) {
                        Log.d(TAG, "BLE connected — pushing current snapshot (${snapshot.quotas.size} quotas)")
                        bleClient.sendQuotaData(snapshot.toBlePayload())
                    }
                }
                previousState = current
            }
        }
    }

    /** Provide the live Activity context for WebView scraping; call from Activity.onCreate. */
    fun setActivityContext(context: Context) {
        activityRef = WeakReference(context)
    }

    fun updateApiKeys(keys: ApiKeys) {
        scope.launch { keyStore.save(keys) }
    }

    fun connectBle() { bleClient.scan() }
    fun disconnectBle() { bleClient.disconnect() }

    /** Toggle the auto-refresh preference. Starting/stopping the service is the caller's job. */
    fun toggleAutoRefresh() {
        _autoRefreshEnabled.value = !_autoRefreshEnabled.value
    }

    /** Fire-and-forget manual refresh (e.g. the Refresh button). */
    fun refresh() {
        scope.launch { doRefresh() }
    }

    /**
     * Call when the login WebView's "Done" is tapped (bd m5-7ph finding: without this, a service
     * that had gone EXPIRED kept showing "Session expired" for up to 45s after a successful
     * re-login, until the next scheduled refresh happened to run and record the real result).
     *
     * Just triggers a refresh — same as [refresh]. An earlier version of this also optimistically
     * reset the service's recorded outcome to UNKNOWN before refreshing, on the theory that a
     * fresh cookie should read as logged-in immediately rather than waiting out the scrape. That
     * was wrong (caught in council review convergence): if the user taps "Done" WITHOUT actually
     * completing login, the follow-up refresh hits the login wall again, its scrape times out or
     * lands on a still-unrecognized interstitial, and per sessionLooksValid's contract that records
     * nothing — so the optimistic UNKNOWN, combined with surviving stale cookies, would read as
     * LOGGED_IN *indefinitely*, not just briefly. An EXPIRED outcome must only ever be cleared by
     * genuine positive evidence from a scrape (sessionLooksValid / a login redirect no longer
     * firing), never by the mere act of dismissing the login screen. Honest brief staleness while
     * the refresh runs beats a lie that never self-corrects.
     */
    fun onLoginDone() {
        refresh()
    }

    /** Refresh on foreground if the current snapshot is empty or older than one auto-refresh tick. */
    private fun refreshIfStale() {
        val snapshot = _quotas.value
        val ageMs = System.currentTimeMillis() - snapshot.timestamp
        if (snapshot.results.isEmpty() || ageMs >= AUTO_REFRESH_INTERVAL_MS) {
            Log.d(TAG, "Foreground: data stale (empty=${snapshot.results.isEmpty()}, age=${ageMs}ms), refreshing")
            scope.launch { doRefresh() }
        } else {
            Log.d(TAG, "Foreground: data fresh (age=${ageMs}ms), skipping refresh")
        }
    }

    /**
     * Fetch every service, merge with the last snapshot, and push to BLE + Wear.
     * Suspends until the refresh completes so the periodic service loop can await each tick.
     */
    suspend fun doRefresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        try {
            // Await the first real emission from the key store rather than reading the eagerly
            // stateIn'd apiKeys, whose seed value is an empty ApiKeys() — otherwise the very first
            // refresh could run before persisted keys load and silently skip GitHub.
            val keys = keyStore.keys.first()
            val fresh = fetcher.fetchAll(keys)
            // Merge with the last snapshot so a transiently-failing service doesn't blank out
            // its numbers on the UI/BLE payload — see QuotaSnapshot.mergedWith for the semantics.
            val snapshot = fresh.mergedWith(_quotas.value, System.currentTimeMillis())
            _quotas.value = snapshot

            if (bleClient.state.value is BleClient.State.Connected) {
                bleClient.sendQuotaData(snapshot.toBlePayload())
            }

            wearSync.syncQuotas(snapshot)
            Log.d(TAG, "Refreshed: ${snapshot.quotas.size} quotas, ${snapshot.errors.size} errors")
        } catch (e: Exception) {
            Log.e(TAG, "Refresh failed", e)
        } finally {
            _refreshing.value = false
        }
    }
}

/**
 * Whether a BLE state transition from [previous] to [current] is the moment to push the current
 * quota snapshot (gh #17) — pure and unit-testable without a real [BleClient].
 *
 * True exactly on arriving at [BleClient.State.Connected] from anything else, including
 * [BleClient.State.Reconnecting] -> [BleClient.State.Connected]: the M5 may have rebooted and
 * lost whatever it last held, so a reconnection deserves a fresh push same as a first connection.
 * False if [current] isn't Connected, and false if [previous] was already Connected — the latter
 * guards against resending on some unrelated re-emission of the same state rather than a genuine
 * transition (StateFlow itself already dedupes structurally-equal consecutive values, but this
 * keeps the "only on an actual transition" contract explicit and independently testable).
 */
fun isFreshBleConnection(previous: BleClient.State?, current: BleClient.State): Boolean =
    current is BleClient.State.Connected && previous !is BleClient.State.Connected
