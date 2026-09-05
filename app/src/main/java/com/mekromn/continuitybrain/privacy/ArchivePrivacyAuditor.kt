package com.mekromn.continuitybrain.privacy

import android.database.Cursor
import com.mekromn.continuitybrain.data.BrainDatabase
import com.mekromn.continuitybrain.data.CryptoVault
import com.mekromn.continuitybrain.data.EncryptedBlob
import java.util.Locale

/**
 * Local-only privacy scanner for archive text.
 *
 * Findings intentionally contain evidence IDs and short redacted previews, not
 * full matched secrets. Nothing is uploaded and the scanner does not persist a
 * plaintext mirror of its results.
 */
class ArchivePrivacyAuditor(
    private val database: BrainDatabase,
    private val crypto: CryptoVault,
) {
    enum class Severity { INFO, MEDIUM, HIGH, CRITICAL }

    enum class Kind {
        PRIVATE_KEY,
        API_KEY_OR_TOKEN,
        JWT,
        PASSWORD_ASSIGNMENT,
        EMAIL_ADDRESS,
        PHONE_NUMBER,
        IPV4_ADDRESS,
        URL_WITH_CREDENTIALS,
        CLOUD_CREDENTIAL_ID,
    }

    data class Finding(
        val kind: Kind,
        val severity: Severity,
        val messageId: String,
        val conversationId: String,
        val conversationTitle: String,
        val preview: String,
        val timestamp: Double?,
    )

    data class Report(
        val messagesScanned: Int,
        val findings: List<Finding>,
    ) {
        val criticalCount: Int get() = findings.count { it.severity == Severity.CRITICAL }
        val highCount: Int get() = findings.count { it.severity == Severity.HIGH }
        val mediumCount: Int get() = findings.count { it.severity == Severity.MEDIUM }
    }

    fun scan(maxFindings: Int = 5_000): Report {
        val findings = ArrayList<Finding>()
        var scanned = 0
        database.readableDatabase.rawQuery(
            """
            SELECT m.id,m.conversation_id,m.created_at,m.content_iv,m.content_ct,c.title_iv,c.title_ct
            FROM messages m
            JOIN conversations c ON c.id=m.conversation_id
            ORDER BY COALESCE(m.created_at,m.updated_at,0),m.id
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext() && findings.size < maxFindings.coerceIn(1, 50_000)) {
                scanned++
                val messageId = cursor.getString(0)
                val conversationId = cursor.getString(1)
                val content = crypto.decryptString(
                    EncryptedBlob(cursor.getBlob(3), cursor.getBlob(4)),
                    "message-content:$messageId",
                )
                if (content.isBlank()) continue
                val title = crypto.decryptString(
                    EncryptedBlob(cursor.getBlob(5), cursor.getBlob(6)),
                    "conversation-title:$conversationId",
                )
                val timestamp = cursor.nullableDouble(2)
                RULES.forEach { rule ->
                    if (findings.size >= maxFindings) return@forEach
                    rule.regex.findAll(content).take(MAX_MATCHES_PER_RULE_PER_MESSAGE).forEach { match ->
                        if (!rule.accept(match.value)) return@forEach
                        findings += Finding(
                            kind = rule.kind,
                            severity = rule.severity,
                            messageId = messageId,
                            conversationId = conversationId,
                            conversationTitle = title,
                            preview = redactedPreview(content, match.range, rule.kind),
                            timestamp = timestamp,
                        )
                    }
                }
            }
        }
        return Report(scanned, findings)
    }

    private fun redactedPreview(text: String, range: IntRange, kind: Kind): String {
        val left = (range.first - PREVIEW_CONTEXT).coerceAtLeast(0)
        val right = (range.last + 1 + PREVIEW_CONTEXT).coerceAtMost(text.length)
        val before = text.substring(left, range.first).replace(Regex("\\s+"), " ").trim()
        val after = text.substring(range.last + 1, right).replace(Regex("\\s+"), " ").trim()
        return buildString {
            if (left > 0) append("…")
            append(before)
            if (before.isNotBlank()) append(' ')
            append('[').append(kind.name).append(" REDACTED]")
            if (after.isNotBlank()) append(' ').append(after)
            if (right < text.length) append("…")
        }.take(MAX_PREVIEW_CHARS)
    }

    private data class Rule(
        val kind: Kind,
        val severity: Severity,
        val regex: Regex,
        val accept: (String) -> Boolean = { true },
    )

    companion object {
        private const val MAX_MATCHES_PER_RULE_PER_MESSAGE = 4
        private const val PREVIEW_CONTEXT = 36
        private const val MAX_PREVIEW_CHARS = 180

        private val RULES = listOf(
            Rule(
                Kind.PRIVATE_KEY,
                Severity.CRITICAL,
                Regex("-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----", RegexOption.IGNORE_CASE),
            ),
            Rule(
                Kind.JWT,
                Severity.HIGH,
                Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b"),
            ),
            Rule(
                Kind.API_KEY_OR_TOKEN,
                Severity.HIGH,
                Regex(
                    "(?i)\\b(?:api[_ -]?key|access[_ -]?token|auth[_ -]?token|secret[_ -]?key|client[_ -]?secret|bearer)\\b\\s*[:=]\\s*['\\\"]?([A-Za-z0-9_./+\\-=]{12,})",
                ),
            ),
            Rule(
                Kind.API_KEY_OR_TOKEN,
                Severity.HIGH,
                Regex("\\b(?:sk|pk)-[A-Za-z0-9_-]{20,}\\b", RegexOption.IGNORE_CASE),
            ),
            Rule(
                Kind.PASSWORD_ASSIGNMENT,
                Severity.HIGH,
                Regex("(?i)\\b(?:password|passwd|pwd)\\b\\s*[:=]\\s*['\\\"]?([^\\s'\\\"]{6,})"),
            ),
            Rule(
                Kind.URL_WITH_CREDENTIALS,
                Severity.HIGH,
                Regex("https?://[^\\s/@:]+:[^\\s/@]+@[^\\s]+", RegexOption.IGNORE_CASE),
            ),
            Rule(
                Kind.CLOUD_CREDENTIAL_ID,
                Severity.HIGH,
                Regex("\\bAKIA[0-9A-Z]{16}\\b"),
            ),
            Rule(
                Kind.EMAIL_ADDRESS,
                Severity.MEDIUM,
                Regex("\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,24}\\b", RegexOption.IGNORE_CASE),
            ),
            Rule(
                Kind.PHONE_NUMBER,
                Severity.MEDIUM,
                Regex("(?<!\\d)(?:\\+?1[-. ]?)?\\(?\\d{3}\\)?[-. ]?\\d{3}[-. ]?\\d{4}(?!\\d)"),
            ),
            Rule(
                Kind.IPV4_ADDRESS,
                Severity.INFO,
                Regex("(?<!\\d)(?:\\d{1,3}\\.){3}\\d{1,3}(?!\\d)"),
                accept = { value ->
                    value.split('.').all { segment -> segment.toIntOrNull()?.let { it in 0..255 } == true }
                },
            ),
        )
    }

    private fun Cursor.nullableDouble(index: Int): Double? = if (isNull(index)) null else getDouble(index)
}
