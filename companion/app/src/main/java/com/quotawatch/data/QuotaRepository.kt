package com.quotawatch.data

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.quotawatch.api.ApiKeys
import com.quotawatch.api.KeyStore
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
    private var activityRef: WeakReference<Context> = WeakReference<Context>(null)
    val fetcher = QuotaFetcher { activityRef.get() ?: app }

    val apiKeys: StateFlow<ApiKeys> = keyStore.keys
        .stateIn(scope, SharingStarted.Eagerly, ApiKeys())

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
        ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundObserver)
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
