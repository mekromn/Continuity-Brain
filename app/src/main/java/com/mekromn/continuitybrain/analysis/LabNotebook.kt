package com.mekromn.continuitybrain.analysis

/**
 * Reconstructs experiment-like sequences from a project's ordered evidence.
 * It does not claim causal relationships: grouping is chronological and every
 * step retains its source message ID for review.
 */
class LabNotebook(
    private val analyzer: ProjectAnalyzer,
) {
    data class Experiment(
        val index: Int,
        val hypothesisOrGoal: List<ProjectAnalyzer.EvidenceFact>,
        val builds: List<ProjectAnalyzer.EvidenceFact>,
        val observations: List<ProjectAnalyzer.EvidenceFact>,
        val conclusionsOrDecisions: List<ProjectAnalyzer.EvidenceFact>,
        val startedAt: Double?,
        val endedAt: Double?,
        val confidence: Double,
    )

    fun reconstruct(projectId: String): List<Experiment> {
        val report = analyzer.analyze(projectId)
        val evidence = buildList {
            addAll(report.authoritativeRequirements)
            addAll(report.unresolvedWork)
            addAll(report.builds)
            addAll(report.testResults)
            addAll(report.bugs)
            addAll(report.decisions)
        }
            .distinctBy(ProjectAnalyzer.EvidenceFact::id)
            .sortedWith(compareBy<ProjectAnalyzer.EvidenceFact> { it.timestamp ?: Double.MAX_VALUE }.thenBy { it.id })

        if (evidence.isEmpty()) return emptyList()

        val output = ArrayList<Experiment>()
        var goals = ArrayList<ProjectAnalyzer.EvidenceFact>()
        var builds = ArrayList<ProjectAnalyzer.EvidenceFact>()
        var observations = ArrayList<ProjectAnalyzer.EvidenceFact>()
        var conclusions = ArrayList<ProjectAnalyzer.EvidenceFact>()
        var sequenceStart: Double? = null
        var lastTimestamp: Double? = null

        fun flush() {
            if (goals.isEmpty() && builds.isEmpty() && observations.isEmpty() && conclusions.isEmpty()) return
            val completePhases = listOf(goals, builds, observations, conclusions).count { it.isNotEmpty() }
            output += Experiment(
                index = output.size + 1,
                hypothesisOrGoal = goals.toList(),
                builds = builds.toList(),
                observations = observations.toList(),
                conclusionsOrDecisions = conclusions.toList(),
                startedAt = sequenceStart,
                endedAt = lastTimestamp,
                confidence = (completePhases / 4.0).coerceIn(0.25, 1.0),
            )
            goals = ArrayList()
            builds = ArrayList()
            observations = ArrayList()
            conclusions = ArrayList()
            sequenceStart = null
            lastTimestamp = null
        }

        for (fact in evidence) {
            val timestamp = fact.timestamp
            if (sequenceStart == null) sequenceStart = timestamp

            val gap = if (timestamp != null && lastTimestamp != null) timestamp - lastTimestamp!! else 0.0
            if (gap > MAX_EXPERIMENT_GAP_SECONDS && (builds.isNotEmpty() || observations.isNotEmpty())) {
                flush()
                sequenceStart = timestamp
            }

            when (fact.kind) {
                "REQUIREMENT", "TODO", "HARD_INVARIANT" -> {
                    if (observations.isNotEmpty() || conclusions.isNotEmpty()) flush()
                    goals += fact
                }
                "BUILD" -> {
                    if (conclusions.isNotEmpty()) flush()
                    builds += fact
                }
                "TEST_RESULT", "BUG" -> observations += fact
                "DECISION" -> conclusions += fact
            }
            if (timestamp != null) lastTimestamp = timestamp
        }
        flush()
        return output
    }

    companion object {
        private const val MAX_EXPERIMENT_GAP_SECONDS = 3.0 * 24.0 * 60.0 * 60.0
    }
}
