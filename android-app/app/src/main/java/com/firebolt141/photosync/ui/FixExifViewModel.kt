package com.firebolt141.ubertrag.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.firebolt141.ubertrag.data.Prefs
import com.firebolt141.ubertrag.repository.SyncRepository
import com.firebolt141.ubertrag.util.ExifFixResult
import com.firebolt141.ubertrag.util.FixByFilenameResult
import com.firebolt141.ubertrag.util.StorageHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class FixExifResult {
    data class DriveMode(val r: ExifFixResult) : FixExifResult()
    data class FilenameMode(val r: FixByFilenameResult) : FixExifResult()
}

data class FixExifUiState(
    val filenameMode:  Boolean          = false,
    // drive mode
    val driveConnected: Boolean         = false,
    val driveUri:       String?         = null,
    // filename mode folders
    val sourceUri:      String          = "",
    val sourceName:     String          = "",
    val outputUri:      String          = "",
    val outputName:     String          = "",
    // operation
    val running:        Boolean         = false,
    val done:           Int             = 0,
    val total:          Int             = 0,
    val currentFile:    String          = "",
    val logLines:       List<String>    = emptyList(),
    val result:         FixExifResult?  = null,
)

class FixExifViewModel(app: Application) : AndroidViewModel(app) {

    private val repo  = SyncRepository(app)
    private val prefs = Prefs(app)

    private val _state = MutableStateFlow(FixExifUiState())
    val state: StateFlow<FixExifUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            prefs.driveUri.collect { uri ->
                _state.update { it.copy(
                    driveUri       = uri,
                    driveConnected = StorageHelper.isDriveMounted(app, uri),
                )}
            }
        }
    }

    fun setFilenameMode(on: Boolean) {
        _state.update { it.copy(filenameMode = on, result = null, logLines = emptyList()) }
    }

    fun onSourceSelected(uri: Uri) {
        _state.update { it.copy(sourceUri = uri.toString(), sourceName = label(uri), result = null) }
    }

    fun onOutputSelected(uri: Uri) {
        _state.update { it.copy(outputUri = uri.toString(), outputName = label(uri), result = null) }
    }

    fun clearResult() {
        _state.update { it.copy(result = null, logLines = emptyList()) }
    }

    fun startFix() {
        if (_state.value.filenameMode) startFilenameModeFix()
        else startDriveModeFix()
    }

    private fun startDriveModeFix() {
        viewModelScope.launch {
            val driveLabel = _state.value.driveUri
                ?.let { android.net.Uri.parse(it) }?.let { label(it) } ?: "unknown"
            _state.update { it.copy(running = true, logLines = emptyList(), result = null, done = 0, total = 0) }
            log("Scanning drive: $driveLabel")
            val result = repo.fixMissingExif { current, total, name ->
                _state.update { it.copy(done = current, total = total, currentFile = name) }
                if (current == 1 || current % 10 == 0 || total <= 20) log("[$current/$total] $name")
            }
            log("─────────────────────────────────────")
            val noneFound = result.fixed == 0 && result.alreadyHasDate == 0 &&
                            result.skipped == 0 && result.failed == 0
            if (noneFound) {
                log("⚠ No media files found.")
                log("  Selected folder: \"$driveLabel\"")
                log("  Supported structures:")
                log("    drive-root / 2024 / January / January_07 / photo.jpg")
                log("    2024 / January / January_07 / photo.jpg  (year folder)")
                log("  A month folder selected directly is not supported.")
            } else {
                log("Fixed:          ${result.fixed}")
                log("Already dated:  ${result.alreadyHasDate}")
                log("Skipped (HEIC/video): ${result.skipped}")
                if (result.failed > 0) log("Errors:         ${result.failed}")
            }
            _state.update { it.copy(running = false, result = FixExifResult.DriveMode(result)) }
        }
    }

    private fun startFilenameModeFix() {
        val s = _state.value
        if (s.sourceUri.isBlank() || s.outputUri.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(running = true, logLines = emptyList(), result = null, done = 0, total = 0) }
            if (s.sourceUri == s.outputUri) {
                log("✗ Source and output folders must be different")
                _state.update { it.copy(running = false) }
                return@launch
            }
            log("Scanning source folder…")
            val result = repo.fixByFilename(
                sourceUri  = s.sourceUri,
                outputUri  = s.outputUri,
                onLog      = { msg -> log(msg) },
                onProgress = { current, total, name ->
                    _state.update { it.copy(done = current, total = total, currentFile = name) }
                },
            )
            log("─────────────────────────────────────")
            log("Copied:            ${result.copied}")
            log("EXIF written:      ${result.exifWritten}")
            if (result.noDate       > 0) log("No date (→ no-date/):  ${result.noDate}")
            if (result.alreadyExists > 0) log("Already exists:        ${result.alreadyExists}")
            if (result.unsupported  > 0) log("Copied (no EXIF — HEIC/video): ${result.unsupported}")
            if (result.failed       > 0) log("Errors (→ error/):     ${result.failed}")
            _state.update { it.copy(running = false, result = FixExifResult.FilenameMode(result)) }
        }
    }

    private fun log(msg: String) {
        _state.update { s -> s.copy(logLines = (s.logLines + msg).takeLast(500)) }
    }

    private fun label(uri: Uri) =
        uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: uri.toString()
}
