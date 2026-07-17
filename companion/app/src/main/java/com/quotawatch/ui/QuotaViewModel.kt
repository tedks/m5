package com.quotawatch.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.quotawatch.QuotaWatchApp
import com.quotawatch.api.ApiKeys

/**
 * Thin UI adapter over the app-scoped [com.quotawatch.data.QuotaRepository]. All state and the
 * fetch pipeline live in the repository so they outlive this ViewModel (and every Activity); the
 * ViewModel just forwards the surface the Compose UI already consumed.
 */
class QuotaViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as QuotaWatchApp).repository

    val bleClient = repository.bleClient
    val fetcher = repository.fetcher

    val apiKeys = repository.apiKeys
    val quotas = repository.quotas
    val refreshing = repository.refreshing
    val autoRefreshEnabled = repository.autoRefreshEnabled
    val claudeLoginStatus = repository.claudeLoginStatus
    val codexLoginStatus = repository.codexLoginStatus

    /** Call from Activity.onCreate to provide Activity context for WebView scraping. */
    fun setActivityContext(context: Context) = repository.setActivityContext(context)

    fun updateApiKeys(keys: ApiKeys) = repository.updateApiKeys(keys)

    fun connectBle() = repository.connectBle()
    fun disconnectBle() = repository.disconnectBle()

    fun toggleAutoRefresh() = repository.toggleAutoRefresh()

    fun refresh() = repository.refresh()

    /** Call when the login WebView's "Done" is tapped — see QuotaRepository.onLoginDone. */
    fun onLoginDone(url: String) = repository.onLoginDone(url)
}
