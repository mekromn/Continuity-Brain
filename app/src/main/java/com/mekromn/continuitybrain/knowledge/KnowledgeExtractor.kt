package com.mekromn.continuitybrain.knowledge

import com.mekromn.continuitybrain.model.ArtifactDraft
import com.mekromn.continuitybrain.model.DerivedInsight
import com.mekromn.continuitybrain.model.ImportedConversation
import com.mekromn.continuitybrain.model.ImportedMessage
import com.mekromn.continuitybrain.model.ProjectCandidate
import java.text.Normalizer
import java.util.Locale

/**
 * Deterministic, fully local first-pass knowledge extraction.
 *
 * This deliberately favors traceability over pretending to understand more than
 * it can. Every derived record points back to the source message. More advanced
 * local-model analysis can enrich these records later without replacing the
 * original evidence.
 */
object KnowledgeExtractor {
    private val githubRepoRegex = Regex(
        "https?://(?:www\\.)?github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)",
        RegexOption.IGNORE_CASE,
    )
    private val apkRegex = Regex("(?i)\\b[^\\s/\\\\]+\\.apk\\b")
    private val versionRegex = Regex("(?i)\\b(?:v|version|build)[ _:-]*([0-9]+(?:\\.[0-9A-Za-z_-]+){0,7})\\b")
    private val codeFenceRegex = Regex("```([A-Za-z0-9_+.-]*)\\s*\\n([\\s\\S]*?)```", RegexOption.MULTILINE)

    private val requirementSignals = listOf(
        "need to", "needs to", "must ", "must not", "should ", "should not",
        "i want", "we need", "goal is", "requirement", "never ", "always ",
        "preserve ", "keep ", "remove ", "do not ", "don't ", "cannot ",
    )
    private val decisionSignals = listOf(
        "we will", "let's ", "lets ", "decided", "decision", "we are going to",
        "keep ", "remove ", "use ", "switch to", "rename ", "scrap ",
    )
    private val bugSignals = listOf(
        "crash", "crashes", "crashed", "bug", "error", "not working", "doesn't work",
        "does not work", "failed", "failure", "app not installed", "black screen",
        "black viewfinder", "freeze", "frozen", "regression",
    )
    private val successSignals = listOf(
        "it works", "works great", "working now", "fixed", "resolved", "mergable",
        "saved", "saves", "success", "looks great", "perfect", "passed",
    )
    private val testSignals = listOf(
        "tested", "test ", "works", "doesn't", "does not", "crash", "installed",
        "not installed", "save", "viewfinder", "result", "output", "latest",
    )
    private val hardInvariantSignals = listOf(
        "must not", "never ", "always ", "hard invariant", "under no circumstances",
        "do not sacrifice", "preserve ", "cannot lower", "never sacrifice",
    )

    private val stopWords = setOf(
        "a", "an", "and", "app", "apk", "build", "can", "continue", "create", "for",
        "from", "how", "i", "improve", "in", "is", "it", "make", "my", "new", "of",
        "on", "please", "project", "the", "this", "to", "update", "with", "we",
    )

    fun projectCandidates(conversation: ImportedConversation): List<ProjectCandidate> {
        val candidates = LinkedHashMap<String, ProjectCandidate>()
        val sample = buildString {
            append(conversation.title)
            conversation.messages.take(60).forEach {
                append('\n')
                append(it.content.take(4000))
            }
        }

        githubRepoRegex.findAll(sample).forEach { match ->
            val owner = match.groupValues[1]
            val repo = match.groupValues[2].removeSuffix(".git")
            val key = "github:${owner.lowercase(Locale.ROOT)}/${repo.lowercase(Locale.ROOT)}"
            candidates[key] = ProjectCandidate(
                name = repo,
                canonicalKey = key,
                confidence = 0.98,
                basis = "github-repository",
            )
        }

        val titleKey = canonicalTitleKey(conversation.title)
        if (titleKey.isNotBlank()) {
            candidates.putIfAbsent(
                "title:$titleKey",
                ProjectCandidate(
                    name = cleanProjectName(conversation.title),
                    canonicalKey = "title:$titleKey",
                    confidence = 0.72,
                    basis = "conversation-title",
                ),
            )
        }
        return candidates.values.toList()
    }

    fun insights(message: ImportedMessage): List<DerivedInsight> {
        if (message.content.isBlank()) return emptyList()
        val results = ArrayList<DerivedInsight>()
        val chunks = semanticChunks(message.content)

        for (chunk in chunks) {
            val normalized = chunk.lowercase(Locale.ROOT)
            val subject = subjectFor(chunk)
            if (subject.isBlank()) continue

            val hard = hardInvariantSignals.any(normalized::contains)
            if (hard) {
                results += DerivedInsight(
                    kind = "HARD_INVARIANT",
                    payload = chunk,
                    subject = subject,
                    polarity = polarity(normalized),
                    confidence = 0.94,
                )
            } else if (requirementSignals.any(normalized::contains)) {
                results += DerivedInsight(
                    kind = "REQUIREMENT",
                    payload = chunk,
                    subject = subject,
                    polarity = polarity(normalized),
                    confidence = 0.82,
                )
            }

            if (decisionSignals.any(normalized::contains)) {
                results += DerivedInsight(
                    kind = "DECISION",
                    payload = chunk,
                    subject = subject,
                    polarity = polarity(normalized),
                    confidence = 0.76,
                )
            }

            if (bugSignals.any(normalized::contains)) {
                results += DerivedInsight(
                    kind = "BUG",
                    payload = chunk,
                    subject = subject,
                    polarity = -1,
                    confidence = 0.87,
                )
            }

            if (testSignals.any(normalized::contains)) {
                val resultPolarity = when {
                    successSignals.any(normalized::contains) -> 1
                    bugSignals.any(normalized::contains) -> -1
                    else -> 0
                }
                results += DerivedInsight(
                    kind = "TEST_RESULT",
                    payload = chunk,
                    subject = subject,
                    polarity = resultPolarity,
                    confidence = if (resultPolarity == 0) 0.58 else 0.84,
                )
            }

            if (apkRegex.containsMatchIn(chunk) || versionRegex.containsMatchIn(chunk)) {
                results += DerivedInsight(
                    kind = "BUILD",
                    payload = chunk,
                    subject = subject,
                    polarity = when {
                        successSignals.any(normalized::contains) -> 1
                        bugSignals.any(normalized::contains) -> -1
                        else -> 0
                    },
                    confidence = 0.86,
                )
            }

            if ("todo" in normalized || "still need" in normalized || "what still" in normalized || "next " in normalized) {
                results += DerivedInsight(
                    kind = "TODO",
                    payload = chunk,
                    subject = subject,
                    polarity = 0,
                    confidence = 0.72,
                )
            }
        }

        githubRepoRegex.findAll(message.content).forEach { match ->
            results += DerivedInsight(
                kind = "REPOSITORY",
                payload = match.value,
                subject = "${match.groupValues[1]}/${match.groupValues[2]}",
                polarity = 0,
                confidence = 0.99,
            )
        }

        return results.distinctBy { "${it.kind}|${it.payload}|${it.subject}" }
    }

    fun artifacts(message: ImportedMessage): List<ArtifactDraft> {
        val output = ArrayList<ArtifactDraft>()
        codeFenceRegex.findAll(message.content).forEachIndexed { index, match ->
            val language = match.groupValues[1].trim().ifBlank { null }
            val code = match.groupValues[2].trimEnd()
            if (code.isNotBlank()) {
                output += ArtifactDraft(
                    kind = "CODE",
                    label = buildString {
                        append(language ?: "code")
                        append(" snippet ")
                        append(index + 1)
                    },
                    language = language,
                    content = code,
                )
            }
        }

        apkRegex.findAll(message.content).forEach { match ->
            output += ArtifactDraft(
                kind = "APK_REFERENCE",
                label = match.value,
                language = null,
                content = match.value,
            )
        }
        return output.distinctBy { "${it.kind}|${it.content}" }
    }

    fun searchTerms(text: String): Map<String, Int> {
        val words = normalize(text)
            .split(Regex("[^a-z0-9_+.#-]+"))
            .filter { it.length >= 2 }
            .take(6000)

        val result = LinkedHashMap<String, Int>()
        words.forEach { word ->
            if (word in stopWords) return@forEach
            result[word] = maxOf(result[word] ?: 0, 3)
            val stem = stem(word)
            if (stem != word && stem.length >= 3) {
                result["s:$stem"] = maxOf(result["s:$stem"] ?: 0, 2)
            }
            if (word.length >= 5) {
                // Blind prefix keys make partial-name search useful without
                // persisting readable prefixes in SQLite.
                for (length in 3..minOf(8, word.length)) {
                    result["p:${word.take(length)}"] = maxOf(result["p:${word.take(length)}"] ?: 0, 1)
                }
            }
        }
        return result
    }

    fun queryTerms(query: String): Set<String> {
        val normalized = normalize(query)
        val words = normalized.split(Regex("[^a-z0-9_+.#-]+"))
            .filter { it.length >= 2 && it !in stopWords }
        val result = LinkedHashSet<String>()
        words.forEach { word ->
            result += word
            val stem = stem(word)
            if (stem != word && stem.length >= 3) result += "s:$stem"
            if (word.length >= 3) result += "p:${word.take(minOf(8, word.length))}"
        }
        return result
    }

    fun canonicalTitleKey(title: String): String = normalize(title)
        .split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 2 && it !in stopWords }
        .take(6)
        .joinToString("-")

    fun cleanProjectName(title: String): String {
        val cleaned = title
            .replace(Regex("(?i)^(new chat|continue|build|improve|update)[: -]*"), "")
            .trim()
        return cleaned.ifBlank { "Untitled project" }.take(120)
    }

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace('’', '\'')
        .trim()

    private fun semanticChunks(text: String): List<String> {
        val chunks = ArrayList<String>()
        text.lineSequence().forEach { line ->
            val trimmed = line.trim().removePrefix("- ").removePrefix("* ")
            if (trimmed.length < 8) return@forEach
            if (trimmed.length <= 700) {
                chunks += trimmed
            } else {
                trimmed.split(Regex("(?<=[.!?])\\s+(?=[A-Z0-9])"))
                    .map(String::trim)
                    .filter { it.length >= 8 }
                    .forEach { chunks += it.take(1000) }
            }
        }
        return chunks.take(240)
    }

    private fun subjectFor(text: String): String {
        val tokens = normalize(text)
            .split(Regex("[^a-z0-9_+.#-]+"))
            .filter { it.length >= 3 && it !in stopWords && it !in subjectNoise }
            .take(12)
        return tokens.joinToString(" ")
    }

    private fun polarity(normalized: String): Int {
        val negative = listOf(
            "remove ", "disable ", "do not ", "don't ", "must not", "never ",
            "without ", "no ", "strip ", "delete ", "scrap ", "stop ",
        ).any(normalized::contains)
        val positive = listOf(
            "keep ", "preserve ", "enable ", "add ", "use ", "must ", "need ",
            "want ", "support ", "allow ", "retain ",
        ).any(normalized::contains)
        return when {
            negative && !positive -> -1
            positive && !negative -> 1
            else -> 0
        }
    }

    private fun stem(word: String): String = when {
        word.length > 6 && word.endsWith("ingly") -> word.dropLast(5)
        word.length > 5 && word.endsWith("ing") -> word.dropLast(3)
        word.length > 5 && word.endsWith("ies") -> word.dropLast(3) + "y"
        word.length > 4 && word.endsWith("ed") -> word.dropLast(2)
        word.length > 4 && word.endsWith("es") -> word.dropLast(2)
        word.length > 3 && word.endsWith("s") -> word.dropLast(1)
        else -> word
    }

    private val subjectNoise = setOf(
        "also", "about", "after", "again", "because", "before", "could", "current",
        "everything", "feature", "latest", "like", "more", "only", "really", "same",
        "something", "still", "that", "then", "there", "these", "they", "thing", "this",
        "very", "when", "where", "which", "would", "your",
    )
}
