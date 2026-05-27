package com.quotawatch.ui

import android.app.Application
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
        const val AUTO_REFRESH_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
    }

    val bleClient = BleClient(app)
    private val wearSync = WearSync(app)
    private val fetcher = QuotaFetcher()
    private val keyStore = KeyStore(app)

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

    fun updateApiKeys(keys: ApiKeys) {
        viewModelScope.launch {
            keyStore.save(keys)
        }
    }

    fun connectBle() { bleClient.scan() }
    fun disconnectBle() { bleClient.disconnect() }

    fun toggleAutoRefresh() {
        _autoRefreshEnabled.value = !_autoRefreshEnabled.value
        if (_autoRefreshEnabled.value) {
            startAutoRefresh()
        } else {
            autoRefreshJob?.cancel()
            autoRefreshJob = null
        }
    }

    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            // Initial fetch on startup (short delay for keys to load from DataStore)
            delay(1000)
            doRefresh()

            while (true) {
                delay(AUTO_REFRESH_INTERVAL_MS)
                if (_autoRefreshEnabled.value) {
                    doRefresh()
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { doRefresh() }
    }

    private suspend fun doRefresh() {
        if (_refreshing.value) return // skip if already refreshing
        _refreshing.value = true
        try {
            val snapshot = withContext(Dispatchers.IO) {
                fetcher.fetchAll(apiKeys.value)
            }
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
