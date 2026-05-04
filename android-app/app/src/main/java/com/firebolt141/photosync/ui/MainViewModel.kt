package com.firebolt141.ubertrag.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.firebolt141.ubertrag.data.CopyStatus
import com.firebolt141.ubertrag.data.Prefs
import com.firebolt141.ubertrag.data.QueueItem
import com.firebolt141.ubertrag.repository.SyncRepository
import com.firebolt141.ubertrag.service.CopyProgress
import com.firebolt141.ubertrag.service.CopyService
import com.firebolt141.ubertrag.util.StorageHelper
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class UiState(
    val queue: List<QueueItem>  = emptyList(),
    val pendingCount: Int       = 0,
    val copiedCount: Int        = 0,
    val skippedCount: Int       = 0,
    val failedCount: Int        = 0,
    val driveUri: String?       = null,
    val driveConnected: Boolean = false,
    val scanning: Boolean       = false,
    val fromDateMs: Long        = 0L,
    val toDateMs: Long          = 0L,
    val copyProgress: CopyProgress? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo   = SyncRepository(app)
    private val prefs  = Prefs(app)
    private val _scanning = MutableStateFlow(false)

    private val _dateRange = combine(prefs.fromDateMs, prefs.toDateMs) { f, t -> f to t }

    val state: StateFlow<UiState> = combine(
        repo.queueFlow,
        prefs.driveUri,
        _scanning,
        _dateRange,
        CopyService.copyProgress,
    ) { queue, driveUri, scanning, dateRange, progress ->
        val (fromMs, toMs) = dateRange
        val effectiveTo    = if (toMs > 0) toMs else Long.MAX_VALUE

        val filtered = if (fromMs == 0L && effectiveTo == Long.MAX_VALUE) queue
        else queue.filter { item ->
            val ts = item.dateTaken ?: item.dateAdded
            ts in fromMs..effectiveTo
        }

        UiState(
            queue          = filtered,
            pendingCount   = filtered.count { it.status == CopyStatus.PENDING },
            copiedCount    = filtered.count { it.status == CopyStatus.COPIED },
            skippedCount   = filtered.count { it.status == CopyStatus.SKIPPED },
            failedCount    = filtered.count { it.status == CopyStatus.FAILED },
            driveUri       = driveUri,
            driveConnected = StorageHelper.isDriveMounted(getApplication(), driveUri),
            scanning       = scanning,
            fromDateMs     = fromMs,
            toDateMs       = toMs,
            copyProgress   = progress,
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
        val s = state.value
        val intent = Intent(getApplication(), CopyService::class.java).apply {
            action = CopyService.ACTION_START
            putExtra(CopyService.EXTRA_FROM_MS, s.fromDateMs)
            putExtra(CopyService.EXTRA_TO_MS,   s.toDateMs)
        }
        getApplication<Application>().startForegroundService(intent)
    }

    fun forgetDrive() {
        viewModelScope.launch { prefs.clearDriveUri() }
    }

    fun setDateRange(fromMs: Long, toMs: Long) {
        viewModelScope.launch { prefs.saveDateRange(fromMs, toMs) }
    }

    fun clearDateRange() {
        viewModelScope.launch { prefs.clearDateRange() }
    }

    fun retryFailed() {
        viewModelScope.launch { repo.retryFailed() }
    }

    fun clearCopied() {
        viewModelScope.launch { repo.clearCopied() }
    }
}
