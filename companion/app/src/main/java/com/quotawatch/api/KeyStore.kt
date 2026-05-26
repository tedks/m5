package com.quotawatch.api

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "api_keys")

class KeyStore(private val context: Context) {

    companion object {
        private val CLAUDE_OAUTH_TOKEN = stringPreferencesKey("claude_oauth_token")
        private val GITHUB_TOKEN = stringPreferencesKey("github_token")
    }

    val keys: Flow<ApiKeys> = context.dataStore.data.map { prefs ->
        ApiKeys(
            claudeOAuthToken = prefs[CLAUDE_OAUTH_TOKEN],
            githubToken = prefs[GITHUB_TOKEN]
        )
    }

    suspend fun save(keys: ApiKeys) {
        context.dataStore.edit { prefs ->
            setOrRemove(prefs, CLAUDE_OAUTH_TOKEN, keys.claudeOAuthToken)
            setOrRemove(prefs, GITHUB_TOKEN, keys.githubToken)
        }
    }

    private fun setOrRemove(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        key: Preferences.Key<String>,
        value: String?
    ) {
        if (value != null) prefs[key] = value else prefs.remove(key)
    }
}
