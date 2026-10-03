package com.firebolt141.ubertrag.ui

import com.firebolt141.ubertrag.util.loc
import com.firebolt141.ubertrag.R
import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.firebolt141.ubertrag.data.CopyStatus
import com.firebolt141.ubertrag.data.Prefs
import com.firebolt141.ubertrag.data.QueueItem
import com.firebolt141.ubertrag.repository.CopySummary
import com.firebolt141.ubertrag.repository.SyncRepository
import com.firebolt141.ubertrag.service.CopyProgress
import com.firebolt141.ubertrag.service.CopyService
import com.firebolt141.ubertrag.service.KeepAlive
import com.firebolt141.ubertrag.util.PhotoLogic
import com.firebolt141.ubertrag.util.RenameResult
import com.firebolt141.ubertrag.util.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.ZoneId

/** How much of the gallery the app may read. */
enum class MediaAccess { FULL, PARTIAL, NONE }

/** State of the Rename Drive Folders screen. */
data class RenameUi(
    val running: Boolean = false,
    val status: UiText = UiText.None,
    val preview: RenameResult? = null,   // result of "Check" (nothing changed yet)
    val result: RenameResult? = null,    // result of the real run
)

data class UiState(
    val queue: List<QueueItem>  = emptyList(),
    val pendingCount: Int       = 0,
    val copiedCount: Int        = 0,
    val skippedCount: Int       = 0,
    val failedCount: Int        = 0,
    val driveUri: String?       = null,
    val driveLabel: String      = "",
    val driveConnected: Boolean = false,
    val scanning: Boolean       = false,
    val scanMessage: UiText     = UiText.None,
    val fromDateMs: Long        = 0L,
    val toDateMs: Long          = 0L,
    val copyProgress: CopyProgress? = null,
    val lastSummary: CopySummary? = null,
    val mediaAccess: MediaAccess = MediaAccess.FULL,
    val rename: RenameUi        = RenameUi(),
    val message: UiText         = UiText.None,
) {
    // Kept for older callers
    val renaming: Boolean get() = rename.running
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo      = SyncRepository(app)
    private val prefs     = Prefs(app)
    private val _scan     = MutableStateFlow<Pair<Boolean, UiText>>(false to UiText.None)
    private val _renameUi = MutableStateFlow(RenameUi())
    private val _access   = MutableStateFlow(checkAccess())
    private val _message  = MutableStateFlow<UiText>(UiText.None)

    private val _dateRange = combine(prefs.fromDateMs, prefs.toDateMs) { f, t -> f to t }

    /** Drive URI + whether it is plugged in, re-checked every few seconds off the main thread. */
    private val _drive: Flow<Pair<String?, Boolean>> = prefs.driveUri.flatMapLatest { uri ->
        flow {
            while (true) {
                emit(uri to StorageHelper.isDriveMounted(getApplication(), uri))
                if (uri == null) break
                delay(3_000)
            }
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    private val _copy = combine(CopyService.copyProgress, CopyService.lastSummary) { p, s -> p to s }

    private val _baseState = combine(
        repo.queueFlow,
        _drive,
        _scan,
        _dateRange,
        _copy,
    ) { queue, (driveUri, connected), (scanning, scanMsg), (fromMs, toMs), (progress, summary) ->
        // Add a day so the end date is inclusive: the picker returns midnight UTC
        // of the selected day. Queue dates are wall-clock time encoded as UTC ms.
        val effectiveTo = if (toMs > 0) toMs + 86_400_000L - 1 else Long.MAX_VALUE
        val zone = ZoneId.systemDefault()
        val filtered = if (fromMs == 0L && effectiveTo == Long.MAX_VALUE) queue
        else queue.filter { item ->
            (item.dateTaken ?: PhotoLogic.wallMs(item.dateAdded, zone)) in fromMs..effectiveTo
        }

        UiState(
            queue          = filtered,
            pendingCount   = filtered.count { it.status == CopyStatus.PENDING || it.status == CopyStatus.FAILED },
            copiedCount    = filtered.count { it.status == CopyStatus.COPIED },
            skippedCount   = filtered.count { it.status == CopyStatus.SKIPPED },
            failedCount    = filtered.count { it.status == CopyStatus.FAILED },
            driveUri       = driveUri,
            driveLabel     = StorageHelper.folderLabel(driveUri),
            driveConnected = connected,
            scanning       = scanning,
            scanMessage    = scanMsg,
            fromDateMs     = fromMs,
            toDateMs       = toMs,
            copyProgress   = progress,
            lastSummary    = summary,
        )
    }.flowOn(Dispatchers.Default)

    val state: StateFlow<UiState> = combine(_baseState, _renameUi, _access, _message) { base, rename, access, msg ->
        base.copy(rename = rename, mediaAccess = access, message = msg)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState(mediaAccess = _access.value))

    // ── permissions ──────────────────────────────────────────────────────

    /** Call when returning to the app (the user may have changed permissions in Settings). */
    fun refreshAccess() { _access.value = checkAccess() }

    private fun checkAccess(): MediaAccess {
        val ctx = getApplication<Application>()
        fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                has(Manifest.permission.READ_MEDIA_IMAGES) && has(Manifest.permission.READ_MEDIA_VIDEO) -> MediaAccess.FULL
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                (has(Manifest.permission.READ_MEDIA_IMAGES) || has(Manifest.permission.READ_MEDIA_VIDEO)) -> MediaAccess.PARTIAL
            Build.VERSION.SDK_INT >= 34 && has("android.permission.READ_MEDIA_VISUAL_USER_SELECTED") -> MediaAccess.PARTIAL
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
                has(Manifest.permission.READ_EXTERNAL_STORAGE) -> MediaAccess.FULL
            else -> MediaAccess.NONE
        }
    }

    // ── drive ────────────────────────────────────────────────────────────

    fun onDriveSelected(uri: Uri) {
        viewModelScope.launch {
            if (!StorageHelper.takePersistablePermission(getApplication(), uri)) {
                say(UiText.Res(R.string.msg_keep_access_denied))
                return@launch
            }
            prefs.saveDriveUri(uri.toString())
            say(UiText.None)
        }
    }

    fun forgetDrive() {
        viewModelScope.launch { prefs.clearDriveUri() }
    }

    // ── scan / copy ──────────────────────────────────────────────────────

    fun startScan() {
        if (_scan.value.first) return
        refreshAccess()
        if (_access.value == MediaAccess.NONE) {
            _scan.value = false to UiText.Res(R.string.scan_allow_first)
            return
        }
        viewModelScope.launch {
            _scan.value = true to UiText.Res(R.string.scan_looking)
            val msg = try {
                val n = repo.scanMedia()
                if (n == 0) UiText.Res(R.string.scan_none_new) else UiText.Plural(R.plurals.scan_found_new, n, n)
            } catch (e: SecurityException) {
                refreshAccess()
                UiText.Res(R.string.scan_allow_first)
            } catch (e: Exception) {
                UiText.Res(R.string.scan_failed, e.message ?: e.javaClass.simpleName)
            }
            _scan.value = false to msg
        }
    }

    fun startCopy() {
        val s = state.value
        CopyService.lastSummary.value = null
        val intent = Intent(getApplication(), CopyService::class.java).apply {
            action = CopyService.ACTION_START
            putExtra(CopyService.EXTRA_FROM_MS, s.fromDateMs)
            putExtra(CopyService.EXTRA_TO_MS,   s.toDateMs)
        }
        try {
            getApplication<Application>().startForegroundService(intent)
        } catch (e: Exception) {
            say(UiText.Res(R.string.copy_couldnt_start, e.message ?: e.javaClass.simpleName))
        }
    }

    fun stopCopy() { CopyService.stopRequested = true }

    fun dismissSummary() { CopyService.lastSummary.value = null }

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

    fun requeueAll() {
        viewModelScope.launch {
            repo.requeueAll()
            say(UiText.Res(R.string.requeued_all))
        }
    }

    fun dismissMessage() = say(UiText.None)

    private fun say(msg: UiText) { _message.value = msg }

    // ── rename legacy folders ────────────────────────────────────────────

    /** [dryRun] = only list what would change. */
    fun renameOldFolders(dryRun: Boolean = false) {
        if (_renameUi.value.running) return
        viewModelScope.launch {
            _renameUi.value = _renameUi.value.copy(running = true, status = UiText.Res(R.string.starting), result = null,
                preview = if (dryRun) null else _renameUi.value.preview)
            if (!dryRun) KeepAlive.begin(getApplication(), getApplication<Application>().loc().getString(R.string.ka_renaming))
            val r = try {
                repo.renameLegacyFolders(dryRun) { path ->
                    _renameUi.value = _renameUi.value.copy(status = UiText.Raw(path))
                    if (!dryRun) KeepAlive.update(getApplication(), path, 0, 0)
                }
            } catch (e: Exception) {
                RenameResult(errors = 1, problems = listOf(e.message ?: getApplication<Application>().loc().getString(R.string.rename_failed)))
            } finally {
                if (!dryRun) KeepAlive.end()
            }
            val status = when {
                r == null -> UiText.Res(R.string.drive_not_connected_short)
                dryRun && r.changes.isEmpty() -> UiText.Res(R.string.rename_nothing_dry)
                dryRun -> UiText.Plural(R.plurals.rename_to_update, r.changes.size, r.changes.size)
                r.renamed + r.merged == 0 && r.errors == 0 -> UiText.Res(R.string.rename_nothing)
                r.errors == 0 && r.merged == 0 -> UiText.Res(R.string.rename_done, r.renamed)
                r.errors == 0 -> UiText.Res(R.string.rename_done_merged, r.renamed, r.merged)
                else -> UiText.Plural(R.plurals.rename_done_problems, r.errors, r.errors, r.renamed, r.merged)
            }
            _renameUi.value = RenameUi(
                running = false, status = status,
                preview = if (dryRun) r else null,
                result  = if (dryRun) null else r,
            )
        }
    }
}
