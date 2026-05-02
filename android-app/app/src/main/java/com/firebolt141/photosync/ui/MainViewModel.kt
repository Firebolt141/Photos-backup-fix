package com.firebolt141.photosync.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.firebolt141.photosync.data.Prefs
import com.firebolt141.photosync.data.QueueItem
import com.firebolt141.photosync.repository.SyncRepository
import com.firebolt141.photosync.service.CopyService
import com.firebolt141.photosync.util.StorageHelper
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class UiState(
    val queue: List<QueueItem> = emptyList(),
    val pendingCount: Int = 0,
    val copiedCount: Int = 0,
    val driveUri: String? = null,
    val driveConnected: Boolean = false,
    val scanning: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo   = SyncRepository(app)
    private val prefs  = Prefs(app)
    private val _scanning = MutableStateFlow(false)

    val state: StateFlow<UiState> = combine(
        repo.queueFlow,
        repo.pendingCountFlow,
        repo.copiedCountFlow,
        prefs.driveUri,
        _scanning,
    ) { queue, pending, copied, driveUri, scanning ->
        UiState(
            queue          = queue,
            pendingCount   = pending,
            copiedCount    = copied,
            driveUri       = driveUri,
            driveConnected = StorageHelper.isDriveMounted(getApplication(), driveUri),
            scanning       = scanning,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun onDriveSelected(uri: Uri) {
        viewModelScope.launch {
            StorageHelper.takePersistablePermission(getApplication(), uri)
            prefs.saveDriveUri(uri.toString())
        }
    }

    fun startScan() {
        viewModelScope.launch {
            _scanning.value = true
            try { repo.scanMedia() } finally { _scanning.value = false }
        }
    }

    fun startCopy() {
        val intent = Intent(getApplication(), CopyService::class.java).apply {
            action = CopyService.ACTION_START
        }
        getApplication<Application>().startForegroundService(intent)
    }

    fun forgetDrive() {
        viewModelScope.launch { prefs.clearDriveUri() }
    }
}
