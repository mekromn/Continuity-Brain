package com.mekromn.continuitybrain.analysis

import android.database.Cursor
import com.mekromn.continuitybrain.data.BrainDatabase
import com.mekromn.continuitybrain.data.CryptoVault
import com.mekromn.continuitybrain.data.EncryptedBlob
import java.time.Instant
import java.time.ZoneId
import java.time.YearMonth

/** Aggregate statistics calculated locally from structural data and decrypted project names. */
class BrainAnalytics(
    private val database: BrainDatabase,
    private val crypto: CryptoVault,
) {
    data class MonthActivity(
        val month: YearMonth,
        val messages: Int,
        val conversationsStarted: Int,
        val userMessages: Int,
        val assistantMessages: Int,
    )

    data class ProjectActivity(
        val projectId: String,
        val name: String,
        val messages: Int,
        val bugs: Int,
        val builds: Int,
        val tests: Int,
        val positiveTests: Int,
        val negativeTests: Int,
        val unresolved: Int,
        val latestActivity: Double?,
    ) {
        val observedTestPassRate: Double?
            get() {
                val decisive = positiveTests + negativeTests
                return if (decisive == 0) null else positiveTests.toDouble() / decisive
            }
    }

    data class Overview(
        val totalMessages: Int,
        val userMessages: Int,
        val assistantMessages: Int,
        val firstMessageAt: Double?,
        val lastMessageAt: Double?,
        val monthly: List<MonthActivity>,
        val projects: List<ProjectActivity>,
    )

    fun overview(): Overview {
        val roleCounts = database.readableDatabase.rawQuery(
            "SELECT role,COUNT(*) FROM messages GROUP BY role",
            null,
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), cursor.getInt(1))
            }
        }
        val range = database.readableDatabase.rawQuery(
            "SELECT MIN(COALESCE(created_at,updated_at)),MAX(COALESCE(created_at,updated_at)) FROM messages",
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) null to null
            else cursor.nullableDouble(0) to cursor.nullableDouble(1)
        }

        return Overview(
            totalMessages = roleCounts.values.sum(),
            userMessages = roleCounts.entries.filter { it.key.equals("user", true) }.sumOf { it.value },
            assistantMessages = roleCounts.entries.filter { it.key.equals("assistant", true) }.sumOf { it.value },
            firstMessageAt = range.first,
            lastMessageAt = range.second,
            monthly = monthlyActivity(),
            projects = projectActivity(),
        )
    }

    fun monthlyActivity(zoneId: ZoneId = ZoneId.systemDefault()): List<MonthActivity> {
        data class MutableMonth(
            var messages: Int = 0,
            var conversations: Int = 0,
            var user: Int = 0,
            var assistant: Int = 0,
        )
        val months = sortedMapOf<YearMonth, MutableMonth>()
        database.readableDatabase.rawQuery(
            "SELECT role,created_at,updated_at FROM messages WHERE COALESCE(created_at,updated_at) IS NOT NULL",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val timestamp = if (!cursor.isNull(1)) cursor.getDouble(1) else cursor.getDouble(2)
                val month = YearMonth.from(
                    Instant.ofEpochMilli((timestamp * 1000.0).toLong()).atZone(zoneId),
                )
                val item = months.getOrPut(month) { MutableMonth() }
                item.messages++
                when (cursor.getString(0).lowercase()) {
                    "user" -> item.user++
                    "assistant" -> item.assistant++
                }
            }
        }
        database.readableDatabase.rawQuery(
            "SELECT created_at,updated_at FROM conversations WHERE COALESCE(created_at,updated_at) IS NOT NULL",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val timestamp = if (!cursor.isNull(0)) cursor.getDouble(0) else cursor.getDouble(1)
                val month = YearMonth.from(
                    Instant.ofEpochMilli((timestamp * 1000.0).toLong()).atZone(zoneId),
                )
                months.getOrPut(month) { MutableMonth() }.conversations++
            }
        }
        return months.map { (month, value) ->
            MonthActivity(month, value.messages, value.conversations, value.user, value.assistant)
        }
    }

    fun projectActivity(limit: Int = 200): List<ProjectActivity> {
        return database.readableDatabase.rawQuery(
            """
            SELECT p.id,p.name_iv,p.name_ct,
                   COUNT(DISTINCT m.id) messages,
                   COUNT(DISTINCT CASE WHEN i.kind='BUG' THEN i.id END) bugs,
                   COUNT(DISTINCT CASE WHEN i.kind='BUILD' THEN i.id END) builds,
                   COUNT(DISTINCT CASE WHEN i.kind='TEST_RESULT' THEN i.id END) tests,
                   COUNT(DISTINCT CASE WHEN i.kind='TEST_RESULT' AND i.polarity>0 THEN i.id END) positive_tests,
                   COUNT(DISTINCT CASE WHEN i.kind='TEST_RESULT' AND i.polarity<0 THEN i.id END) negative_tests,
                   COUNT(DISTINCT CASE WHEN i.kind IN ('TODO','REQUIREMENT') AND i.polarity>=0 THEN i.id END) unresolved_candidates,
                   MAX(COALESCE(m.created_at,m.updated_at)) latest
            FROM projects p
            LEFT JOIN project_conversations pc ON pc.project_id=p.id
            LEFT JOIN messages m ON m.conversation_id=pc.conversation_id
            LEFT JOIN insights i ON i.project_id=p.id AND i.status='active'
            GROUP BY p.id
            ORDER BY latest DESC,messages DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.coerceIn(1, 500).toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val projectId = cursor.getString(0)
                    add(
                        ProjectActivity(
                            projectId = projectId,
                            name = crypto.decryptString(
                                EncryptedBlob(cursor.getBlob(1), cursor.getBlob(2)),
                                "project-name:$projectId",
                            ),
                            messages = cursor.getInt(3),
                            bugs = cursor.getInt(4),
                            builds = cursor.getInt(5),
                            tests = cursor.getInt(6),
                            positiveTests = cursor.getInt(7),
                            negativeTests = cursor.getInt(8),
                            unresolved = cursor.getInt(9),
                            latestActivity = cursor.nullableDouble(10),
                        ),
                    )
                }
            }
        }
    }

    private fun Cursor.nullableDouble(index: Int): Double? = if (isNull(index)) null else getDouble(index)
}
