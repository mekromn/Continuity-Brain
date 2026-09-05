package com.mekromn.continuitybrain.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mekromn.continuitybrain.analysis.ProjectAnalyzer
import com.mekromn.continuitybrain.ui.theme.BrainAmber
import com.mekromn.continuitybrain.ui.theme.BrainCyan
import com.mekromn.continuitybrain.ui.theme.BrainGreen
import com.mekromn.continuitybrain.ui.theme.BrainPurple
import com.mekromn.continuitybrain.ui.theme.BrainSurface
import com.mekromn.continuitybrain.ui.theme.BrainSurfaceRaised
import com.mekromn.continuitybrain.ui.theme.BrainTextMuted

@Composable
internal fun ProjectLoadingScreen(onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator()
            Text("Reconstructing project state…", fontWeight = FontWeight.SemiBold)
            Text("Resolving requirements, builds, tests and contradictions", color = BrainTextMuted)
            TextButton(onClick = onBack) { Text("Back") }
        }
    }
}

@Composable
internal fun ProjectDetailScreen(
    report: ProjectAnalyzer.ProjectReport,
    onBack: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp, 18.dp, 20.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(BrainSurfaceRaised)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) { Text("‹", fontSize = 30.sp, color = BrainCyan) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("PROJECT BRAIN", color = BrainCyan, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text(report.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141021)),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    Text("Canonical project state", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "User-authored historical requirements outrank assistant suggestions. Newer same-subject evidence wins within the same authority tier, while contradictions stay visible.",
                        color = BrainTextMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProjectMetric("Invariants", report.hardInvariants.size, BrainPurple, Modifier.weight(1f))
                        ProjectMetric("Remaining", report.unresolvedWork.size, BrainAmber, Modifier.weight(1f))
                        ProjectMetric("Regressions", report.regressions.size, BrainCyan, Modifier.weight(1f))
                    }
                    Button(
                        onClick = { clipboard.setText(AnnotatedString(report.canonicalMarkdown)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Copy canonical spec") }
                }
            }
        }

        if (report.hardInvariants.isNotEmpty()) {
            item { FactSection("Hard invariants", "Rules the project should not violate", report.hardInvariants, BrainPurple) }
        }
        if (report.authoritativeRequirements.isNotEmpty()) {
            item { FactSection("Current requirements", "Latest canonical user evidence per derived subject", report.authoritativeRequirements, BrainCyan) }
        }
        if (report.unresolvedWork.isNotEmpty()) {
            item { FactSection("What still needs doing", "No later completion evidence is currently linked", report.unresolvedWork, BrainAmber) }
        }
        if (report.rejectedOrRemoved.isNotEmpty()) {
            item { FactSection("Rejected / removed / prohibited", "Negative requirements and decisions remain visible", report.rejectedOrRemoved, Color(0xFFFF8797)) }
        }

        if (report.contradictions.isNotEmpty()) {
            item {
                DetailCard("Contradictions", BrainAmber) {
                    report.contradictions.take(30).forEachIndexed { index, pair ->
                        if (index > 0) Spacer(Modifier.height(11.dp))
                        Text("Older", color = BrainTextMuted, style = MaterialTheme.typography.labelSmall)
                        EvidenceText(pair.older)
                        Spacer(Modifier.height(5.dp))
                        Text("Newer", color = BrainGreen, style = MaterialTheme.typography.labelSmall)
                        EvidenceText(pair.newer)
                    }
                }
            }
        }

        if (report.regressions.isNotEmpty()) {
            item {
                DetailCard("Regression candidates", Color(0xFFFF8797)) {
                    report.regressions.take(30).forEachIndexed { index, regression ->
                        if (index > 0) Spacer(Modifier.height(12.dp))
                        Text("✓ Previously working", color = BrainGreen, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        EvidenceText(regression.previouslyWorking)
                        Spacer(Modifier.height(5.dp))
                        Text("✕ Later failure", color = Color(0xFFFF8797), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        EvidenceText(regression.laterFailure)
                    }
                }
            }
        }

        if (report.buildMatrix.isNotEmpty()) {
            item {
                DetailCard("Build / test matrix", BrainCyan) {
                    report.buildMatrix.take(40).forEachIndexed { index, row ->
                        if (index > 0) Spacer(Modifier.height(13.dp))
                        Text("BUILD", color = BrainPurple, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        EvidenceText(row.build)
                        row.nearbyResults.forEach { result ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${when { result.polarity > 0 -> "PASS"; result.polarity < 0 -> "FAIL"; else -> "OBS" }} • ${result.payload}",
                                color = when { result.polarity > 0 -> BrainGreen; result.polarity < 0 -> Color(0xFFFF8797); else -> BrainTextMuted },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        if (report.failurePatterns.isNotEmpty()) {
            item {
                DetailCard("Recurring failure patterns", Color(0xFFFF8797)) {
                    report.failurePatterns.forEach { pattern ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(pattern.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text(pattern.count.toString(), color = Color(0xFFFF8797), fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(7.dp))
                    }
                }
            }
        }

        if (report.abandonedIdeaCandidates.isNotEmpty()) {
            item {
                FactSection(
                    "Abandoned-idea candidates",
                    "Heuristic only: old unresolved ideas with no later same-subject implementation/test/decision evidence",
                    report.abandonedIdeaCandidates,
                    BrainAmber,
                )
            }
        }

        item {
            DetailCard("Project assets", BrainGreen) {
                Text("${report.builds.size} build references", color = BrainTextMuted)
                Text("${report.testResults.size} extracted test observations", color = BrainTextMuted)
                Text("${report.bugs.size} bug observations", color = BrainTextMuted)
                Text("${report.codeArtifacts.size} fenced code artifacts", color = BrainTextMuted)
            }
        }
    }
}

@Composable
private fun ProjectMetric(label: String, value: Int, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(15.dp)).background(Color(0xFF0B0910)).padding(10.dp),
    ) {
        Text(value.toString(), color = accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = BrainTextMuted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun FactSection(
    title: String,
    subtitle: String,
    facts: List<ProjectAnalyzer.EvidenceFact>,
    accent: Color,
) {
    DetailCard(title, accent) {
        Text(subtitle, color = BrainTextMuted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        facts.take(60).forEachIndexed { index, fact ->
            if (index > 0) Spacer(Modifier.height(10.dp))
            EvidenceText(fact)
        }
    }
}

@Composable
private fun EvidenceText(fact: ProjectAnalyzer.EvidenceFact) {
    Text(fact.payload, color = Color(0xFFE1E1E8), style = MaterialTheme.typography.bodyMedium)
    Text(
        "evidence:${fact.messageId.take(14)} • ${fact.role} • confidence ${"%.0f".format(fact.confidence * 100)}%",
        color = BrainTextMuted,
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun DetailCard(
    title: String,
    accent: Color,
    content: @Composable () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = BrainSurface), shape = RoundedCornerShape(21.dp)) {
        Column(Modifier.padding(17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}
