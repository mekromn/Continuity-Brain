package com.mekromn.continuitybrain.autosync

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.mekromn.continuitybrain.data.BrainRepository

/** User-facing scheduler/configuration for periodic export-folder ingestion. */
class AutoImportController(
    private val context: Context,
    private val repository: BrainRepository,
) {
    data class State(
        val enabled: Boolean,
        val folderName: String?,
        val treeUri: Uri?,
        val lastStatus: String?,
        val lastCheckAt: Double?,
    )

    fun state(): State = State(
        enabled = repository.getEncryptedSetting(KEY_ENABLED) == "true",
        folderName = repository.getEncryptedSetting(KEY_FOLDER_NAME),
        treeUri = repository.getEncryptedSetting(KEY_TREE_URI)?.let(Uri::parse),
        lastStatus = repository.getEncryptedSetting(KEY_LAST_STATUS),
        lastCheckAt = repository.getEncryptedSetting(KEY_LAST_CHECK)?.toDoubleOrNull(),
    )

    fun setFolder(treeUri: Uri): State {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(treeUri, flags)
        val name = resolveTreeName(treeUri)
        repository.setEncryptedSetting(KEY_TREE_URI, treeUri.toString())
        repository.setEncryptedSetting(KEY_FOLDER_NAME, name)
        repository.setEncryptedSetting(KEY_ENABLED, "true")
        repository.setEncryptedSetting(KEY_LAST_STATUS, "Folder linked. Checking for the newest ChatGPT export…")
        schedulePeriodic()
        runNow()
        return state()
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled && state().treeUri == null) return
        repository.setEncryptedSetting(KEY_ENABLED, enabled.toString())
        if (enabled) schedulePeriodic() else cancelScheduled()
    }

    fun runNow() {
        if (state().treeUri == null) return
        scheduler.schedule(
            JobInfo.Builder(
                IMMEDIATE_JOB_ID,
                ComponentName(context, AutoImportJobService::class.java),
            )
                .setMinimumLatency(0L)
                .setOverrideDeadline(1_000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .build(),
        )
    }

    fun schedulePeriodic() {
        if (state().treeUri == null || repository.getEncryptedSetting(KEY_ENABLED) != "true") return
        scheduler.schedule(
            JobInfo.Builder(
                PERIODIC_JOB_ID,
                ComponentName(context, AutoImportJobService::class.java),
            )
                .setPeriodic(PERIOD_MILLIS, FLEX_MILLIS)
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .build(),
        )
    }

    fun recordResult(status: String) {
        repository.setEncryptedSetting(KEY_LAST_STATUS, status)
        repository.setEncryptedSetting(KEY_LAST_CHECK, (System.currentTimeMillis() / 1000.0).toString())
    }

    private fun cancelScheduled() {
        scheduler.cancel(PERIODIC_JOB_ID)
        scheduler.cancel(IMMEDIATE_JOB_ID)
    }

    private fun resolveTreeName(treeUri: Uri): String {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return "Selected folder"
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        return context.contentResolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.takeIf(String::isNotBlank) ?: "Selected folder"
    }

    private val scheduler: JobScheduler
        get() = context.getSystemService(JobScheduler::class.java)

    companion object {
        const val KEY_ENABLED = "auto_import.enabled.v1"
        const val KEY_TREE_URI = "auto_import.tree_uri.v1"
        const val KEY_FOLDER_NAME = "auto_import.folder_name.v1"
        const val KEY_LAST_STATUS = "auto_import.last_status.v1"
        const val KEY_LAST_CHECK = "auto_import.last_check.v1"

        private const val PERIODIC_JOB_ID = 0x434201
        private const val IMMEDIATE_JOB_ID = 0x434202
        private const val PERIOD_MILLIS = 6L * 60L * 60L * 1000L
        private const val FLEX_MILLIS = 45L * 60L * 1000L
    }
}
