package com.mekromn.continuitybrain.search

import android.content.ContentValues
import android.database.Cursor
import com.mekromn.continuitybrain.data.BrainDatabase
import com.mekromn.continuitybrain.data.CryptoVault
import com.mekromn.continuitybrain.data.EncryptedBlob
import com.mekromn.continuitybrain.knowledge.KnowledgeExtractor
import com.mekromn.continuitybrain.model.SearchHit

/**
 * Maintains blind lexical indexes without mixing title terms into every message.
 *
 * The original repository indexer included conversation title tokens in each
 * message row. That works initially but a renamed conversation can leave stale
 * title tokens behind. This revisioned layer rebuilds message terms from message
 * content only and stores current title terms in their own blind table.
 */
class PrivateSearchIndex(
    private val database: BrainDatabase,
    private val crypto: CryptoVault,
) {
    init {
        ensureSchema()
    }

    fun synchronizePending(maxConversations: Int = Int.MAX_VALUE): Int {
        var synchronized = 0
        database.readableDatabase.rawQuery(
            """
            SELECT c.id
            FROM conversations c
            LEFT JOIN search_index_state s ON s.conversation_id=c.id
            WHERE s.revision_hash IS NULL OR s.revision_hash<>c.revision_hash
            ORDER BY COALESCE(c.updated_at,c.created_at,0)
            LIMIT ?
            """.trimIndent(),
            arrayOf(maxConversations.coerceAtLeast(1).toString()),
        ).use { cursor ->
            val ids = ArrayList<String>()
            while (cursor.moveToNext()) ids += cursor.getString(0)
            ids.forEach {
                synchronizeConversation(it)
                synchronized++
            }
        }
        return synchronized
    }

    fun synchronizeConversation(conversationId: String): Boolean {
        val header = database.readableDatabase.rawQuery(
            "SELECT revision_hash,title_iv,title_ct FROM conversations WHERE id=?",
            arrayOf(conversationId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return false
            Triple(cursor.getString(0), cursor.getBlob(1), cursor.getBlob(2))
        }
        val already = database.readableDatabase.rawQuery(
            "SELECT revision_hash FROM search_index_state WHERE conversation_id=?",
            arrayOf(conversationId),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        if (already == header.first) return false

        val title = crypto.decryptString(
            EncryptedBlob(header.second, header.third),
            "conversation-title:$conversationId",
        )
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            db.delete("conversation_terms", "conversation_id=?", arrayOf(conversationId))
            KnowledgeExtractor.searchTerms(title).forEach { (term, weight) ->
                val values = ContentValues().apply {
                    put("conversation_id", conversationId)
                    put("term_hash", crypto.blind(term, "search-term"))
                    put("weight", weight + 2)
                }
                db.insertWithOnConflict(
                    "conversation_terms",
                    null,
                    values,
                    android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                )
            }

            db.rawQuery(
                "SELECT id,content_iv,content_ct FROM messages WHERE conversation_id=? ORDER BY ordinal,id",
                arrayOf(conversationId),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val messageId = cursor.getString(0)
                    val content = crypto.decryptString(
                        EncryptedBlob(cursor.getBlob(1), cursor.getBlob(2)),
                        "message-content:$messageId",
                    )
                    db.delete("message_terms", "message_id=?", arrayOf(messageId))
                    KnowledgeExtractor.searchTerms(content).forEach { (term, weight) ->
                        val values = ContentValues().apply {
                            put("message_id", messageId)
                            put("term_hash", crypto.blind(term, "search-term"))
                            put("weight", weight)
                        }
                        db.insertWithOnConflict(
                            "message_terms",
                            null,
                            values,
                            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                        )
                    }
                }
            }

            val state = ContentValues().apply {
                put("conversation_id", conversationId)
                put("revision_hash", header.first)
                put("indexed_at", nowSeconds())
            }
            db.insertWithOnConflict(
                "search_index_state",
                null,
                state,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return true
    }

    fun titleSearch(query: String, limit: Int = 20): List<SearchHit> {
        val terms = KnowledgeExtractor.queryTerms(query)
        if (terms.isEmpty()) return emptyList()
        val hashes = terms.map { crypto.blind(it, "search-term") }.distinct().take(32)
        val placeholders = hashes.joinToString(",") { "?" }
        val args = ArrayList<String>(hashes.size + 1).apply {
            addAll(hashes)
            add(limit.coerceIn(1, 100).toString())
        }
        val conversations = database.readableDatabase.rawQuery(
            """
            SELECT c.id,c.title_iv,c.title_ct,SUM(ct.weight) score
            FROM conversation_terms ct
            JOIN conversations c ON c.id=ct.conversation_id
            WHERE ct.term_hash IN ($placeholders)
            GROUP BY c.id
            ORDER BY score DESC,COALESCE(c.updated_at,c.created_at,0) DESC
            LIMIT ?
            """.trimIndent(),
            args.toTypedArray(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    add(
                        TitleMatch(
                            conversationId = id,
                            title = crypto.decryptString(
                                EncryptedBlob(cursor.getBlob(1), cursor.getBlob(2)),
                                "conversation-title:$id",
                            ),
                            score = cursor.getInt(3),
                        ),
                    )
                }
            }
        }

        return conversations.mapNotNull { match ->
            database.readableDatabase.rawQuery(
                """
                SELECT id,role,created_at,content_iv,content_ct
                FROM messages
                WHERE conversation_id=?
                ORDER BY COALESCE(created_at,updated_at,0) DESC,ordinal DESC
                LIMIT 1
                """.trimIndent(),
                arrayOf(match.conversationId),
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val messageId = cursor.getString(0)
                SearchHit(
                    messageId = messageId,
                    conversationId = match.conversationId,
                    conversationTitle = match.title,
                    role = cursor.getString(1),
                    createdAt = cursor.nullableDouble(2),
                    content = crypto.decryptString(
                        EncryptedBlob(cursor.getBlob(3), cursor.getBlob(4)),
                        "message-content:$messageId",
                    ),
                    score = match.score + TITLE_SCORE_BONUS,
                )
            }
        }
    }

    private fun ensureSchema() {
        val db = database.writableDatabase
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversation_terms (
                conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                term_hash TEXT NOT NULL,
                weight INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY(conversation_id,term_hash)
            ) WITHOUT ROWID
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_conversation_terms_term ON conversation_terms(term_hash)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS search_index_state (
                conversation_id TEXT PRIMARY KEY REFERENCES conversations(id) ON DELETE CASCADE,
                revision_hash TEXT NOT NULL,
                indexed_at REAL NOT NULL
            ) WITHOUT ROWID
            """.trimIndent(),
        )
    }

    private data class TitleMatch(
        val conversationId: String,
        val title: String,
        val score: Int,
    )

    private fun Cursor.nullableDouble(index: Int): Double? = if (isNull(index)) null else getDouble(index)
    private fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0

    companion object {
        private const val TITLE_SCORE_BONUS = 12
    }
}
