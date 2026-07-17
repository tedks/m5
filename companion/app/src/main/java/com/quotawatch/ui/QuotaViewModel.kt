package com.quotawatch.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.quotawatch.api.ApiKeys
import com.quotawatch.api.KeyStore
import com.quotawatch.api.QuotaFetcher
import com.quotawatch.api.QuotaSnapshot
import com.quotawatch.api.mergedWith
import com.quotawatch.ble.BleClient
import com.quotawatch.wear.WearSync
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

class QuotaViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val TAG = "QuotaViewModel"
        const val AUTO_REFRESH_INTERVAL_MS = 5 * 60 * 1000L
    }

    val bleClient = BleClient(app)
    private val wearSync = WearSync(app)
    private val keyStore = KeyStore(app)

    // Weak ref to the live Activity so scrapers can render WebViews with an Activity context
    // (Application context fails on some devices, see 046ec8c) without the ViewModel — which
    // outlives the Activity across config changes — leaking the destroyed Activity. The provider
    // prefers the live Activity and only falls back to the Application context for safety.
    private var activityRef: WeakReference<Context> = WeakReference<Context>(null)
    val fetcher = QuotaFetcher { activityRef.get() ?: getApplication<Application>() }

    val apiKeys: StateFlow<ApiKeys> = keyStore.keys
        .stateIn(viewModelScope, SharingStarted.Eagerly, ApiKeys())

    private val _quotas = MutableStateFlow(QuotaSnapshot(emptyList()))
    val quotas: StateFlow<QuotaSnapshot> = _quotas

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val _autoRefreshEnabled = MutableStateFlow(true)
    val autoRefreshEnabled: StateFlow<Boolean> = _autoRefreshEnabled

    private var autoRefreshJob: Job? = null

    // Refresh when the app is brought to the foreground if the data is stale, so opening the app
    // after it sat backgrounded (past a tick, or with the process frozen) shows current numbers
    // rather than waiting for the next periodic tick to fire.
    private val foregroundObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) = refreshIfStale()
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundObserver)
        startAutoRefresh()
    }

    /** Call from Activity.onCreate to provide Activity context for WebView scraping. */
    fun setActivityContext(context: Context) {
        activityRef = WeakReference(context)
    }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(foregroundObserver)
        super.onCleared()
    }

    fun updateApiKeys(keys: ApiKeys) {
        viewModelScope.launch { keyStore.save(keys) }
    }

    fun connectBle() { bleClient.scan() }
    fun disconnectBle() { bleClient.disconnect() }

    fun toggleAutoRefresh() {
        _autoRefreshEnabled.value = !_autoRefreshEnabled.value
        if (_autoRefreshEnabled.value) startAutoRefresh()
        else { autoRefreshJob?.cancel(); autoRefreshJob = null }
    }

    /** Refresh on foreground if the current snapshot is empty or older than one auto-refresh tick. */
    private fun refreshIfStale() {
        val snapshot = _quotas.value
        val ageMs = System.currentTimeMillis() - snapshot.timestamp
        if (snapshot.results.isEmpty() || ageMs >= AUTO_REFRESH_INTERVAL_MS) {
            Log.d(TAG, "Foreground: data stale (empty=${snapshot.results.isEmpty()}, age=${ageMs}ms), refreshing")
            viewModelScope.launch { doRefresh() }
        } else {
            Log.d(TAG, "Foreground: data fresh (age=${ageMs}ms), skipping refresh")
        }
    }

    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            // Best-effort periodic refresh: while backgrounded the OS process freezer can suspend
            // this loop, so ticks aren't guaranteed. The initial "app just opened" refresh is
            // handled by the ON_START observer above; this is replaced by a foreground service in
            // the next PR of the stack.
            while (true) {
                delay(AUTO_REFRESH_INTERVAL_MS)
                if (_autoRefreshEnabled.value) doRefresh()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { doRefresh() }
    }

    private suspend fun doRefresh() {
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
