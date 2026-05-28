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
        private val GITHUB_TOKEN = stringPreferencesKey("github_token")
    }

    val keys: Flow<ApiKeys> = context.dataStore.data.map { prefs ->
        ApiKeys(githubToken = prefs[GITHUB_TOKEN])
    }

    suspend fun save(keys: ApiKeys) {
        context.dataStore.edit { prefs ->
            if (keys.githubToken != null) prefs[GITHUB_TOKEN] = keys.githubToken
            else prefs.remove(GITHUB_TOKEN)
        }
    }
}
