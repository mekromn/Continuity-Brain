package com.mekromn.continuitybrain.semantic

import android.content.ContentValues
import android.database.Cursor
import com.mekromn.continuitybrain.data.BrainDatabase
import com.mekromn.continuitybrain.data.BrainRepository
import com.mekromn.continuitybrain.data.CryptoVault
import com.mekromn.continuitybrain.data.EncryptedBlob
import com.mekromn.continuitybrain.model.SearchHit
import java.io.InputStream
import java.util.PriorityQueue
import kotlin.math.sqrt

class SemanticIndex(
    private val database: BrainDatabase,
    private val repository: BrainRepository,
    private val crypto: CryptoVault,
    private val engine: LocalEmbeddingEngine,
) {
    data class IndexProgress(
        val indexed: Int,
        val remaining: Int,
        val currentConversation: String? = null,
    )

    init {
        // A message edit must never leave a stale semantic vector associated
        // with the new text. SQLite enforces this even if the edit came from a
        // future importer or live Continuity path.
        database.writableDatabase.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS invalidate_embedding_on_message_change
            AFTER UPDATE OF content_hash ON messages
            WHEN OLD.content_hash <> NEW.content_hash
            BEGIN
              DELETE FROM message_embeddings WHERE message_id=NEW.id;
            END
            """.trimIndent(),
        )
    }

    fun installModel(input: InputStream): LocalEmbeddingEngine.ModelInfo = engine.installModel(input)

    fun modelInfo(): LocalEmbeddingEngine.ModelInfo? = engine.activeModel()

    fun indexedCount(): Int {
        val model = engine.activeModel() ?: return 0
        return database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM message_embeddings WHERE model_hash=?",
            arrayOf(model.hash),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun pendingCount(): Int {
        val model = engine.activeModel() ?: return 0
        return database.readableDatabase.rawQuery(
            """
            SELECT COUNT(*)
            FROM messages m
            LEFT JOIN message_embeddings e
              ON e.message_id=m.id AND e.model_hash=?
            WHERE e.message_id IS NULL AND LENGTH(m.content_ct)>0
            """.trimIndent(),
            arrayOf(model.hash),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /**
     * Builds/rebuilds embeddings incrementally. Safe to stop and run again:
     * each message is committed independently and edited messages are
     * invalidated by the database trigger.
     */
    fun buildIndex(progress: (IndexProgress) -> Unit = {}): Int {
        val model = engine.activeModel() ?: error("Install a local embedding model first")
        var indexed = 0
        var remaining = pendingCount()
        progress(IndexProgress(indexed, remaining))

        val pending = database.readableDatabase.rawQuery(
            """
            SELECT m.id,m.content_iv,m.content_ct,c.title_iv,c.title_ct,m.conversation_id
            FROM messages m
            JOIN conversations c ON c.id=m.conversation_id
            LEFT JOIN message_embeddings e
              ON e.message_id=m.id AND e.model_hash=?
            WHERE e.message_id IS NULL AND LENGTH(m.content_ct)>0
            ORDER BY COALESCE(m.created_at,m.updated_at,0),m.id
            """.trimIndent(),
            arrayOf(model.hash),
        )

        pending.use { cursor ->
            while (cursor.moveToNext()) {
                val messageId = cursor.getString(0)
                val conversationId = cursor.getString(5)
                val content = crypto.decryptString(
                    EncryptedBlob(cursor.getBlob(1), cursor.getBlob(2)),
                    "message-content:$messageId",
                )
                if (content.isNotBlank()) {
                    val embedding = engine.embed(content)
                    val encrypted = crypto.encrypt(
                        embedding.quantized,
                        vectorAad(messageId, model.hash),
                    )
                    val values = ContentValues().apply {
                        put("message_id", messageId)
                        put("model_hash", model.hash)
                        put("dimensions", embedding.dimensions)
                        put("signature", embedding.signature)
                        put("vector_iv", encrypted.iv)
                        put("vector_ct", encrypted.ciphertext)
                        put("indexed_at", nowSeconds())
                    }
                    database.writableDatabase.insertWithOnConflict(
                        "message_embeddings",
                        null,
                        values,
                        android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                    )
                    indexed++
                }
                remaining--
                if (indexed == 1 || indexed % 25 == 0 || remaining == 0) {
                    val title = crypto.decryptString(
                        EncryptedBlob(cursor.getBlob(3), cursor.getBlob(4)),
                        "conversation-title:$conversationId",
                    )
                    progress(IndexProgress(indexed, remaining.coerceAtLeast(0), title))
                }
            }
        }
        return indexed
    }

    fun semanticSearch(query: String, limit: Int = 50): List<SearchHit> {
        val model = engine.activeModel() ?: return emptyList()
        if (query.isBlank()) return emptyList()
        val queryEmbedding = engine.embed(query)
        if (queryEmbedding.dimensions != model.dimensions) return emptyList()

        val shortlistSize = maxOf(limit * 8, 240).coerceAtMost(1200)
        val shortlist = PriorityQueue<SignatureCandidate>(
            compareByDescending<SignatureCandidate> { it.hamming }
                .thenByDescending { it.messageId },
        )

        database.readableDatabase.rawQuery(
            "SELECT message_id,signature FROM message_embeddings WHERE model_hash=?",
            arrayOf(model.hash),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val candidate = SignatureCandidate(
                    messageId = cursor.getString(0),
                    hamming = java.lang.Long.bitCount(queryEmbedding.signature xor cursor.getLong(1)),
                )
                if (shortlist.size < shortlistSize) {
                    shortlist += candidate
                } else if (candidate.hamming < shortlist.peek().hamming) {
                    shortlist.poll()
                    shortlist += candidate
                }
            }
        }
        if (shortlist.isEmpty()) return emptyList()

        val ids = shortlist.map { it.messageId }
        val similarities = HashMap<String, Double>(ids.size)
        ids.chunked(400).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val args = ArrayList<String>(chunk.size + 1).apply {
                add(model.hash)
                addAll(chunk)
            }
            database.readableDatabase.rawQuery(
                "SELECT message_id,dimensions,vector_iv,vector_ct FROM message_embeddings WHERE model_hash=? AND message_id IN ($placeholders)",
                args.toTypedArray(),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val dimensions = cursor.getInt(1)
                    if (dimensions != queryEmbedding.dimensions) continue
                    val vector = crypto.decrypt(
                        EncryptedBlob(cursor.getBlob(2), cursor.getBlob(3)),
                        vectorAad(id, model.hash),
                    )
                    similarities[id] = cosine(queryEmbedding.quantized, vector)
                }
            }
        }

        val best = similarities.entries
            .sortedByDescending { it.value }
            .take(limit.coerceIn(1, 200))
        if (best.isEmpty()) return emptyList()
        val scoreById = best.associate { it.key to it.value }
        return readHits(best.map { it.key }, scoreById)
    }

    /**
     * Reciprocal-rank fusion avoids pretending lexical and cosine scores have
     * the same scale. Exact technical matches stay strong while semantic-only
     * matches can enter the result set.
     */
    fun hybridSearch(query: String, limit: Int = 50): List<SearchHit> {
        val lexical = repository.search(query, limit = minOf(100, limit * 2))
        if (!engine.isReady() || indexedCount() == 0) return lexical.take(limit)
        val semantic = semanticSearch(query, limit = minOf(100, limit * 2))
        if (semantic.isEmpty()) return lexical.take(limit)

        data class Fusion(val hit: SearchHit, var score: Double = 0.0)
        val fused = LinkedHashMap<String, Fusion>()
        lexical.forEachIndexed { rank, hit ->
            val item = fused.getOrPut(hit.messageId) { Fusion(hit) }
            item.score += 1.0 / (60.0 + rank)
        }
        semantic.forEachIndexed { rank, hit ->
            val item = fused.getOrPut(hit.messageId) { Fusion(hit) }
            item.score += 1.0 / (60.0 + rank)
        }
        return fused.values
            .sortedByDescending { it.score }
            .take(limit.coerceIn(1, 200))
            .mapIndexed { index, fusion -> fusion.hit.copy(score = (10_000 - index).coerceAtLeast(1)) }
    }

    private fun readHits(ids: List<String>, similarityById: Map<String, Double>): List<SearchHit> {
        val results = HashMap<String, SearchHit>(ids.size)
        ids.chunked(400).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            database.readableDatabase.rawQuery(
                """
                SELECT m.id,m.conversation_id,m.role,m.created_at,m.content_iv,m.content_ct,c.title_iv,c.title_ct
                FROM messages m JOIN conversations c ON c.id=m.conversation_id
                WHERE m.id IN ($placeholders)
                """.trimIndent(),
                chunk.toTypedArray(),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val conversationId = cursor.getString(1)
                    val similarity = similarityById[id] ?: continue
                    results[id] = SearchHit(
                        messageId = id,
                        conversationId = conversationId,
                        conversationTitle = crypto.decryptString(
                            EncryptedBlob(cursor.getBlob(6), cursor.getBlob(7)),
                            "conversation-title:$conversationId",
                        ),
                        role = cursor.getString(2),
                        createdAt = cursor.nullableDouble(3),
                        content = crypto.decryptString(
                            EncryptedBlob(cursor.getBlob(4), cursor.getBlob(5)),
                            "message-content:$id",
                        ),
                        score = ((similarity + 1.0) * 5000.0).toInt(),
                    )
                }
            }
        }
        return ids.mapNotNull(results::get)
    }

    private fun cosine(a: ByteArray, b: ByteArray): Double {
        if (a.size != b.size || a.isEmpty()) return -1.0
        var dot = 0.0
        var aa = 0.0
        var bb = 0.0
        for (index in a.indices) {
            val x = a[index].toDouble()
            val y = b[index].toDouble()
            dot += x * y
            aa += x * x
            bb += y * y
        }
        if (aa <= 0.0 || bb <= 0.0) return -1.0
        return dot / (sqrt(aa) * sqrt(bb))
    }

    private data class SignatureCandidate(val messageId: String, val hamming: Int)

    private fun Cursor.nullableDouble(index: Int): Double? = if (isNull(index)) null else getDouble(index)
    private fun vectorAad(messageId: String, modelHash: String) = "embedding:$messageId:$modelHash"
    private fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0
}
