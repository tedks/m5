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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QuotaViewModel(app: Application) : AndroidViewModel(app) {

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

    fun updateApiKeys(keys: ApiKeys) {
        viewModelScope.launch {
            keyStore.save(keys)
        }
    }

    fun connectBle() { bleClient.scan() }
    fun disconnectBle() { bleClient.disconnect() }

    fun refresh() {
        viewModelScope.launch {
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
            } catch (e: Exception) {
                Log.e("QuotaViewModel", "Refresh failed", e)
            } finally {
                _refreshing.value = false
            }
        }
    }
}
