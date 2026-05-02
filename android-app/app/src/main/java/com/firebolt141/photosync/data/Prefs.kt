package com.firebolt141.photosync.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "photosync_prefs")

class Prefs(private val context: Context) {
    companion object {
        private val KEY_DRIVE_URI = stringPreferencesKey("drive_uri")
    }

    val driveUri: Flow<String?> = context.dataStore.data.map { it[KEY_DRIVE_URI] }

    suspend fun saveDriveUri(uri: String) {
        context.dataStore.edit { it[KEY_DRIVE_URI] = uri }
    }

    suspend fun clearDriveUri() {
        context.dataStore.edit { it.remove(KEY_DRIVE_URI) }
    }
}
