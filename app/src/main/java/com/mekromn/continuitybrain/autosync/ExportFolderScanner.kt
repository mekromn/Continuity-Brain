package com.mekromn.continuitybrain.autosync

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.util.zip.ZipInputStream

/**
 * Finds genuine ChatGPT export candidates under a user-granted SAF tree.
 *
 * We deliberately do not import arbitrary ZIP files from the chosen folder:
 * ZIPs are opened only far enough to verify that they contain
 * `conversations.json`. Raw JSON candidates must themselves be named
 * `conversations.json`.
 */
class ExportFolderScanner(
    private val resolver: ContentResolver,
) {
    data class Candidate(
        val uri: Uri,
        val name: String,
        val lastModified: Long,
        val size: Long,
    )

    fun newestValidExport(treeUri: Uri, maxDepth: Int = 2): Candidate? {
        val candidates = ArrayList<Candidate>()
        scanDirectory(
            treeUri = treeUri,
            documentId = DocumentsContract.getTreeDocumentId(treeUri),
            depth = 0,
            maxDepth = maxDepth.coerceIn(0, 4),
            output = candidates,
        )
        return candidates
            .sortedWith(compareByDescending<Candidate> { it.lastModified }.thenByDescending { it.size })
            .asSequence()
            .take(MAX_CANDIDATES_TO_VALIDATE)
            .firstOrNull(::isGenuineExport)
    }

    private fun scanDirectory(
        treeUri: Uri,
        documentId: String,
        depth: Int,
        maxDepth: Int,
        output: MutableList<Candidate>,
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val modifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            while (cursor.moveToNext()) {
                val childId = cursor.getString(idIndex) ?: continue
                val name = cursor.getString(nameIndex).orEmpty()
                val mime = cursor.getString(mimeIndex).orEmpty()
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (depth < maxDepth) {
                        scanDirectory(treeUri, childId, depth + 1, maxDepth, output)
                    }
                    continue
                }
                if (!looksLikeCandidate(name, mime)) continue
                output += Candidate(
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId),
                    name = name,
                    lastModified = if (cursor.isNull(modifiedIndex)) 0L else cursor.getLong(modifiedIndex),
                    size = if (cursor.isNull(sizeIndex)) 0L else cursor.getLong(sizeIndex),
                )
            }
        }
    }

    private fun looksLikeCandidate(name: String, mime: String): Boolean {
        val lower = name.lowercase()
        return lower == "conversations.json" ||
            lower.endsWith(".zip") ||
            mime == "application/zip"
    }

    private fun isGenuineExport(candidate: Candidate): Boolean {
        if (candidate.name.equals("conversations.json", ignoreCase = true)) return true
        return runCatching {
            resolver.openInputStream(candidate.uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entries = 0
                    while (entries++ < MAX_ZIP_ENTRIES_TO_INSPECT) {
                        val entry = zip.nextEntry ?: break
                        val normalized = entry.name.replace('\\', '/').trimStart('/')
                        if (normalized.equals("conversations.json", ignoreCase = true) ||
                            normalized.endsWith("/conversations.json", ignoreCase = true)
                        ) {
                            return@use true
                        }
                    }
                    false
                }
            } ?: false
        }.getOrDefault(false)
    }

    companion object {
        private const val MAX_CANDIDATES_TO_VALIDATE = 24
        private const val MAX_ZIP_ENTRIES_TO_INSPECT = 4096
    }
}
