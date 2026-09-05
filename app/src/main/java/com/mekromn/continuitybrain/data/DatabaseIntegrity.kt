package com.mekromn.continuitybrain.data

import android.database.sqlite.SQLiteDatabase

/**
 * Idempotent integrity rules that complement foreign keys.
 *
 * Edges deliberately reference heterogeneous record IDs, so SQLite cannot use
 * a normal FK for both ends. Live Continuity ingestion also uses provisional
 * message IDs that can later be superseded by official ChatGPT export IDs.
 */
object DatabaseIntegrity {
    fun install(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS cleanup_edges_before_insight_delete
                BEFORE DELETE ON insights
                BEGIN
                    DELETE FROM edges
                    WHERE from_ref=OLD.id OR to_ref=OLD.id;
                END
                """.trimIndent(),
            )

            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS reconcile_live_message_after_export_insert
                AFTER INSERT ON messages
                WHEN NEW.source='export'
                BEGIN
                    DELETE FROM messages
                    WHERE id<>NEW.id
                      AND source='continuity-live'
                      AND conversation_id=NEW.conversation_id
                      AND role=NEW.role
                      AND ordinal=NEW.ordinal
                      AND content_hash=NEW.content_hash;
                END
                """.trimIndent(),
            )

            // Clean up rows produced before these triggers existed. Avoid a
            // DELETE target alias here so the query remains valid on the older
            // SQLite versions shipped by the minimum supported Android API.
            db.execSQL(
                """
                DELETE FROM edges
                WHERE (from_ref NOT IN (SELECT id FROM insights))
                   OR (to_ref NOT IN (SELECT id FROM insights))
                """.trimIndent(),
            )
            db.execSQL(
                """
                DELETE FROM messages
                WHERE source='continuity-live'
                  AND EXISTS (
                    SELECT 1 FROM messages AS exported
                    WHERE exported.source='export'
                      AND exported.id<>messages.id
                      AND exported.conversation_id=messages.conversation_id
                      AND exported.role=messages.role
                      AND exported.ordinal=messages.ordinal
                      AND exported.content_hash=messages.content_hash
                  )
                """.trimIndent(),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
