package com.mekromn.continuitybrain.backup

import android.database.Cursor
import com.mekromn.continuitybrain.data.BrainDatabase
import com.mekromn.continuitybrain.data.BrainRepository
import com.mekromn.continuitybrain.data.CryptoVault
import com.mekromn.continuitybrain.data.EncryptedBlob
import com.mekromn.continuitybrain.model.ImportedConversation
import com.mekromn.continuitybrain.model.ImportedMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Device-independent Continuity Brain backup.
 *
 * Android Keystore keys are intentionally non-exportable, so copying the app's
 * SQLite/CBENC files would not be a portable backup. This format performs a
 * streaming logical export: values are decrypted only as they are serialized
 * into an AES-256-GCM stream protected by a passphrase-derived key.
 *
 * No plaintext backup is ever written to disk by this class.
 */
class PortableBrainBackup(
    private val database: BrainDatabase,
    private val repository: BrainRepository,
    private val crypto: CryptoVault,
) {
    data class BackupSummary(
        val conversations: Int,
        val messages: Int,
        val attachments: Int,
    )

    data class RestoreSummary(
        val conversations: Int,
        val messages: Int,
        val attachments: Int,
    )

    fun write(output: OutputStream, passphrase: CharArray): BackupSummary {
        require(passphrase.size >= MIN_PASSPHRASE_LENGTH) {
            "Backup passphrase must be at least $MIN_PASSPHRASE_LENGTH characters"
        }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val key = deriveKey(passphrase, salt, PBKDF2_ITERATIONS)
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val header = DataOutputStream(output)
        header.write(MAGIC)
        header.writeInt(FORMAT_VERSION)
        header.writeInt(PBKDF2_ITERATIONS)
        header.writeInt(salt.size)
        header.write(salt)
        header.writeInt(cipher.iv.size)
        header.write(cipher.iv)
        header.flush()

        var conversationCount = 0
        var messageCount = 0
        var attachmentCount = 0

        CipherOutputStream(header, cipher).use { encrypted ->
            ZipOutputStream(encrypted, StandardCharsets.UTF_8).use { zip ->
                val manifest = JSONObject()
                    .put("format", "continuity-brain-portable")
                    .put("version", FORMAT_VERSION)
                    .put("created_at", Instant.now().toString())
                    .put("derivation", "PBKDF2WithHmacSHA256")
                    .put("cipher", "AES-256-GCM")
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString(2).toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("conversations.jsonl"))
                val writer = OutputStreamWriter(NonClosingOutputStream(zip), StandardCharsets.UTF_8)
                val db = database.readableDatabase
                db.rawQuery(
                    "SELECT id,title_iv,title_ct,created_at,updated_at,current_node_id FROM conversations ORDER BY COALESCE(created_at,updated_at,0),id",
                    null,
                ).use { conversations ->
                    while (conversations.moveToNext()) {
                        val conversationId = conversations.getString(0)
                        val title = crypto.decryptString(
                            EncryptedBlob(conversations.getBlob(1), conversations.getBlob(2)),
                            "conversation-title:$conversationId",
                        )
                        val messagesJson = JSONArray()
                        db.rawQuery(
                            "SELECT id,parent_id,role,created_at,updated_at,content_iv,content_ct,content_type,ordinal FROM messages WHERE conversation_id=? ORDER BY ordinal,id",
                            arrayOf(conversationId),
                        ).use { messages ->
                            while (messages.moveToNext()) {
                                val messageId = messages.getString(0)
                                val content = crypto.decryptString(
                                    EncryptedBlob(messages.getBlob(5), messages.getBlob(6)),
                                    "message-content:$messageId",
                                )
                                messagesJson.put(
                                    JSONObject()
                                        .put("id", messageId)
                                        .putNullable("parent_id", messages.nullableString(1))
                                        .put("role", messages.getString(2))
                                        .putNullable("created_at", messages.nullableDouble(3))
                                        .putNullable("updated_at", messages.nullableDouble(4))
                                        .put("content", content)
                                        .putNullable("content_type", messages.nullableString(7))
                                        .put("ordinal", messages.getInt(8)),
                                )
                                messageCount++
                            }
                        }
                        val record = JSONObject()
                            .put("id", conversationId)
                            .put("title", title)
                            .putNullable("created_at", conversations.nullableDouble(3))
                            .putNullable("updated_at", conversations.nullableDouble(4))
                            .putNullable("current_node_id", conversations.nullableString(5))
                            .put("messages", messagesJson)
                        writer.write(record.toString())
                        writer.write("\n")
                        conversationCount++
                    }
                }
                writer.flush()
                zip.closeEntry()

                val attachmentMetadata = JSONArray()
                val attachments = ArrayList<AttachmentBackupRecord>()
                database.readableDatabase.rawQuery(
                    "SELECT id,entry_name_iv,entry_name_ct,mime_type,size_bytes,sha256,encrypted_path FROM attachments ORDER BY id",
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0)
                        val name = crypto.decryptString(
                            EncryptedBlob(cursor.getBlob(1), cursor.getBlob(2)),
                            "attachment-name:$id",
                        )
                        val record = AttachmentBackupRecord(
                            id = id,
                            name = name,
                            mime = cursor.nullableString(3),
                            size = cursor.getLong(4),
                            sha256 = cursor.getString(5),
                            encryptedPath = cursor.getString(6),
                        )
                        attachments += record
                        attachmentMetadata.put(
                            JSONObject()
                                .put("id", id)
                                .put("name", name)
                                .putNullable("mime", record.mime)
                                .put("size", record.size)
                                .put("sha256", record.sha256),
                        )
                    }
                }

                zip.putNextEntry(ZipEntry("attachments.json"))
                zip.write(attachmentMetadata.toString().toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()

                for (attachment in attachments) {
                    val source = File(attachment.encryptedPath)
                    if (!source.isFile) continue
                    zip.putNextEntry(ZipEntry("attachments/${attachment.id}.bin"))
                    source.inputStream().use { encryptedAttachment ->
                        crypto.decryptStream(
                            encryptedAttachment,
                            NonClosingOutputStream(zip),
                            "attachment:${attachment.id}",
                        )
                    }
                    zip.closeEntry()
                    attachmentCount++
                }
            }
        }
        passphrase.fill('\u0000')
        return BackupSummary(conversationCount, messageCount, attachmentCount)
    }

    fun restore(input: InputStream, passphrase: CharArray): RestoreSummary {
        require(passphrase.isNotEmpty()) { "Backup passphrase is required" }
        val header = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        header.readFully(magic)
        require(magic.contentEquals(MAGIC)) { "Not a Continuity Brain portable backup" }
        val version = header.readInt()
        require(version == FORMAT_VERSION) { "Unsupported Continuity Brain backup version: $version" }
        val iterations = header.readInt()
        require(iterations in 100_000..2_000_000) { "Invalid backup KDF parameters" }
        val saltLength = header.readInt()
        require(saltLength in 16..64) { "Invalid backup salt" }
        val salt = ByteArray(saltLength)
        header.readFully(salt)
        val ivLength = header.readInt()
        require(ivLength in 12..32) { "Invalid backup IV" }
        val iv = ByteArray(ivLength)
        header.readFully(iv)

        val key = deriveKey(passphrase, salt, iterations)
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))

        var conversations = 0
        var messages = 0
        var attachments = 0
        var attachmentNames = emptyMap<String, String>()

        CipherInputStream(header, cipher).use { decrypted ->
            ZipInputStream(decrypted, StandardCharsets.UTF_8).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when (entry.name) {
                        "manifest.json" -> {
                            val manifest = JSONObject(readEntryText(zip, 256 * 1024))
                            require(manifest.optString("format") == "continuity-brain-portable") {
                                "Invalid Continuity Brain backup manifest"
                            }
                        }
                        "conversations.jsonl" -> {
                            val reader = BufferedReader(
                                InputStreamReader(NonClosingInputStream(zip), StandardCharsets.UTF_8),
                                64 * 1024,
                            )
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isBlank()) continue
                                val conversation = decodeConversation(JSONObject(line))
                                repository.ingestConversation(conversation, source = "portable-backup")
                                conversations++
                                messages += conversation.messages.size
                            }
                        }
                        "attachments.json" -> {
                            val array = JSONArray(readEntryText(zip, MAX_METADATA_BYTES))
                            attachmentNames = buildMap {
                                for (index in 0 until array.length()) {
                                    val item = array.getJSONObject(index)
                                    put(item.getString("id"), item.getString("name"))
                                }
                            }
                        }
                        else -> if (entry.name.startsWith("attachments/") && entry.name.endsWith(".bin")) {
                            val id = entry.name.substringAfterLast('/').removeSuffix(".bin")
                            val name = attachmentNames[id] ?: "restored-$id.bin"
                            if (repository.storeAttachment(name, NonClosingInputStream(zip))) attachments++
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
        passphrase.fill('\u0000')
        return RestoreSummary(conversations, messages, attachments)
    }

    private fun decodeConversation(json: JSONObject): ImportedConversation {
        val messagesArray = json.getJSONArray("messages")
        val messages = ArrayList<ImportedMessage>(messagesArray.length())
        for (index in 0 until messagesArray.length()) {
            val message = messagesArray.getJSONObject(index)
            messages += ImportedMessage(
                id = message.getString("id"),
                parentId = message.optNullableString("parent_id"),
                role = message.getString("role"),
                createdAt = message.optNullableDouble("created_at"),
                updatedAt = message.optNullableDouble("updated_at"),
                content = message.getString("content"),
                contentType = message.optNullableString("content_type"),
                ordinal = message.optInt("ordinal", index),
            )
        }
        return ImportedConversation(
            id = json.getString("id"),
            title = json.getString("title"),
            createdAt = json.optNullableDouble("created_at"),
            updatedAt = json.optNullableDouble("updated_at"),
            currentNodeId = json.optNullableString("current_node_id"),
            messages = messages,
        )
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        return try {
            val encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
            SecretKeySpec(encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun readEntryText(input: InputStream, maxBytes: Int): String {
        val buffer = ByteArray(16 * 1024)
        val output = java.io.ByteArrayOutputStream()
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            require(output.size() + read <= maxBytes) { "Backup metadata is too large" }
            output.write(buffer, 0, read)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject =
        put(name, value ?: JSONObject.NULL)

    private fun JSONObject.optNullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else getString(name)

    private fun JSONObject.optNullableDouble(name: String): Double? =
        if (!has(name) || isNull(name)) null else getDouble(name)

    private fun Cursor.nullableString(index: Int): String? = if (isNull(index)) null else getString(index)
    private fun Cursor.nullableDouble(index: Int): Double? = if (isNull(index)) null else getDouble(index)

    private data class AttachmentBackupRecord(
        val id: String,
        val name: String,
        val mime: String?,
        val size: Long,
        val sha256: String,
        val encryptedPath: String,
    )

    private class NonClosingInputStream(input: InputStream) : java.io.FilterInputStream(input) {
        override fun close() = Unit
    }

    private class NonClosingOutputStream(output: OutputStream) : java.io.FilterOutputStream(output) {
        override fun close() = flush()
    }

    companion object {
        private val MAGIC = byteArrayOf(0x43, 0x42, 0x42, 0x4B, 0x55, 0x50, 0x32) // CBBKUP2
        private const val FORMAT_VERSION = 2
        private const val SALT_BYTES = 32
        private const val PBKDF2_ITERATIONS = 600_000
        private const val MIN_PASSPHRASE_LENGTH = 12
        private const val MAX_METADATA_BYTES = 32 * 1024 * 1024
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
