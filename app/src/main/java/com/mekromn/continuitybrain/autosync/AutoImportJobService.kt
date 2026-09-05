package com.mekromn.continuitybrain.autosync

import android.app.job.JobParameters
import android.app.job.JobService
import com.mekromn.continuitybrain.ContinuityBrainApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Periodically scans the user-selected SAF folder and imports the newest genuine
 * ChatGPT export. No network is required and exact archive duplicates are
 * discarded by the importer before message processing.
 */
class AutoImportJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runningJob: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as ContinuityBrainApplication
        val controller = app.autoImportController
        val state = controller.state()
        if (!state.enabled || state.treeUri == null) {
            controller.recordResult("Automatic import is disabled or the export folder is unavailable.")
            return false
        }

        runningJob = scope.launch {
            var needsRetry = false
            try {
                val candidate = ExportFolderScanner(contentResolver).newestValidExport(state.treeUri)
                if (candidate == null) {
                    controller.recordResult("Checked ${state.folderName ?: "export folder"}: no ChatGPT export found.")
                } else {
                    val summary = app.importer.importUri(candidate.uri)
                    when {
                        summary.duplicateArchive -> controller.recordResult(
                            "Checked ${candidate.name}: already up to date.",
                        )
                        summary.conversationsSeen > 0 -> {
                            app.retrievalService.synchronizeSearchIndex()
                            controller.recordResult(
                                "Imported ${candidate.name}: ${summary.conversationsSeen} conversations, +${summary.added} new, ${summary.updated} changed messages.",
                            )
                        }
                        else -> controller.recordResult(
                            "Checked ${candidate.name}: no conversations were imported.",
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: SecurityException) {
                controller.recordResult("Export-folder permission was lost. Choose the folder again in Vault.")
            } catch (failure: Throwable) {
                needsRetry = true
                controller.recordResult(
                    "Automatic import failed: ${failure.message ?: failure.javaClass.simpleName}",
                )
            } finally {
                jobFinished(params, needsRetry)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        runningJob?.cancel()
        runningJob = null
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
