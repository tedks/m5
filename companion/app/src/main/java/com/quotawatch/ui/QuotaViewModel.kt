package com.quotawatch.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.quotawatch.api.ApiKeys
import com.quotawatch.api.KeyStore
import com.quotawatch.api.QuotaFetcher
import com.quotawatch.api.QuotaSnapshot
import com.quotawatch.ble.BleClient
import com.quotawatch.wear.WearSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QuotaViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val TAG = "QuotaViewModel"
        const val AUTO_REFRESH_INTERVAL_MS = 5 * 60 * 1000L
    }

    val bleClient = BleClient(app)
    private val wearSync = WearSync(app)
    private val keyStore = KeyStore(app)

    // Fetcher is lazily recreated with Activity context when available
    private var _fetcher: QuotaFetcher? = null
    val fetcher: QuotaFetcher get() = _fetcher ?: QuotaFetcher(getApplication<Application>()).also { _fetcher = it }

    val apiKeys: StateFlow<ApiKeys> = keyStore.keys
        .stateIn(viewModelScope, SharingStarted.Eagerly, ApiKeys())

    private val _quotas = MutableStateFlow(QuotaSnapshot(emptyList()))
    val quotas: StateFlow<QuotaSnapshot> = _quotas

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val _autoRefreshEnabled = MutableStateFlow(true)
    val autoRefreshEnabled: StateFlow<Boolean> = _autoRefreshEnabled

    private var autoRefreshJob: Job? = null

    init {
        startAutoRefresh()
    }

    /** Call from Activity.onCreate to provide Activity context for WebView scraping. */
    fun setActivityContext(context: Context) {
        _fetcher = QuotaFetcher(context)
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

    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            delay(3000) // wait for Activity context + keys to load
            doRefresh()
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
            val snapshot = fetcher.fetchAll(apiKeys.value)
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
