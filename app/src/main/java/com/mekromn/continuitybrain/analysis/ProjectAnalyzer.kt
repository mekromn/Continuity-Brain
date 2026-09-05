package com.mekromn.continuitybrain.analysis

import android.database.Cursor
import com.mekromn.continuitybrain.data.BrainDatabase
import com.mekromn.continuitybrain.data.CryptoVault
import com.mekromn.continuitybrain.data.EncryptedBlob
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Reconstructs a project from evidence already derived from the archive.
 *
 * This layer never invents missing decisions. Canonical requirements prefer
 * user-authored evidence and use chronology only within the same provenance
 * tier. Assistant-authored suggestions remain visible but do not silently
 * become authoritative project requirements.
 */
class ProjectAnalyzer(
    private val database: BrainDatabase,
    private val crypto: CryptoVault,
) {
    data class EvidenceFact(
        val id: String,
        val messageId: String,
        val kind: String,
        val payload: String,
        val role: String,
        val subjectHash: String,
        val polarity: Int,
        val confidence: Double,
        val timestamp: Double?,
        val conversationId: String,
    )

    data class Contradiction(
        val older: EvidenceFact,
        val newer: EvidenceFact,
    )

    data class RegressionCandidate(
        val previouslyWorking: EvidenceFact,
        val laterFailure: EvidenceFact,
    )

    data class BuildMatrixRow(
        val build: EvidenceFact,
        val nearbyResults: List<EvidenceFact>,
    )

    data class FailurePattern(
        val label: String,
        val count: Int,
        val evidenceMessageIds: List<String>,
    )

    data class CodeArtifact(
        val id: String,
        val messageId: String,
        val label: String,
        val language: String?,
        val content: String,
        val timestamp: Double?,
    )

    data class ProjectReport(
        val projectId: String,
        val name: String,
        val generatedAt: Double,
        val latestActivity: Double?,
        val hardInvariants: List<EvidenceFact>,
        val authoritativeRequirements: List<EvidenceFact>,
        val decisions: List<EvidenceFact>,
        val rejectedOrRemoved: List<EvidenceFact>,
        val unresolvedWork: List<EvidenceFact>,
        val abandonedIdeaCandidates: List<EvidenceFact>,
        val bugs: List<EvidenceFact>,
        val testResults: List<EvidenceFact>,
        val builds: List<EvidenceFact>,
        val buildMatrix: List<BuildMatrixRow>,
        val regressions: List<RegressionCandidate>,
        val contradictions: List<Contradiction>,
        val failurePatterns: List<FailurePattern>,
        val codeArtifacts: List<CodeArtifact>,
        val canonicalMarkdown: String,
    )

    fun analyze(projectId: String): ProjectReport {
        val name = projectName(projectId) ?: error("Project not found")
        val facts = readFacts(projectId)
        val latestActivity = facts.mapNotNull(EvidenceFact::timestamp).maxOrNull()

        val userFacts = facts.filter { it.role.equals("user", ignoreCase = true) }
        val authorityPool = if (userFacts.isNotEmpty()) userFacts else facts

        val hardInvariants = canonicalBySubject(
            authorityPool.filter { it.kind == "HARD_INVARIANT" },
        ).sortedWith(authorityComparator)

        val requirements = canonicalBySubject(
            authorityPool.filter { it.kind == "REQUIREMENT" && it.polarity >= 0 },
        ).sortedWith(authorityComparator)

        val decisions = canonicalBySubject(
            authorityPool.filter { it.kind == "DECISION" && it.polarity >= 0 },
        ).sortedWith(authorityComparator)

        val rejected = canonicalBySubject(
            authorityPool.filter {
                it.kind in setOf("REQUIREMENT", "DECISION", "HARD_INVARIANT") && it.polarity < 0
            },
        ).sortedWith(authorityComparator)

        val bugs = facts.filter { it.kind == "BUG" }.sortedByDescending { it.timestamp ?: 0.0 }
        val tests = facts.filter { it.kind == "TEST_RESULT" }.sortedByDescending { it.timestamp ?: 0.0 }
        val builds = facts.filter { it.kind == "BUILD" }.sortedByDescending { it.timestamp ?: 0.0 }
        val unresolved = unresolvedWork(authorityPool, facts)
        val abandoned = abandonedCandidates(unresolved, facts, latestActivity)
        val contradictions = contradictionPairs(projectId, facts)
        val regressions = regressionCandidates(facts)
        val buildMatrix = buildMatrix(builds, tests)
        val failurePatterns = failurePatterns(bugs)
        val artifacts = codeArtifacts(projectId)

        val base = ProjectReport(
            projectId = projectId,
            name = name,
            generatedAt = nowSeconds(),
            latestActivity = latestActivity,
            hardInvariants = hardInvariants,
            authoritativeRequirements = requirements,
            decisions = decisions,
            rejectedOrRemoved = rejected,
            unresolvedWork = unresolved,
            abandonedIdeaCandidates = abandoned,
            bugs = bugs,
            testResults = tests,
            builds = builds,
            buildMatrix = buildMatrix,
            regressions = regressions,
            contradictions = contradictions,
            failurePatterns = failurePatterns,
            codeArtifacts = artifacts,
            canonicalMarkdown = "",
        )
        return base.copy(canonicalMarkdown = renderMarkdown(base))
    }

    private fun projectName(projectId: String): String? = database.readableDatabase.rawQuery(
        "SELECT name_iv,name_ct FROM projects WHERE id=?",
        arrayOf(projectId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        crypto.decryptString(
            EncryptedBlob(cursor.getBlob(0), cursor.getBlob(1)),
            "project-name:$projectId",
        )
    }

    private fun readFacts(projectId: String): List<EvidenceFact> = database.readableDatabase.rawQuery(
        """
        SELECT i.id,i.message_id,i.kind,i.payload_iv,i.payload_ct,i.subject_hash,
               i.polarity,i.confidence,i.created_at,m.role,m.conversation_id
        FROM insights i
        JOIN messages m ON m.id=i.message_id
        WHERE i.project_id=? AND i.status='active'
        ORDER BY COALESCE(i.created_at,0),i.id
        """.trimIndent(),
        arrayOf(projectId),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                add(
                    EvidenceFact(
                        id = id,
                        messageId = cursor.getString(1),
                        kind = cursor.getString(2),
                        payload = crypto.decryptString(
                            EncryptedBlob(cursor.getBlob(3), cursor.getBlob(4)),
                            "insight:$id",
                        ),
                        subjectHash = cursor.getString(5),
                        polarity = cursor.getInt(6),
                        confidence = cursor.getDouble(7),
                        timestamp = cursor.nullableDouble(8),
                        role = cursor.getString(9),
                        conversationId = cursor.getString(10),
                    ),
                )
            }
        }
    }

    /** Latest user-authored fact wins within a derived subject bucket. */
    private fun canonicalBySubject(facts: List<EvidenceFact>): List<EvidenceFact> = facts
        .groupBy(EvidenceFact::subjectHash)
        .values
        .mapNotNull { group ->
            group.maxWithOrNull(
                compareBy<EvidenceFact> { authorityWeight(it.role) }
                    .thenBy { it.timestamp ?: Double.NEGATIVE_INFINITY }
                    .thenBy { it.confidence },
            )
        }

    private fun unresolvedWork(authorityPool: List<EvidenceFact>, allFacts: List<EvidenceFact>): List<EvidenceFact> {
        val candidates = authorityPool.filter { it.kind in setOf("TODO", "REQUIREMENT") && it.polarity >= 0 }
        return canonicalBySubject(candidates).filter { candidate ->
            val candidateTime = candidate.timestamp ?: Double.NEGATIVE_INFINITY
            allFacts.none { later ->
                later.subjectHash == candidate.subjectHash &&
                    (later.timestamp ?: Double.NEGATIVE_INFINITY) > candidateTime &&
                    (
                        (later.kind == "TEST_RESULT" && later.polarity > 0) ||
                            (later.kind == "BUILD" && later.polarity > 0) ||
                            (later.kind == "DECISION" && later.polarity < 0)
                    )
            }
        }.sortedByDescending { it.timestamp ?: 0.0 }
    }

    private fun abandonedCandidates(
        unresolved: List<EvidenceFact>,
        allFacts: List<EvidenceFact>,
        latestActivity: Double?,
    ): List<EvidenceFact> {
        val latest = latestActivity ?: return emptyList()
        val threshold = latest - ABANDONED_AFTER_SECONDS
        return unresolved.filter { candidate ->
            val timestamp = candidate.timestamp ?: return@filter false
            timestamp <= threshold && allFacts.none { fact ->
                fact.timestamp != null && fact.timestamp > timestamp &&
                    fact.subjectHash == candidate.subjectHash &&
                    fact.kind in setOf("DECISION", "BUILD", "TEST_RESULT")
            }
        }
    }

    private fun contradictionPairs(projectId: String, factList: List<EvidenceFact>): List<Contradiction> {
        val byId = factList.associateBy(EvidenceFact::id)
        return database.readableDatabase.rawQuery(
            "SELECT from_ref,to_ref FROM edges WHERE project_id=? AND kind='CONTRADICTS' ORDER BY created_at DESC",
            arrayOf(projectId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val from = byId[cursor.getString(0)] ?: continue
                    val to = byId[cursor.getString(1)] ?: continue
                    val ordered = listOf(from, to).sortedBy { it.timestamp ?: 0.0 }
                    add(Contradiction(ordered[0], ordered[1]))
                }
            }
        }.distinctBy { "${it.older.id}|${it.newer.id}" }
    }

    private fun regressionCandidates(facts: List<EvidenceFact>): List<RegressionCandidate> {
        val outcomeFacts = facts.filter {
            it.kind in setOf("TEST_RESULT", "BUILD") && it.polarity != 0
        }.groupBy(EvidenceFact::subjectHash)

        val output = ArrayList<RegressionCandidate>()
        for (group in outcomeFacts.values) {
            val ordered = group.sortedBy { it.timestamp ?: 0.0 }
            var lastSuccess: EvidenceFact? = null
            for (fact in ordered) {
                if (fact.polarity > 0) {
                    lastSuccess = fact
                } else if (fact.polarity < 0 && lastSuccess != null) {
                    output += RegressionCandidate(lastSuccess, fact)
                }
            }
        }
        return output.sortedByDescending { it.laterFailure.timestamp ?: 0.0 }.take(100)
    }

    private fun buildMatrix(
        builds: List<EvidenceFact>,
        tests: List<EvidenceFact>,
    ): List<BuildMatrixRow> = builds.take(150).map { build ->
        val buildTime = build.timestamp
        val candidates = tests.asSequence()
            .filter { test ->
                if (buildTime == null || test.timestamp == null) return@filter false
                test.timestamp >= buildTime && test.timestamp - buildTime <= BUILD_TEST_WINDOW_SECONDS
            }
            .sortedBy { it.timestamp }
            .take(4)
            .toList()
        BuildMatrixRow(build, candidates)
    }

    private fun failurePatterns(bugs: List<EvidenceFact>): List<FailurePattern> {
        val buckets = linkedMapOf(
            "Install/signing failures" to listOf("app not installed", "install_parse", "signature", "certificate", "signing"),
            "Startup crashes" to listOf("startup crash", "crash at startup", "crashes at startup", "launch crash"),
            "Runtime crashes" to listOf("crash", "crashes", "crashed", "exception"),
            "Black screen/viewfinder" to listOf("black screen", "black viewfinder"),
            "Save/output failures" to listOf("not saving", "doesn't save", "does not save", "save bug", "failed to save"),
            "Freeze/hang" to listOf("freeze", "frozen", "hang", "stuck"),
            "Missing UI/option" to listOf("missing", "not in", "don't see", "do not see"),
        )
        return buckets.mapNotNull { (label, signals) ->
            val matches = bugs.filter { bug ->
                val normalized = bug.payload.lowercase(Locale.ROOT)
                signals.any(normalized::contains)
            }
            if (matches.isEmpty()) null else FailurePattern(
                label = label,
                count = matches.size,
                evidenceMessageIds = matches.map(EvidenceFact::messageId).distinct().take(30),
            )
        }.sortedByDescending(FailurePattern::count)
    }

    private fun codeArtifacts(projectId: String): List<CodeArtifact> = database.readableDatabase.rawQuery(
        """
        SELECT a.id,a.message_id,a.label_iv,a.label_ct,a.language,a.content_iv,a.content_ct,a.created_at
        FROM artifacts a
        WHERE a.project_id=? AND a.kind='CODE'
        ORDER BY COALESCE(a.created_at,0) DESC
        LIMIT 300
        """.trimIndent(),
        arrayOf(projectId),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                add(
                    CodeArtifact(
                        id = id,
                        messageId = cursor.getString(1),
                        label = crypto.decryptString(
                            EncryptedBlob(cursor.getBlob(2), cursor.getBlob(3)),
                            "artifact-label:$id",
                        ),
                        language = cursor.nullableString(4),
                        content = crypto.decryptString(
                            EncryptedBlob(cursor.getBlob(5), cursor.getBlob(6)),
                            "artifact-content:$id",
                        ),
                        timestamp = cursor.nullableDouble(7),
                    ),
                )
            }
        }
    }

    private fun renderMarkdown(report: ProjectReport): String = buildString {
        appendLine("# ${report.name} — Continuity Brain Project State")
        appendLine()
        appendLine("Generated locally from evidence in the private ChatGPT archive.")
        report.latestActivity?.let { appendLine("Latest recorded activity: ${formatTime(it)}") }
        appendLine()
        appendLine("## Authority rule")
        appendLine("User-authored historical instructions outrank assistant suggestions. Within the same authority tier and derived subject, the newest evidence is treated as the current candidate requirement. Contradictions are listed explicitly rather than silently merged.")
        appendLine()

        markdownFacts("Hard invariants", report.hardInvariants)
        markdownFacts("Current requirements", report.authoritativeRequirements)
        markdownFacts("Decisions", report.decisions)
        markdownFacts("Rejected / removed / prohibited", report.rejectedOrRemoved)
        markdownFacts("Unresolved work candidates", report.unresolvedWork)

        appendLine("## Contradictions")
        if (report.contradictions.isEmpty()) {
            appendLine("- No contradiction edges are currently detected.")
        } else {
            report.contradictions.forEach { pair ->
                appendLine("- Older [evidence:${pair.older.messageId}]: ${pair.older.payload}")
                appendLine("  Newer [evidence:${pair.newer.messageId}]: ${pair.newer.payload}")
            }
        }
        appendLine()

        appendLine("## Regression candidates")
        if (report.regressions.isEmpty()) {
            appendLine("- No same-subject working→failure sequence is currently detected.")
        } else {
            report.regressions.forEach { regression ->
                appendLine("- Previously working [evidence:${regression.previouslyWorking.messageId}]: ${regression.previouslyWorking.payload}")
                appendLine("  Later failure [evidence:${regression.laterFailure.messageId}]: ${regression.laterFailure.payload}")
            }
        }
        appendLine()

        appendLine("## Build / test matrix")
        if (report.buildMatrix.isEmpty()) {
            appendLine("- No build records extracted yet.")
        } else {
            report.buildMatrix.forEach { row ->
                appendLine("- Build [evidence:${row.build.messageId}]: ${row.build.payload}")
                row.nearbyResults.forEach { result ->
                    appendLine("  - ${if (result.polarity > 0) "PASS" else if (result.polarity < 0) "FAIL" else "OBS"} [evidence:${result.messageId}]: ${result.payload}")
                }
            }
        }
        appendLine()

        appendLine("## Recurring failure patterns")
        if (report.failurePatterns.isEmpty()) {
            appendLine("- No recurring bug pattern has enough extracted evidence yet.")
        } else {
            report.failurePatterns.forEach { pattern ->
                appendLine("- ${pattern.label}: ${pattern.count} extracted bug observations (${pattern.evidenceMessageIds.joinToString { "evidence:$it" }})")
            }
        }
        appendLine()

        appendLine("## Abandoned-idea candidates")
        appendLine("These are heuristics, not declarations: old unresolved items with no later same-subject implementation/test/decision evidence.")
        if (report.abandonedIdeaCandidates.isEmpty()) {
            appendLine("- None currently detected.")
        } else {
            report.abandonedIdeaCandidates.forEach { fact ->
                appendLine("- [evidence:${fact.messageId}] ${fact.payload}")
            }
        }
        appendLine()

        appendLine("## Extracted code")
        appendLine("- ${report.codeArtifacts.size} code artifacts are linked to this project in the local vault.")
    }

    private fun StringBuilder.markdownFacts(title: String, facts: List<EvidenceFact>) {
        appendLine("## $title")
        if (facts.isEmpty()) {
            appendLine("- None currently supported by extracted evidence.")
        } else {
            facts.forEach { fact ->
                append("- [evidence:").append(fact.messageId).append("] ")
                append(fact.payload)
                fact.timestamp?.let { append(" _(recorded ").append(formatTime(it)).append(")_") }
                appendLine()
            }
        }
        appendLine()
    }

    private fun authorityWeight(role: String): Int = when {
        role.equals("user", true) -> 3
        role.equals("system", true) -> 2
        role.equals("assistant", true) -> 1
        else -> 0
    }

    private fun formatTime(epochSeconds: Double): String = runCatching {
        val instant = Instant.ofEpochMilli((epochSeconds * 1000.0).toLong())
        DATE_FORMAT.format(instant.atZone(ZoneId.systemDefault()))
    }.getOrDefault(epochSeconds.toString())

    private fun Cursor.nullableDouble(index: Int): Double? = if (isNull(index)) null else getDouble(index)
    private fun Cursor.nullableString(index: Int): String? = if (isNull(index)) null else getString(index)
    private fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0

    private val authorityComparator = compareByDescending<EvidenceFact> { authorityWeight(it.role) }
        .thenByDescending { it.timestamp ?: 0.0 }
        .thenByDescending(EvidenceFact::confidence)

    companion object {
        private const val ABANDONED_AFTER_SECONDS = 14.0 * 24.0 * 60.0 * 60.0
        private const val BUILD_TEST_WINDOW_SECONDS = 36.0 * 60.0 * 60.0
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
