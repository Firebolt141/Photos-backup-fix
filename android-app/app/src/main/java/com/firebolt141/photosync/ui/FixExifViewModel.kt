package com.firebolt141.ubertrag.ui

import com.firebolt141.ubertrag.util.loc
import com.firebolt141.ubertrag.R
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.firebolt141.ubertrag.data.Prefs
import com.firebolt141.ubertrag.repository.SyncRepository
import com.firebolt141.ubertrag.service.KeepAlive
import com.firebolt141.ubertrag.util.ExifFixResult
import com.firebolt141.ubertrag.util.FixByFilenameResult
import com.firebolt141.ubertrag.util.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class FixExifResult {
    /** null = the drive wasn't connected */
    data class DriveMode(val r: ExifFixResult?) : FixExifResult()
    data class FilenameMode(val r: FixByFilenameResult) : FixExifResult()
}

data class FixExifUiState(
    /** true = "Sort a folder by date" (copy into year/month/day); false = fix the backup drive in place. */
    val filenameMode:  Boolean          = false,
    // drive mode
    val driveConnected: Boolean         = false,
    val driveUri:       String?         = null,
    val fixMismatched:  Boolean         = false,
    // filename mode folders + options
    val sourceUri:      String          = "",
    val sourceName:     String          = "",
    val outputUri:      String          = "",
    val outputName:     String          = "",
    val keepExistingDates: Boolean      = true,
    val renameToDate:   Boolean         = false,
    // operation
    val running:        Boolean         = false,
    val stopping:       Boolean         = false,
    val done:           Int             = 0,
    val total:          Int             = 0,
    val currentFile:    String          = "",
    val logLines:       List<String>    = emptyList(),
    val result:         FixExifResult?  = null,
    val error:          String          = "",
)

class FixExifViewModel(app: Application) : AndroidViewModel(app) {

    private val repo  = SyncRepository(app)
    private val prefs = Prefs(app)

    private val _state = MutableStateFlow(FixExifUiState())
    val state: StateFlow<FixExifUiState> = _state.asStateFlow()

    @Volatile private var cancel = false

    init {
        // Drive status, re-checked every few seconds off the main thread.
        viewModelScope.launch {
            prefs.driveUri.collect { uri -> _state.update { it.copy(driveUri = uri) } }
        }
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val connected = StorageHelper.isDriveMounted(app, _state.value.driveUri)
                _state.update { it.copy(driveConnected = connected) }
                delay(3_000)
            }
        }
        // Remembered folders from last time
        viewModelScope.launch {
            val src = prefs.folder("organize_source")
            val out = prefs.folder("organize_output")
            _state.update { s ->
                s.copy(
                    sourceUri = src ?: "", sourceName = StorageHelper.folderLabel(src),
                    outputUri = out ?: "", outputName = StorageHelper.folderLabel(out),
                )
            }
        }
    }

    fun setFilenameMode(on: Boolean) {
        if (_state.value.running) return
        _state.update { it.copy(filenameMode = on, result = null, logLines = emptyList(), error = "") }
    }

    fun setFixMismatched(on: Boolean) = _state.update { it.copy(fixMismatched = on) }
    fun setKeepExistingDates(on: Boolean) = _state.update { it.copy(keepExistingDates = on) }
    fun setRenameToDate(on: Boolean) = _state.update { it.copy(renameToDate = on) }

    fun onSourceSelected(uri: Uri) = pick(uri, "organize_source") { s, u, n -> s.copy(sourceUri = u, sourceName = n) }
    fun onOutputSelected(uri: Uri) = pick(uri, "organize_output") { s, u, n -> s.copy(outputUri = u, outputName = n) }

    private fun pick(uri: Uri, slot: String, apply: (FixExifUiState, String, String) -> FixExifUiState) {
        val str = uri.toString()
        _state.update { apply(it, str, StorageHelper.folderLabel(str)).copy(result = null, error = "") }
        viewModelScope.launch {
            if (StorageHelper.takePersistablePermission(getApplication(), uri)) prefs.saveFolder(slot, str)
        }
    }

    fun clearResult() {
        _state.update { it.copy(result = null, logLines = emptyList(), error = "") }
    }

    fun stop() {
        cancel = true
        _state.update { it.copy(stopping = true) }
    }

    fun startFix() {
        if (_state.value.running) return
        if (_state.value.filenameMode) startFilenameModeFix() else startDriveModeFix()
    }

    private fun begin(title: String) {
        cancel = false
        _state.update { it.copy(running = true, stopping = false, logLines = emptyList(), result = null, error = "", done = 0, total = 0, currentFile = "") }
        KeepAlive.begin(getApplication(), title)
    }

    private fun progress(current: Int, total: Int, name: String) {
        _state.update { it.copy(done = current, total = total, currentFile = name) }
        KeepAlive.update(getApplication(), name, current, total)
    }

    private fun startDriveModeFix() {
        val s = _state.value
        viewModelScope.launch {
            begin(str(R.string.ka_fixing))
            try {
                log(str(R.string.log_checking_drive, StorageHelper.folderLabel(s.driveUri, getApplication()).ifBlank { "?" }))
                val result = repo.fixMissingExif(
                    fixMismatched = s.fixMismatched,
                    onProgress    = ::progress,
                    onLog         = ::log,
                    isCancelled   = { cancel },
                )
                log("─────────────────────────────────────")
                when {
                    result == null -> log(str(R.string.log_drive_not_connected))
                    result.fixed + result.alreadyHasDate + result.skipped + result.failed == 0 -> {
                        log(str(R.string.log_no_dated_photos))
                        log(str(R.string.log_expected_layout))
                        log(str(R.string.log_year_folder_ok))
                    }
                    else -> {
                        log(str(R.string.log_dates_written, result.fixed))
                        if (result.corrected > 0) log(str(R.string.log_corrected, result.corrected))
                        log(str(R.string.log_already_dated, result.alreadyHasDate))
                        if (result.mismatched > result.corrected) log(str(R.string.log_mismatch, result.mismatched - result.corrected))
                        if (result.skipped > 0) log(str(R.string.log_cant_store, result.skipped))
                        if (result.failed > 0) log(str(R.string.log_errors, result.failed))
                    }
                }
                if (cancel) log(str(R.string.log_stopped_by_you))
                _state.update { it.copy(result = FixExifResult.DriveMode(result)) }
            } catch (e: Exception) {
                log("✗ ${e.message}")
                _state.update { it.copy(error = e.message ?: str(R.string.something_wrong)) }
            } finally {
                KeepAlive.end()
                _state.update { it.copy(running = false, stopping = false) }
            }
        }
    }

    private fun startFilenameModeFix() {
        val s = _state.value
        if (s.sourceUri.isBlank() || s.outputUri.isBlank()) return
        viewModelScope.launch {
            begin(str(R.string.ka_sorting))
            try {
                val result = repo.fixByFilename(
                    sourceUri  = s.sourceUri,
                    outputUri  = s.outputUri,
                    keepExistingDates = s.keepExistingDates,
                    renameToDate = s.renameToDate,
                    onLog      = ::log,
                    onProgress = ::progress,
                    isCancelled = { cancel },
                )
                if (result.errorMsg.isNotBlank()) {
                    _state.update { it.copy(error = result.errorMsg) }
                } else {
                    log("─────────────────────────────────────")
                    log(str(R.string.log_sorted, result.copied - result.noDate))
                    if (result.exifWritten > 0) log(str(R.string.log_dates_written2, result.exifWritten))
                    if (result.noDate > 0) log(str(R.string.log_no_date, result.noDate))
                    if (result.alreadyExists > 0) log(str(R.string.log_already_there, result.alreadyExists))
                    if (result.failed > 0) log(str(R.string.log_errors_dir, result.failed))
                    _state.update { it.copy(result = FixExifResult.FilenameMode(result)) }
                }
            } catch (e: Exception) {
                log("✗ ${e.message}")
                _state.update { it.copy(error = e.message ?: str(R.string.something_wrong)) }
            } finally {
                KeepAlive.end()
                _state.update { it.copy(running = false, stopping = false) }
            }
        }
    }

    /** Text in the app's chosen language (log lines are written once, as they happen). */
    private fun str(id: Int, vararg args: Any): String = getApplication<Application>().loc().getString(id, *args)

    private fun log(msg: String) {
        _state.update { s -> s.copy(logLines = (s.logLines + msg).takeLast(500)) }
    }
}
