package com.mekromn.continuitybrain.export

import com.mekromn.continuitybrain.analysis.ProjectAnalyzer
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Converts one reconstructed project into a portable, human-readable knowledge
 * pack. The output exists only where the user explicitly saves it; nothing from
 * this exporter is compiled into or committed to the public source repository.
 */
class ProjectPackExporter(
    private val analyzer: ProjectAnalyzer,
) {
    data class Summary(
        val projectName: String,
        val filesWritten: Int,
        val codeArtifacts: Int,
    )

    fun write(projectId: String, output: OutputStream): Summary {
        val report = analyzer.analyze(projectId)
        var files = 0
        ZipOutputStream(output, StandardCharsets.UTF_8).use { zip ->
            fun text(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
                files++
            }

            text("README.md", renderReadme(report))
            text("PROJECT_STATE.md", report.canonicalMarkdown)
            text("ROADMAP.md", renderRoadmap(report))
            text("DESIGN_DECISIONS.md", renderDecisions(report))
            text("KNOWN_ISSUES.md", renderIssues(report))
            text("BUILD_TEST_MATRIX.md", renderBuildMatrix(report))
            text("REGRESSIONS.md", renderRegressions(report))
            text("PROCESS_PATTERNS.md", renderProcessPatterns(report))
            text("project.json", renderJson(report).toString(2))

            val usedNames = HashSet<String>()
            report.codeArtifacts.forEachIndexed { index, artifact ->
                val extension = languageExtension(artifact.language)
                var stem = sanitizeFileStem(artifact.label).take(70).ifBlank { "snippet-${index + 1}" }
                var candidate = "$stem$extension"
                var suffix = 2
                while (!usedNames.add(candidate.lowercase(Locale.ROOT))) {
                    candidate = "$stem-$suffix$extension"
                    suffix++
                }
                text("code/$candidate", buildString {
                    append("// Continuity Brain evidence: ").append(artifact.messageId).append('\n')
                    artifact.language?.let { append("// Extracted language: ").append(it).append('\n') }
                    append('\n').append(artifact.content)
                    if (!artifact.content.endsWith('\n')) append('\n')
                })
            }
        }
        return Summary(report.name, files, report.codeArtifacts.size)
    }

    private fun renderReadme(report: ProjectAnalyzer.ProjectReport): String = buildString {
        appendLine("# ${report.name}")
        appendLine()
        appendLine("Generated locally by Continuity Brain from evidence in the private ChatGPT archive.")
        appendLine()
        appendLine("## Snapshot")
        appendLine("- Hard invariants: ${report.hardInvariants.size}")
        appendLine("- Current requirements: ${report.authoritativeRequirements.size}")
        appendLine("- Unresolved work candidates: ${report.unresolvedWork.size}")
        appendLine("- Known contradiction pairs: ${report.contradictions.size}")
        appendLine("- Regression candidates: ${report.regressions.size}")
        appendLine("- Build references: ${report.builds.size}")
        appendLine("- Test observations: ${report.testResults.size}")
        appendLine("- Bug observations: ${report.bugs.size}")
        appendLine("- Extracted code artifacts: ${report.codeArtifacts.size}")
        appendLine()
        appendLine("See `PROJECT_STATE.md` for the canonical evidence-backed state and the other files for focused views.")
        appendLine()
        appendLine("## Evidence model")
        appendLine("Historical user-authored instructions outrank assistant suggestions. Evidence IDs in these files refer back to private archive messages; the source messages themselves are not copied into this public-code repository automatically.")
    }

    private fun renderRoadmap(report: ProjectAnalyzer.ProjectReport): String = buildString {
        appendLine("# Roadmap — ${report.name}")
        appendLine()
        appendLine("## Unresolved work")
        appendFacts(report.unresolvedWork)
        appendLine()
        appendLine("## Abandoned-idea candidates")
        appendLine("These are conservative heuristics, not declarations. Review before removing or reviving anything.")
        appendFacts(report.abandonedIdeaCandidates)
    }

    private fun renderDecisions(report: ProjectAnalyzer.ProjectReport): String = buildString {
        appendLine("# Design Decisions — ${report.name}")
        appendLine()
        appendLine("## Hard invariants")
        appendFacts(report.hardInvariants)
        appendLine()
        appendLine("## Current decisions")
        appendFacts(report.decisions)
        appendLine()
        appendLine("## Rejected / removed / prohibited")
        appendFacts(report.rejectedOrRemoved)
        appendLine()
        appendLine("## Contradictions requiring review")
        if (report.contradictions.isEmpty()) appendLine("- None detected.")
        report.contradictions.forEach { pair ->
            appendLine("- Older [evidence:${pair.older.messageId}]: ${pair.older.payload}")
            appendLine("  Newer [evidence:${pair.newer.messageId}]: ${pair.newer.payload}")
        }
    }

    private fun renderIssues(report: ProjectAnalyzer.ProjectReport): String = buildString {
        appendLine("# Known Issues — ${report.name}")
        appendLine()
        appendLine("## Extracted bug observations")
        appendFacts(report.bugs)
        appendLine()
        appendLine("## Recurring failure patterns")
        if (report.failurePatterns.isEmpty()) appendLine("- None currently detected.")
        report.failurePatterns.forEach { pattern ->
            appendLine("- ${pattern.label}: ${pattern.count} observations")
            appendLine("  Evidence: ${pattern.evidenceMessageIds.joinToString { "evidence:$it" }}")
        }
    }

    private fun renderBuildMatrix(report: ProjectAnalyzer.ProjectReport): String = buildString {
        appendLine("# Build / Test Matrix — ${report.name}")
        appendLine()
        if (report.buildMatrix.isEmpty()) {
            appendLine("No build records have been extracted yet.")
            return@buildString
        }
        report.buildMatrix.forEach { row ->
            appendLine("## Build — evidence:${row.build.messageId}")
            appendLine(row.build.payload)
            appendLine()
            if (row.nearbyResults.isEmpty()) {
                appendLine("- No nearby extracted test observation.")
            } else {
                row.nearbyResults.forEach { result ->
                    val state = when {
                        result.polarity > 0 -> "PASS"
                        result.polarity < 0 -> "FAIL"
                        else -> "OBS"
                    }
                    appendLine("- **$state** [evidence:${result.messageId}] ${result.payload}")
                }
            }
            appendLine()
        }
    }

    private fun renderRegressions(report: ProjectAnalyzer.ProjectReport): String = buildString {
        appendLine("# Regression Candidates — ${report.name}")
        appendLine()
        appendLine("Candidates require human confirmation; they are derived from same-subject working→later-failure evidence sequences.")
        appendLine()
        if (report.regressions.isEmpty()) appendLine("- None currently detected.")
        report.regressions.forEach { regression ->
            appendLine("- Previously working [evidence:${regression.previouslyWorking.messageId}]: ${regression.previouslyWorking.payload}")
            appendLine("  Later failure [evidence:${regression.laterFailure.messageId}]: ${regression.laterFailure.payload}")
        }
    }

    private fun renderProcessPatterns(report: ProjectAnalyzer.ProjectReport): String = buildString {
        appendLine("# Engineering Process Patterns — ${report.name}")
        appendLine()
        if (report.failurePatterns.isEmpty()) {
            appendLine("No recurring extracted failure class is currently strong enough to report.")
        } else {
            report.failurePatterns.forEach { pattern ->
                appendLine("## ${pattern.label}")
                appendLine("Observed ${pattern.count} times in extracted bug evidence.")
                appendLine("Evidence: ${pattern.evidenceMessageIds.joinToString { "evidence:$it" }}")
                appendLine()
            }
        }
        appendLine("## Suggested use")
        appendLine("Turn recurring failure classes into pre-release checks for this project. The pack intentionally reports evidence rather than claiming a causal diagnosis without one.")
    }

    private fun renderJson(report: ProjectAnalyzer.ProjectReport): JSONObject = JSONObject()
        .put("format", "continuity-brain-project-pack")
        .put("version", 1)
        .put("project_id", report.projectId)
        .put("name", report.name)
        .put("generated_at", report.generatedAt)
        .put("latest_activity", report.latestActivity ?: JSONObject.NULL)
        .put("hard_invariants", factsJson(report.hardInvariants))
        .put("requirements", factsJson(report.authoritativeRequirements))
        .put("decisions", factsJson(report.decisions))
        .put("rejected_or_removed", factsJson(report.rejectedOrRemoved))
        .put("unresolved_work", factsJson(report.unresolvedWork))
        .put("abandoned_candidates", factsJson(report.abandonedIdeaCandidates))
        .put("bugs", factsJson(report.bugs))
        .put("test_results", factsJson(report.testResults))
        .put("builds", factsJson(report.builds))
        .put("regressions", JSONArray().apply {
            report.regressions.forEach { regression ->
                put(JSONObject()
                    .put("working_evidence", regression.previouslyWorking.messageId)
                    .put("failure_evidence", regression.laterFailure.messageId))
            }
        })
        .put("contradictions", JSONArray().apply {
            report.contradictions.forEach { pair ->
                put(JSONObject()
                    .put("older_evidence", pair.older.messageId)
                    .put("newer_evidence", pair.newer.messageId))
            }
        })
        .put("code_artifacts", JSONArray().apply {
            report.codeArtifacts.forEach { artifact ->
                put(JSONObject()
                    .put("id", artifact.id)
                    .put("message_id", artifact.messageId)
                    .put("label", artifact.label)
                    .put("language", artifact.language ?: JSONObject.NULL))
            }
        })

    private fun factsJson(facts: List<ProjectAnalyzer.EvidenceFact>): JSONArray = JSONArray().apply {
        facts.forEach { fact ->
            put(JSONObject()
                .put("message_id", fact.messageId)
                .put("kind", fact.kind)
                .put("payload", fact.payload)
                .put("role", fact.role)
                .put("polarity", fact.polarity)
                .put("confidence", fact.confidence)
                .put("timestamp", fact.timestamp ?: JSONObject.NULL))
        }
    }

    private fun StringBuilder.appendFacts(facts: List<ProjectAnalyzer.EvidenceFact>) {
        if (facts.isEmpty()) {
            appendLine("- None currently supported by extracted evidence.")
            return
        }
        facts.forEach { fact -> appendLine("- [evidence:${fact.messageId}] ${fact.payload}") }
    }

    private fun sanitizeFileStem(value: String): String = value
        .replace(Regex("[^A-Za-z0-9._-]+"), "-")
        .trim('-', '.', '_')

    private fun languageExtension(language: String?): String = when (language?.lowercase(Locale.ROOT)) {
        "kotlin", "kt" -> ".kt"
        "java" -> ".java"
        "python", "py" -> ".py"
        "javascript", "js" -> ".js"
        "typescript", "ts" -> ".ts"
        "json" -> ".json"
        "xml" -> ".xml"
        "html" -> ".html"
        "css" -> ".css"
        "bash", "sh", "shell" -> ".sh"
        "sql" -> ".sql"
        "c" -> ".c"
        "cpp", "c++" -> ".cpp"
        "csharp", "c#" -> ".cs"
        "rust" -> ".rs"
        "go" -> ".go"
        "swift" -> ".swift"
        "gradle" -> ".gradle"
        "markdown", "md" -> ".md"
        else -> ".txt"
    }
}
