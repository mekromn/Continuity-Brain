package com.mekromn.continuitybrain.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mekromn.continuitybrain.ContinuityBrainApplication
import com.mekromn.continuitybrain.bridge.BrainBridgeService
import com.mekromn.continuitybrain.bridge.BridgeTokenStore
import com.mekromn.continuitybrain.bridge.LocalBrainServer
import com.mekromn.continuitybrain.model.BrainStats
import com.mekromn.continuitybrain.model.ImportProgress
import com.mekromn.continuitybrain.model.ImportSummary
import com.mekromn.continuitybrain.model.ProjectSummary
import com.mekromn.continuitybrain.model.SearchHit
import com.mekromn.continuitybrain.model.TimelineItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BrainUiState(
    val loading: Boolean = true,
    val importing: Boolean = false,
    val importProgress: ImportProgress? = null,
    val importSummary: ImportSummary? = null,
    val stats: BrainStats = BrainStats(),
    val projects: List<ProjectSummary> = emptyList(),
    val timeline: List<TimelineItem> = emptyList(),
    val query: String = "",
    val searchHits: List<SearchHit> = emptyList(),
    val bridgeEnabled: Boolean = false,
    val bridgeToken: String = "",
    val bridgePort: Int = LocalBrainServer.DEFAULT_PORT,
    val error: String? = null,
)

class BrainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ContinuityBrainApplication
    private val repository = app.repository
    private val tokenStore = BridgeTokenStore(repository)
    private val _state = MutableStateFlow(BrainUiState())
    val state: StateFlow<BrainUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                Triple(repository.stats(), repository.listProjects(), repository.timeline(120))
            }
            _state.update {
                it.copy(
                    loading = false,
                    stats = snapshot.first,
                    projects = snapshot.second,
                    timeline = snapshot.third,
                    bridgeEnabled = repository.getEncryptedSetting(BrainBridgeService.SETTING_ENABLED) == "true",
                    bridgeToken = tokenStore.token(),
                )
            }
        }
    }

    fun importExport(uri: Uri) {
        if (_state.value.importing) return
        _state.update {
            it.copy(
                importing = true,
                importProgress = ImportProgress("Opening export"),
                importSummary = null,
                error = null,
            )
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    app.importer.importUri(uri) { progress ->
                        _state.update { current -> current.copy(importProgress = progress) }
                    }
                }
            }.onSuccess { summary ->
                _state.update { it.copy(importing = false, importSummary = summary, error = null) }
                refresh()
            }.onFailure { failure ->
                _state.update {
                    it.copy(
                        importing = false,
                        error = failure.message ?: failure.javaClass.simpleName,
                    )
                }
            }
        }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun search(query: String = _state.value.query) {
        val cleaned = query.trim()
        _state.update { it.copy(query = cleaned, error = null) }
        if (cleaned.isBlank()) {
            _state.update { it.copy(searchHits = emptyList()) }
            return
        }
        viewModelScope.launch {
            val hits = withContext(Dispatchers.IO) { repository.search(cleaned, 80) }
            _state.update { it.copy(searchHits = hits) }
        }
    }

    fun setBridgeEnabled(enabled: Boolean) {
        val context = getApplication<Application>()
        if (enabled) {
            context.startForegroundService(BrainBridgeService.startIntent(context))
        } else {
            context.startService(BrainBridgeService.stopIntent(context))
        }
        repository.setEncryptedSetting(BrainBridgeService.SETTING_ENABLED, enabled.toString())
        _state.update { it.copy(bridgeEnabled = enabled, bridgeToken = tokenStore.token()) }
    }

    fun rotateBridgeToken() {
        val token = tokenStore.rotate()
        _state.update { it.copy(bridgeToken = token) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
