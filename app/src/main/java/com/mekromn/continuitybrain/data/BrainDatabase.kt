package com.mekromn.continuitybrain.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite schema for Continuity Brain.
 *
 * Sensitive human-readable values are encrypted by [CryptoVault] before they are
 * inserted. Plaintext columns are restricted to structural/provenance values,
 * opaque IDs, timestamps, enums, counts, hashes, and blind HMAC indexes.
 */
class BrainDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE imports (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                archive_hash TEXT NOT NULL UNIQUE,
                imported_at REAL NOT NULL,
                conversations_seen INTEGER NOT NULL DEFAULT 0,
                messages_seen INTEGER NOT NULL DEFAULT 0,
                attachments_seen INTEGER NOT NULL DEFAULT 0,
                added INTEGER NOT NULL DEFAULT 0,
                updated INTEGER NOT NULL DEFAULT 0,
                unchanged INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE conversations (
                id TEXT PRIMARY KEY,
                title_iv BLOB NOT NULL,
                title_ct BLOB NOT NULL,
                created_at REAL,
                updated_at REAL,
                current_node_id TEXT,
                revision_hash TEXT NOT NULL,
                source TEXT NOT NULL DEFAULT 'export',
                last_seen_at REAL NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_conversations_updated ON conversations(updated_at DESC)")

        db.execSQL(
            """
            CREATE TABLE messages (
                id TEXT PRIMARY KEY,
                conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                parent_id TEXT,
                role TEXT NOT NULL,
                created_at REAL,
                updated_at REAL,
                content_iv BLOB NOT NULL,
                content_ct BLOB NOT NULL,
                content_type TEXT,
                ordinal INTEGER NOT NULL,
                content_hash TEXT NOT NULL,
                source TEXT NOT NULL DEFAULT 'export',
                last_seen_at REAL NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_messages_conversation ON messages(conversation_id, ordinal)")
        db.execSQL("CREATE INDEX idx_messages_created ON messages(created_at DESC)")
        db.execSQL("CREATE INDEX idx_messages_hash ON messages(content_hash)")

        db.execSQL(
            """
            CREATE TABLE message_terms (
                message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
                term_hash TEXT NOT NULL,
                weight INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY(message_id, term_hash)
            ) WITHOUT ROWID
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_message_terms_term ON message_terms(term_hash)")

        db.execSQL(
            """
            CREATE TABLE projects (
                id TEXT PRIMARY KEY,
                canonical_hash TEXT NOT NULL UNIQUE,
                name_iv BLOB NOT NULL,
                name_ct BLOB NOT NULL,
                created_at REAL NOT NULL,
                updated_at REAL NOT NULL
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE project_conversations (
                project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
                conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                confidence REAL NOT NULL,
                basis TEXT NOT NULL,
                PRIMARY KEY(project_id, conversation_id)
            ) WITHOUT ROWID
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_project_conversations_conversation ON project_conversations(conversation_id)")

        db.execSQL(
            """
            CREATE TABLE insights (
                id TEXT PRIMARY KEY,
                project_id TEXT REFERENCES projects(id) ON DELETE SET NULL,
                message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
                kind TEXT NOT NULL,
                payload_iv BLOB NOT NULL,
                payload_ct BLOB NOT NULL,
                subject_hash TEXT NOT NULL,
                polarity INTEGER NOT NULL DEFAULT 0,
                confidence REAL NOT NULL,
                status TEXT NOT NULL DEFAULT 'active',
                created_at REAL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_insights_project_kind ON insights(project_id, kind, created_at DESC)")
        db.execSQL("CREATE INDEX idx_insights_subject ON insights(project_id, subject_hash, polarity)")
        db.execSQL("CREATE INDEX idx_insights_message ON insights(message_id)")

        db.execSQL(
            """
            CREATE TABLE edges (
                id TEXT PRIMARY KEY,
                project_id TEXT REFERENCES projects(id) ON DELETE CASCADE,
                from_ref TEXT NOT NULL,
                to_ref TEXT NOT NULL,
                kind TEXT NOT NULL,
                confidence REAL NOT NULL,
                created_at REAL NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_edges_project_kind ON edges(project_id, kind)")

        db.execSQL(
            """
            CREATE TABLE artifacts (
                id TEXT PRIMARY KEY,
                project_id TEXT REFERENCES projects(id) ON DELETE SET NULL,
                message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
                kind TEXT NOT NULL,
                language TEXT,
                label_iv BLOB NOT NULL,
                label_ct BLOB NOT NULL,
                content_iv BLOB NOT NULL,
                content_ct BLOB NOT NULL,
                content_hash TEXT NOT NULL,
                created_at REAL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_artifacts_project_kind ON artifacts(project_id, kind, created_at DESC)")
        db.execSQL("CREATE INDEX idx_artifacts_message ON artifacts(message_id)")

        db.execSQL(
            """
            CREATE TABLE attachments (
                id TEXT PRIMARY KEY,
                entry_name_iv BLOB NOT NULL,
                entry_name_ct BLOB NOT NULL,
                mime_type TEXT,
                size_bytes INTEGER NOT NULL,
                sha256 TEXT NOT NULL,
                encrypted_path TEXT NOT NULL,
                imported_at REAL NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_attachments_sha ON attachments(sha256)")

        db.execSQL(
            """
            CREATE TABLE attachment_refs (
                message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
                attachment_id TEXT NOT NULL REFERENCES attachments(id) ON DELETE CASCADE,
                PRIMARY KEY(message_id, attachment_id)
            ) WITHOUT ROWID
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE settings (
                key TEXT PRIMARY KEY,
                value_iv BLOB NOT NULL,
                value_ct BLOB NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Schema starts at v1. Future versions must use explicit, lossless
        // migrations because the database is the user's durable project memory.
        if (oldVersion != newVersion) {
            throw IllegalStateException(
                "Unsupported Continuity Brain database migration $oldVersion -> $newVersion",
            )
        }
    }

    companion object {
        private const val DATABASE_NAME = "continuity-brain.db"
        private const val DATABASE_VERSION = 1
    }
}
