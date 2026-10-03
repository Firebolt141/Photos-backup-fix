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
import com.firebolt141.ubertrag.util.StorageHelper
import com.firebolt141.ubertrag.util.TakeoutOptions
import com.firebolt141.ubertrag.util.TakeoutResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TakeoutUiState(
    val sourceUri:     String         = "",
    val sourceName:    String         = "",
    val outputUri:     String         = "",
    val outputName:    String         = "",
    val skipIfHasExif: Boolean        = true,
    val renameToDate:  Boolean        = false,
    val running:       Boolean        = false,
    val stopping:      Boolean        = false,
    val done:          Int            = 0,
    val total:         Int            = 0,
    val currentFile:   String         = "",
    val logLines:      List<String>   = emptyList(),
    val result:        TakeoutResult? = null,
)

class TakeoutViewModel(app: Application) : AndroidViewModel(app) {

    private val repo  = SyncRepository(app)
    private val prefs = Prefs(app)

    private val _state = MutableStateFlow(TakeoutUiState())
    val state: StateFlow<TakeoutUiState> = _state.asStateFlow()

    @Volatile private var cancel = false

    init {
        viewModelScope.launch {
            val src = prefs.folder("takeout_source")
            val out = prefs.folder("takeout_output")
            _state.update { s ->
                s.copy(
                    sourceUri = src ?: "", sourceName = StorageHelper.folderLabel(src),
                    outputUri = out ?: "", outputName = StorageHelper.folderLabel(out),
                )
            }
        }
    }

    fun onSourceSelected(uri: Uri) {
        val str = uri.toString()
        _state.update { it.copy(sourceUri = str, sourceName = StorageHelper.folderLabel(str), result = null) }
        rememberFolder(uri, "takeout_source")
    }

    fun onOutputSelected(uri: Uri) {
        val str = uri.toString()
        _state.update { it.copy(outputUri = str, outputName = StorageHelper.folderLabel(str), result = null) }
        rememberFolder(uri, "takeout_output")
    }

    private fun rememberFolder(uri: Uri, slot: String) {
        viewModelScope.launch {
            if (StorageHelper.takePersistablePermission(getApplication(), uri)) prefs.saveFolder(slot, uri.toString())
        }
    }

    fun setSkipIfHasExif(value: Boolean) = _state.update { it.copy(skipIfHasExif = value) }
    fun setRenameToDate(value: Boolean) = _state.update { it.copy(renameToDate = value) }

    fun stop() {
        cancel = true
        _state.update { it.copy(stopping = true) }
    }

    fun startProcessing() {
        val s = _state.value
        if (s.running) return
        val srcUri = s.sourceUri.takeIf { it.isNotBlank() } ?: return
        val outUri = s.outputUri.takeIf { it.isNotBlank() } ?: return
        cancel = false
        viewModelScope.launch {
            _state.update { it.copy(running = true, stopping = false, done = 0, total = 0, currentFile = "", result = null, logLines = emptyList()) }
            KeepAlive.begin(getApplication(), getApplication<Application>().loc().getString(R.string.ka_importing))
            val result = try {
                repo.processTakeout(
                    sourceUri   = srcUri,
                    outputUri   = outUri,
                    options     = TakeoutOptions(skipIfHasExif = s.skipIfHasExif, renameToDate = s.renameToDate),
                    onProgress  = { done, total, name ->
                        _state.update { it.copy(done = done, total = total, currentFile = name) }
                        KeepAlive.update(getApplication(), name, done, total)
                    },
                    onLog       = ::log,
                    isCancelled = { cancel },
                )
            } catch (e: Exception) {
                TakeoutResult(errorMsg = e.message ?: getApplication<Application>().loc().getString(R.string.something_wrong))
            } finally {
                KeepAlive.end()
            }
            _state.update { it.copy(running = false, stopping = false, result = result) }
        }
    }

    fun clearResult() {
        _state.update { it.copy(result = null, logLines = emptyList()) }
    }

    private fun log(msg: String) {
        _state.update { s -> s.copy(logLines = (s.logLines + msg).takeLast(500)) }
    }
}
