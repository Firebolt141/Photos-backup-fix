package com.firebolt141.ubertrag.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.firebolt141.ubertrag.repository.SyncRepository
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
    val skipIfHasExif: Boolean        = true,
    val running:       Boolean        = false,
    val done:          Int            = 0,
    val total:         Int            = 0,
    val currentFile:   String         = "",
    val result:        TakeoutResult? = null,
)

class TakeoutViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SyncRepository(app)

    private val _state = MutableStateFlow(TakeoutUiState())
    val state: StateFlow<TakeoutUiState> = _state.asStateFlow()

    fun onSourceSelected(uri: Uri) {
        val name = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast(':')
            ?: uri.toString()
        _state.update { it.copy(sourceUri = uri.toString(), sourceName = name, result = null) }
    }

    fun setSkipIfHasExif(value: Boolean) {
        _state.update { it.copy(skipIfHasExif = value) }
    }

    fun startProcessing() {
        val srcUri = _state.value.sourceUri.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            _state.update { it.copy(running = true, done = 0, total = 0, currentFile = "", result = null) }
            val result = repo.processTakeout(
                sourceUri = srcUri,
                options   = TakeoutOptions(skipIfHasExif = _state.value.skipIfHasExif),
            ) { done, total, name ->
                _state.update { it.copy(done = done, total = total, currentFile = name) }
            }
            _state.update { it.copy(running = false, result = result) }
        }
    }

    fun clearResult() {
        _state.update { it.copy(result = null) }
    }
}
