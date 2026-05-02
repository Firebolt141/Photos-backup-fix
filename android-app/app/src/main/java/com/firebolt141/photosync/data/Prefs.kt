package com.firebolt141.photosync.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "photosync_prefs")

class Prefs(private val context: Context) {
    companion object {
        private val KEY_DRIVE_URI = stringPreferencesKey("drive_uri")
        private val KEY_FROM_MS   = longPreferencesKey("from_date_ms")
        private val KEY_TO_MS     = longPreferencesKey("to_date_ms")
    }

    val driveUri:   Flow<String?> = context.dataStore.data.map { it[KEY_DRIVE_URI] }
    val fromDateMs: Flow<Long>    = context.dataStore.data.map { it[KEY_FROM_MS] ?: 0L }
    val toDateMs:   Flow<Long>    = context.dataStore.data.map { it[KEY_TO_MS]   ?: 0L }

    suspend fun saveDriveUri(uri: String) {
        context.dataStore.edit { it[KEY_DRIVE_URI] = uri }
    }

    suspend fun clearDriveUri() {
        context.dataStore.edit { it.remove(KEY_DRIVE_URI) }
    }

    suspend fun saveDateRange(fromMs: Long, toMs: Long) {
        context.dataStore.edit { it[KEY_FROM_MS] = fromMs; it[KEY_TO_MS] = toMs }
    }

    suspend fun clearDateRange() {
        context.dataStore.edit { it.remove(KEY_FROM_MS); it.remove(KEY_TO_MS) }
    }
}
