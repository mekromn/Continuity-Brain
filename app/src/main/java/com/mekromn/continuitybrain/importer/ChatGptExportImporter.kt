package com.mekromn.continuitybrain.importer

import android.content.ContentResolver
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import com.mekromn.continuitybrain.data.BrainRepository
import com.mekromn.continuitybrain.data.CryptoVault
import com.mekromn.continuitybrain.model.ImportDelta
import com.mekromn.continuitybrain.model.ImportProgress
import com.mekromn.continuitybrain.model.ImportSummary
import com.mekromn.continuitybrain.model.ImportedConversation
import com.mekromn.continuitybrain.model.ImportedMessage
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackInputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

class ChatGptExportImporter(
    private val resolver: ContentResolver,
    private val repository: BrainRepository,
    private val crypto: CryptoVault,
) {
    fun importUri(uri: Uri, progress: (ImportProgress) -> Unit = {}): ImportSummary {
        progress(ImportProgress(stage = "Fingerprinting export"))
        val archiveHash = resolver.openInputStream(uri)?.use(crypto::sha256)
            ?: error("Unable to open export")
        if (repository.hasImport(archiveHash)) {
            return ImportSummary(
                duplicateArchive = true,
                conversationsSeen = 0,
                messagesSeen = 0,
                attachmentsSeen = 0,
                added = 0,
                updated = 0,
                unchanged = 0,
            )
        }

        var conversations = 0
        var messages = 0
        var attachments = 0
        var delta = ImportDelta()
        var foundConversations = false

        resolver.openInputStream(uri)?.use { raw ->
            val input = PushbackInputStream(BufferedInputStream(raw, 128 * 1024), 8)
            val signature = ByteArray(4)
            val read = input.read(signature)
            if (read > 0) input.unread(signature, 0, read)
            val isZip = read >= 2 && signature[0] == 'P'.code.toByte() && signature[1] == 'K'.code.toByte()

            if (isZip) {
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) {
                            zip.closeEntry()
                            continue
                        }
                        val basename = entry.name.substringAfterLast('/')
                        if (basename.equals("conversations.json", ignoreCase = true)) {
                            progress(
                                ImportProgress(
                                    stage = "Reading conversations",
                                    conversationsSeen = conversations,
                                    messagesSeen = messages,
                                    attachmentsSeen = attachments,
                                    added = delta.added,
                                    updated = delta.updated,
                                    unchanged = delta.unchanged,
                                ),
                            )
                            val reader = JsonReader(
                                InputStreamReader(NonClosingInputStream(zip), StandardCharsets.UTF_8),
                            )
                            parseConversationRoot(reader) { conversation ->
                                foundConversations = true
                                conversations++
                                messages += conversation.messages.size
                                delta += repository.ingestConversation(conversation)
                                if (conversations % 5 == 0 || conversations == 1) {
                                    progress(
                                        ImportProgress(
                                            stage = "Merging knowledge",
                                            conversationsSeen = conversations,
                                            messagesSeen = messages,
                                            attachmentsSeen = attachments,
                                            added = delta.added,
                                            updated = delta.updated,
                                            unchanged = delta.unchanged,
                                        ),
                                    )
                                }
                            }
                        } else if (shouldArchiveEntry(entry.name)) {
                            attachments++
                            progress(
                                ImportProgress(
                                    stage = "Encrypting attachments",
                                    conversationsSeen = conversations,
                                    messagesSeen = messages,
                                    attachmentsSeen = attachments,
                                    added = delta.added,
                                    updated = delta.updated,
                                    unchanged = delta.unchanged,
                                ),
                            )
                            repository.storeAttachment(entry.name, NonClosingInputStream(zip))
                        }
                        zip.closeEntry()
                    }
                }
            } else {
                val reader = JsonReader(InputStreamReader(input, StandardCharsets.UTF_8))
                parseConversationRoot(reader) { conversation ->
                    foundConversations = true
                    conversations++
                    messages += conversation.messages.size
                    delta += repository.ingestConversation(conversation)
                    if (conversations % 5 == 0 || conversations == 1) {
                        progress(
                            ImportProgress(
                                stage = "Merging knowledge",
                                conversationsSeen = conversations,
                                messagesSeen = messages,
                                attachmentsSeen = 0,
                                added = delta.added,
                                updated = delta.updated,
                                unchanged = delta.unchanged,
                            ),
                        )
                    }
                }
            }
        } ?: error("Unable to reopen export")

        check(foundConversations) { "No ChatGPT conversations were found in this export" }
        val summary = ImportSummary(
            duplicateArchive = false,
            conversationsSeen = conversations,
            messagesSeen = messages,
            attachmentsSeen = attachments,
            added = delta.added,
            updated = delta.updated,
            unchanged = delta.unchanged,
        )
        repository.recordImport(archiveHash, summary)
        progress(
            ImportProgress(
                stage = "Complete",
                conversationsSeen = conversations,
                messagesSeen = messages,
                attachmentsSeen = attachments,
                added = delta.added,
                updated = delta.updated,
                unchanged = delta.unchanged,
            ),
        )
        return summary
    }

    private fun parseConversationRoot(reader: JsonReader, onConversation: (ImportedConversation) -> Unit) {
        when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> parseConversationArray(reader, onConversation)
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "conversations" -> parseConversationArray(reader, onConversation)
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }
            else -> error("Unsupported conversations.json root")
        }
    }

    private fun parseConversationArray(reader: JsonReader, onConversation: (ImportedConversation) -> Unit) {
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }
            readConversation(reader)?.let(onConversation)
        }
        reader.endArray()
    }

    private fun readConversation(reader: JsonReader): ImportedConversation? {
        var id: String? = null
        var title = "Untitled chat"
        var createdAt: Double? = null
        var updatedAt: Double? = null
        var currentNodeId: String? = null
        val nodes = ArrayList<NodeRecord>()

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id", "conversation_id" -> id = nextStringOrNull(reader) ?: id
                "title" -> title = nextStringOrNull(reader)?.takeIf(String::isNotBlank) ?: title
                "create_time" -> createdAt = nextDoubleOrNull(reader)
                "update_time" -> updatedAt = nextDoubleOrNull(reader)
                "current_node" -> currentNodeId = nextStringOrNull(reader)
                "mapping" -> readMapping(reader, nodes)
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        val stableId = id ?: crypto.sha256(
            "$title|$createdAt|$updatedAt|${nodes.firstOrNull()?.id.orEmpty()}"
                .toByteArray(StandardCharsets.UTF_8),
        )
        val messages = nodes.mapIndexedNotNull { index, node ->
            val message = node.message ?: return@mapIndexedNotNull null
            ImportedMessage(
                id = message.id.ifBlank { node.id },
                parentId = node.parentId,
                role = message.role.ifBlank { "unknown" },
                createdAt = message.createdAt,
                updatedAt = message.updatedAt,
                content = message.content.ifBlank {
                    message.contentType?.let { "[content_type=$it]" }.orEmpty()
                },
                contentType = message.contentType,
                ordinal = index,
            )
        }
        return ImportedConversation(
            id = stableId,
            title = title,
            createdAt = createdAt,
            updatedAt = updatedAt,
            currentNodeId = currentNodeId,
            messages = messages,
        )
    }

    private fun readMapping(reader: JsonReader, output: MutableList<NodeRecord>) {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return
        }
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }
            output += readNode(reader, key)
        }
        reader.endObject()
    }

    private fun readNode(reader: JsonReader, mappingKey: String): NodeRecord {
        var id = mappingKey
        var parentId: String? = null
        var message: MessageRecord? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = nextStringOrNull(reader) ?: id
                "parent" -> parentId = nextStringOrNull(reader)
                "message" -> message = readMessage(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return NodeRecord(id, parentId, message)
    }

    private fun readMessage(reader: JsonReader): MessageRecord? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        var id = ""
        var role = "unknown"
        var createdAt: Double? = null
        var updatedAt: Double? = null
        var contentType: String? = null
        var content = ""

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = nextStringOrNull(reader).orEmpty()
                "author" -> role = readAuthorRole(reader) ?: role
                "create_time" -> createdAt = nextDoubleOrNull(reader)
                "update_time" -> updatedAt = nextDoubleOrNull(reader)
                "content" -> {
                    val parsed = readContent(reader)
                    contentType = parsed.first
                    content = parsed.second
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return MessageRecord(id, role, createdAt, updatedAt, contentType, content)
    }

    private fun readAuthorRole(reader: JsonReader): String? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        var role: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "role" -> role = nextStringOrNull(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return role
    }

    private fun readContent(reader: JsonReader): Pair<String?, String> {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null to ""
        }
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            return null to readTextish(reader, 0, null)
        }
        var type: String? = null
        val parts = ArrayList<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            when (name) {
                "content_type" -> type = nextStringOrNull(reader)
                "parts" -> {
                    if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            readTextish(reader, 0, "part").takeIf(String::isNotBlank)?.let(parts::add)
                        }
                        reader.endArray()
                    } else {
                        readTextish(reader, 0, name).takeIf(String::isNotBlank)?.let(parts::add)
                    }
                }
                "text", "result", "code", "caption" ->
                    readTextish(reader, 0, name).takeIf(String::isNotBlank)?.let(parts::add)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return type to parts.joinToString("\n").take(MAX_MESSAGE_CHARS)
    }

    private fun readTextish(reader: JsonReader, depth: Int, keyHint: String?): String {
        if (depth > 8) {
            reader.skipValue()
            return ""
        }
        return when (reader.peek()) {
            JsonToken.NULL -> {
                reader.nextNull(); ""
            }
            JsonToken.STRING -> reader.nextString()
            JsonToken.NUMBER -> if (keyHint in meaningfulScalarKeys) reader.nextString() else {
                reader.skipValue(); ""
            }
            JsonToken.BOOLEAN -> if (keyHint in meaningfulScalarKeys) reader.nextBoolean().toString() else {
                reader.skipValue(); ""
            }
            JsonToken.BEGIN_ARRAY -> buildString {
                reader.beginArray()
                while (reader.hasNext()) {
                    val value = readTextish(reader, depth + 1, keyHint)
                    if (value.isNotBlank()) {
                        if (isNotEmpty()) append('\n')
                        append(value)
                    }
                    if (length > MAX_MESSAGE_CHARS) break
                }
                while (reader.hasNext()) reader.skipValue()
                reader.endArray()
            }
            JsonToken.BEGIN_OBJECT -> buildString {
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    if (key in meaningfulObjectKeys) {
                        val value = readTextish(reader, depth + 1, key)
                        if (value.isNotBlank()) {
                            if (isNotEmpty()) append('\n')
                            if (key in labeledObjectKeys) append("[$key: ").append(value).append(']')
                            else append(value)
                        }
                    } else {
                        reader.skipValue()
                    }
                    if (length > MAX_MESSAGE_CHARS) break
                }
                while (reader.hasNext()) {
                    reader.nextName(); reader.skipValue()
                }
                reader.endObject()
            }
            else -> {
                reader.skipValue(); ""
            }
        }.take(MAX_MESSAGE_CHARS)
    }

    private fun nextStringOrNull(reader: JsonReader): String? = when (reader.peek()) {
        JsonToken.NULL -> { reader.nextNull(); null }
        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
        else -> { reader.skipValue(); null }
    }

    private fun nextDoubleOrNull(reader: JsonReader): Double? = when (reader.peek()) {
        JsonToken.NULL -> { reader.nextNull(); null }
        JsonToken.NUMBER, JsonToken.STRING -> runCatching { reader.nextString().toDouble() }.getOrNull()
        else -> { reader.skipValue(); null }
    }

    private fun shouldArchiveEntry(path: String): Boolean {
        val basename = path.substringAfterLast('/').lowercase()
        if (basename.isBlank()) return false
        return basename !in metadataEntries && !basename.endsWith(".html")
    }

    private data class NodeRecord(
        val id: String,
        val parentId: String?,
        val message: MessageRecord?,
    )

    private data class MessageRecord(
        val id: String,
        val role: String,
        val createdAt: Double?,
        val updatedAt: Double?,
        val contentType: String?,
        val content: String,
    )

    private class NonClosingInputStream(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }

    companion object {
        private const val MAX_MESSAGE_CHARS = 2_000_000
        private val metadataEntries = setOf(
            "conversations.json",
            "user.json",
            "message_feedback.json",
            "shared_conversations.json",
            "model_comparisons.json",
            "group_chats.json",
        )
        private val meaningfulObjectKeys = setOf(
            "text", "content", "caption", "code", "name", "title", "url", "asset_pointer",
            "file_name", "filename", "mime_type", "result", "parts",
        )
        private val labeledObjectKeys = setOf(
            "asset_pointer", "file_name", "filename", "mime_type", "url", "name", "title",
        )
        private val meaningfulScalarKeys = meaningfulObjectKeys
    }
}
