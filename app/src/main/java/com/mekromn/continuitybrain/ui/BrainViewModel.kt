package com.mekromn.continuitybrain.ui

import android.app.Application
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
    val vaultBusy: Boolean = false,
    val vaultStatus: String? = null,
    val semanticReady: Boolean = false,
    val semanticModelHash: String? = null,
    val semanticDimensions: Int = 0,
    val semanticIndexed: Int = 0,
    val semanticPending: Int = 0,
    val semanticIndexing: Boolean = false,
    val semanticStatus: String? = null,
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
                val model = app.semanticIndex.modelInfo()
                RefreshSnapshot(
                    stats = repository.stats(),
                    projects = repository.listProjects(),
                    timeline = repository.timeline(120),
                    modelHash = model?.hash,
                    modelDimensions = model?.dimensions ?: 0,
                    semanticIndexed = if (model == null) 0 else app.semanticIndex.indexedCount(),
                    semanticPending = if (model == null) 0 else app.semanticIndex.pendingCount(),
                )
            }
            _state.update {
                it.copy(
                    loading = false,
                    stats = snapshot.stats,
                    projects = snapshot.projects,
                    timeline = snapshot.timeline,
                    bridgeEnabled = repository.getEncryptedSetting(BrainBridgeService.SETTING_ENABLED) == "true",
                    bridgeToken = tokenStore.token(),
                    semanticReady = snapshot.modelHash != null,
                    semanticModelHash = snapshot.modelHash,
                    semanticDimensions = snapshot.modelDimensions,
                    semanticIndexed = snapshot.semanticIndexed,
                    semanticPending = snapshot.semanticPending,
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

    fun createPortableBackup(uri: Uri, passphrase: String) {
        if (_state.value.vaultBusy) return
        _state.update { it.copy(vaultBusy = true, vaultStatus = "Encrypting portable backup…", error = null) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val output = app.contentResolver.openOutputStream(uri, "w")
                        ?: error("Unable to create backup file")
                    output.use { app.portableBackup.write(it, passphrase.toCharArray()) }
                }
            }.onSuccess { summary ->
                _state.update {
                    it.copy(
                        vaultBusy = false,
                        vaultStatus = "Backup complete: ${summary.conversations} conversations, ${summary.messages} messages, ${summary.attachments} attachments.",
                    )
                }
            }.onFailure { failure ->
                _state.update {
                    it.copy(
                        vaultBusy = false,
                        vaultStatus = null,
                        error = failure.message ?: failure.javaClass.simpleName,
                    )
                }
            }
        }
    }

    fun restorePortableBackup(uri: Uri, passphrase: String) {
        if (_state.value.vaultBusy || _state.value.importing) return
        _state.update { it.copy(vaultBusy = true, vaultStatus = "Decrypting and rebuilding Brain…", error = null) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val input = app.contentResolver.openInputStream(uri)
                        ?: error("Unable to open backup file")
                    input.use { app.portableBackup.restore(it, passphrase.toCharArray()) }
                }
            }.onSuccess { summary ->
                _state.update {
                    it.copy(
                        vaultBusy = false,
                        vaultStatus = "Restore complete: ${summary.conversations} conversations, ${summary.messages} messages, ${summary.attachments} attachments processed.",
                    )
                }
                refresh()
            }.onFailure { failure ->
                _state.update {
                    it.copy(
                        vaultBusy = false,
                        vaultStatus = null,
                        error = failure.message ?: "Restore failed — verify the backup passphrase",
                    )
                }
            }
        }
    }

    fun installEmbeddingModel(uri: Uri) {
        if (_state.value.semanticIndexing || _state.value.vaultBusy) return
        _state.update { it.copy(semanticStatus = "Validating local embedding model…", error = null) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val input = app.contentResolver.openInputStream(uri)
                        ?: error("Unable to open embedding model")
                    input.use(app.semanticIndex::installModel)
                }
            }.onSuccess { model ->
                _state.update {
                    it.copy(
                        semanticReady = true,
                        semanticModelHash = model.hash,
                        semanticDimensions = model.dimensions,
                        semanticIndexed = 0,
                        semanticPending = it.stats.messages,
                        semanticStatus = "Local ${model.dimensions}-dimension embedder installed. Build the semantic index when ready.",
                    )
                }
                refresh()
            }.onFailure { failure ->
                _state.update {
                    it.copy(
                        semanticStatus = null,
                        error = failure.message ?: "Embedding model is not compatible",
                    )
                }
            }
        }
    }

    fun buildSemanticIndex() {
        if (_state.value.semanticIndexing) return
        val startingIndexed = _state.value.semanticIndexed
        _state.update { it.copy(semanticIndexing = true, semanticStatus = "Starting semantic index…", error = null) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    app.semanticIndex.buildIndex { progress ->
                        _state.update {
                            it.copy(
                                semanticIndexing = true,
                                semanticIndexed = startingIndexed + progress.indexed,
                                semanticPending = progress.remaining,
                                semanticStatus = buildString {
                                    append("Embedding ")
                                    append(progress.indexed)
                                    append(" new messages")
                                    if (!progress.currentConversation.isNullOrBlank()) {
                                        append(" • ")
                                        append(progress.currentConversation)
                                    }
                                },
                            )
                        }
                    }
                }
            }.onSuccess { newlyIndexed ->
                _state.update {
                    it.copy(
                        semanticIndexing = false,
                        semanticStatus = "Semantic index ready • $newlyIndexed new messages embedded locally.",
                    )
                }
                refresh()
            }.onFailure { failure ->
                _state.update {
                    it.copy(
                        semanticIndexing = false,
                        semanticStatus = null,
                        error = failure.message ?: "Semantic indexing failed",
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
            val hits = withContext(Dispatchers.IO) {
                if (_state.value.semanticReady && _state.value.semanticIndexed > 0) {
                    app.semanticIndex.hybridSearch(cleaned, 80)
                } else {
                    repository.search(cleaned, 80)
                }
            }
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

    private data class RefreshSnapshot(
        val stats: BrainStats,
        val projects: List<ProjectSummary>,
        val timeline: List<TimelineItem>,
        val modelHash: String?,
        val modelDimensions: Int,
        val semanticIndexed: Int,
        val semanticPending: Int,
    )
}
