package com.mekromn.continuitybrain.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mekromn.continuitybrain.model.BrainStats
import com.mekromn.continuitybrain.model.ImportProgress
import com.mekromn.continuitybrain.model.ImportSummary
import com.mekromn.continuitybrain.model.ProjectSummary
import com.mekromn.continuitybrain.model.SearchHit
import com.mekromn.continuitybrain.model.TimelineItem
import com.mekromn.continuitybrain.ui.theme.BrainAmber
import com.mekromn.continuitybrain.ui.theme.BrainCyan
import com.mekromn.continuitybrain.ui.theme.BrainGreen
import com.mekromn.continuitybrain.ui.theme.BrainPurple
import com.mekromn.continuitybrain.ui.theme.BrainSurface
import com.mekromn.continuitybrain.ui.theme.BrainSurfaceRaised
import com.mekromn.continuitybrain.ui.theme.BrainTextMuted
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun HomeScreen(
    state: BrainUiState,
    onImport: () -> Unit,
    onOpenSearch: (String) -> Unit,
    onOpenVault: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(20.dp, 20.dp, 20.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            ScreenHeader(
                eyebrow = "Continuity Brain",
                title = if (state.stats.messages == 0) "Build your private memory" else "Your work, remembered",
                subtitle = "Encrypted project history that can recover decisions, tests, code and unfinished work.",
            )
        }
        item { BrainHero(state.stats, state.importing, onImport) }
        item { StatsGrid(state.stats) }

        if (state.semanticReady) {
            item {
                StatusCard(
                    accent = BrainCyan,
                    title = "Semantic memory ${if (state.semanticPending == 0) "ready" else "needs indexing"}",
                    body = "${state.semanticIndexed} messages embedded • ${state.semanticPending} pending • ${state.semanticDimensions} dimensions",
                    onClick = onOpenVault,
                )
            }
        }

        if (state.projects.isNotEmpty()) {
            item { SectionTitle("Recent projects", "Derived from conversation evidence") }
            items(state.projects.take(6), key = { it.id }) { project ->
                ProjectCard(project) { onOpenSearch(project.name) }
            }
        } else {
            item {
                EmptyStateCard(
                    title = "No project graph yet",
                    body = "Import a ChatGPT export. Continuity Brain preserves the original messages, then derives projects, requirements, bugs, builds and relationships locally.",
                    action = "Import export",
                    onAction = onImport,
                )
            }
        }

        item {
            StatusCard(
                accent = if (state.bridgeEnabled) BrainGreen else BrainTextMuted,
                title = "Continuity bridge",
                body = if (state.bridgeEnabled) {
                    "Private localhost access is active"
                } else {
                    "Off by default — enable it when you want ChatGPT to retrieve Brain context"
                },
                onClick = onOpenVault,
            )
        }
    }
}

@Composable
internal fun SearchScreen(
    state: BrainUiState,
    onQuery: (String) -> Unit,
    onSearch: (String) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(20.dp, 20.dp, 20.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            ScreenHeader(
                eyebrow = if (state.semanticReady && state.semanticIndexed > 0) "Hybrid evidence search" else "Evidence search",
                title = "Find what actually happened",
                subtitle = if (state.semanticReady && state.semanticIndexed > 0) {
                    "Exact technical matches and local semantic similarity are fused without uploading your archive."
                } else {
                    "Search message text, project names, builds and technical terms without sending the archive anywhere."
                },
            )
        }
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("e.g. FP32 detail crash or Meboard telemetry") },
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(state.query) }),
                trailingIcon = {
                    Text(
                        "⌕",
                        modifier = Modifier.clickable { onSearch(state.query) }.padding(10.dp),
                        color = BrainCyan,
                        fontSize = 23.sp,
                    )
                },
            )
        }
        if (state.query.isNotBlank()) {
            item {
                Text(
                    if (state.searchHits.isEmpty()) "No indexed matches yet" else "${state.searchHits.size} evidence matches",
                    color = BrainTextMuted,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        items(state.searchHits, key = { it.messageId }) { SearchHitCard(it) }
        if (state.query.isBlank() && state.searchHits.isEmpty()) {
            item {
                EmptyStateCard(
                    title = "Ask your history",
                    body = "Try a project name, APK filename, error message, implementation detail, or an idea you only vaguely remember. Install a local embedder in Vault for concept-level retrieval too.",
                )
            }
        }
    }
}

@Composable
internal fun ProjectsScreen(
    projects: List<ProjectSummary>,
    onProject: (ProjectSummary) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(20.dp, 20.dp, 20.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            ScreenHeader(
                eyebrow = "Project graph",
                title = "Everything connected",
                subtitle = "Projects are linked back to the conversations, requirements, tests and artifacts that created them.",
            )
        }
        items(projects, key = { it.id }) { project -> ProjectCard(project) { onProject(project) } }
        if (projects.isEmpty()) {
            item {
                EmptyStateCard(
                    "Projects appear after import",
                    "Repository links and conversation titles seed the first project graph. Evidence remains traceable to its source message.",
                )
            }
        }
    }
}

@Composable
internal fun TimelineScreen(timeline: List<TimelineItem>) {
    LazyColumn(
        contentPadding = PaddingValues(20.dp, 20.dp, 20.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            ScreenHeader(
                eyebrow = "Chronology",
                title = "Project history in order",
                subtitle = "A cross-chat timeline makes regression points, build sequences and forgotten decisions easier to reconstruct.",
            )
        }
        items(timeline, key = { it.messageId }) { item -> TimelineCard(item) }
        if (timeline.isEmpty()) {
            item { EmptyStateCard("No timeline yet", "Import an export to reconstruct activity across every conversation.") }
        }
    }
}

@Composable
internal fun VaultScreen(
    state: BrainUiState,
    onImport: () -> Unit,
    onBridge: (Boolean) -> Unit,
    onRotateToken: () -> Unit,
    onCreateBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    onInstallEmbeddingModel: () -> Unit,
    onBuildSemanticIndex: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    LazyColumn(
        contentPadding = PaddingValues(20.dp, 20.dp, 20.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            ScreenHeader(
                eyebrow = "Private vault",
                title = "Own the archive",
                subtitle = "Public code. Private evidence. Portable encrypted backups. Local semantic retrieval.",
            )
        }

        item {
            VaultCard("Export updates") {
                Text(
                    "Choose a fresh ChatGPT export ZIP whenever you make a backup. Exact duplicate archives are skipped and only changed/new messages are reprocessed.",
                    color = BrainTextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onImport, enabled = !state.importing && !state.vaultBusy) {
                    Text(if (state.importing) "Import in progress…" else if (state.stats.imports == 0) "Import first export" else "Import newer export")
                }
                state.importProgress?.let { ImportProgressBlock(it, state.importing) }
                state.importSummary?.let { ImportSummaryBlock(it) }
            }
        }

        item {
            VaultCard("Portable encrypted backup") {
                Text(
                    "A .cbbrain backup is independent of this phone's Android Keystore. It is streamed through AES-256-GCM under your passphrase, so no plaintext archive is written to storage.",
                    color = BrainTextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onCreateBackup,
                        enabled = !state.vaultBusy && state.stats.messages > 0,
                        modifier = Modifier.weight(1f),
                    ) { Text("Create backup") }
                    Button(
                        onClick = onRestoreBackup,
                        enabled = !state.vaultBusy && !state.importing,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = BrainSurfaceRaised),
                    ) { Text("Restore") }
                }
                if (state.vaultBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.vaultStatus?.let { Text(it, color = BrainGreen, style = MaterialTheme.typography.bodySmall) }
            }
        }

        item {
            VaultCard("Private semantic engine") {
                if (!state.semanticReady) {
                    Text(
                        "Install a string-input .tflite text embedder such as the Universal Sentence Encoder. The model and every vector stay on-device; standalone LiteRT is used instead of telemetry-enabled MediaPipe Tasks.",
                        color = BrainTextMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onInstallEmbeddingModel, enabled = !state.semanticIndexing) {
                        Text("Install local .tflite model")
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(BrainGreen))
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${state.semanticDimensions}-dimension local embedder", fontWeight = FontWeight.SemiBold)
                            Text(
                                "model ${state.semanticModelHash?.take(12)}…",
                                color = BrainTextMuted,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    Text(
                        "${state.semanticIndexed} indexed • ${state.semanticPending} pending",
                        color = if (state.semanticPending == 0) BrainGreen else BrainAmber,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.semanticIndexing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.semanticStatus?.let { Text(it, color = BrainCyan, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onBuildSemanticIndex,
                            enabled = !state.semanticIndexing && state.semanticPending > 0,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (state.semanticPending > 0) "Index pending" else "Index ready")
                        }
                        TextButton(onClick = onInstallEmbeddingModel, enabled = !state.semanticIndexing) {
                            Text("Change model")
                        }
                    }
                }
            }
        }

        item {
            VaultCard("Continuity bridge") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("ChatGPT access", fontWeight = FontWeight.SemiBold)
                        Text("127.0.0.1:${state.bridgePort} only", color = BrainGreen, style = MaterialTheme.typography.labelMedium)
                    }
                    Switch(checked = state.bridgeEnabled, onCheckedChange = onBridge)
                }
                Text(
                    "When enabled, the paired Continuity extension can search the Brain and request evidence context packs. The server binds only to loopback; it never exposes your archive on Wi-Fi or cellular interfaces.",
                    color = BrainTextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                AnimatedVisibility(state.bridgeEnabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        HorizontalDivider(color = Color(0xFF262630))
                        Text("Pairing token", color = BrainTextMuted, style = MaterialTheme.typography.labelSmall)
                        Text(
                            state.bridgeToken,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Color(0xFF09090D)).padding(12.dp),
                            color = BrainCyan,
                            fontSize = 12.sp,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TextButton(onClick = { clipboard.setText(AnnotatedString(state.bridgeToken)) }) { Text("Copy token") }
                            TextButton(onClick = onRotateToken) { Text("Rotate token") }
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0C1513)),
                shape = RoundedCornerShape(22.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Privacy boundary", color = BrainGreen, fontWeight = FontWeight.Bold)
                    PrivacyLine("Conversation text is AES-256-GCM encrypted before persistent storage.")
                    PrivacyLine("Search terms are keyed HMAC values, not readable words in SQLite.")
                    PrivacyLine("Semantic vectors are quantized then encrypted; only a similarity signature stays queryable.")
                    PrivacyLine("Imported attachments are streamed into encrypted internal files.")
                    PrivacyLine("Android cloud backup/device transfer of the vault is disabled.")
                    PrivacyLine("No analytics, ads, account SDK, remote database, MediaPipe telemetry, or cloud embedding API is required.")
                }
            }
        }
    }
}

@Composable
private fun VaultCard(title: String, content: @Composable Column.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = BrainSurface),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ScreenHeader(eyebrow: String, title: String, subtitle: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = eyebrow.uppercase(),
            color = BrainCyan,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(text = title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        Text(text = subtitle, color = BrainTextMuted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BrainHero(stats: BrainStats, importing: Boolean, onImport: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(28.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF251C4C), Color(0xFF101B2B), Color(0xFF0D111A)),
                    ),
                )
                .padding(22.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(54.dp).clip(RoundedCornerShape(18.dp))
                            .background(Brush.linearGradient(listOf(BrainPurple, BrainCyan))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("∞", color = Color.Black, fontSize = 30.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            text = if (stats.messages == 0) "Ready for your archive" else "${compact(stats.messages)} messages indexed",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text("Local • encrypted • evidence-linked", color = BrainGreen, style = MaterialTheme.typography.labelMedium)
                    }
                }
                Text(
                    text = if (stats.messages == 0) {
                        "Import the latest ChatGPT export ZIP. Future exports merge incrementally instead of replacing your Brain."
                    } else {
                        "Re-import a newer export anytime. Unchanged history is skipped while new and edited messages merge into the same knowledge graph."
                    },
                    color = Color(0xFFD7D6E2),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = onImport,
                    enabled = !importing,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                ) {
                    Text(if (importing) "Importing…" else if (stats.imports == 0) "Import ChatGPT export" else "Update from new export")
                }
            }
        }
    }
}

@Composable
private fun StatsGrid(stats: BrainStats) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard("Conversations", stats.conversations, BrainPurple, Modifier.weight(1f))
            StatCard("Projects", stats.projects, BrainCyan, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard("Insights", stats.insights, BrainGreen, Modifier.weight(1f))
            StatCard("Artifacts", stats.artifacts + stats.attachments, BrainAmber, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatCard(label: String, value: Int, accent: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BrainSurface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(compact(value), fontSize = 25.sp, fontWeight = FontWeight.Bold, color = accent)
            Spacer(Modifier.height(3.dp))
            Text(label, color = BrainTextMuted, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String? = null) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, color = BrainTextMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ProjectCard(project: ProjectSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BrainSurface),
    ) {
        Row(modifier = Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF232035)),
                contentAlignment = Alignment.Center,
            ) { Text("◇", color = BrainPurple, fontSize = 23.sp) }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(project.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(
                    "${project.conversationCount} chats • ${project.messageCount} messages • ${project.insightCount} facts",
                    color = BrainTextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("›", color = BrainTextMuted, fontSize = 28.sp)
        }
    }
}

@Composable
private fun StatusCard(accent: Color, title: String, body: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BrainSurfaceRaised),
    ) {
        Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(11.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(body, color = BrainTextMuted, style = MaterialTheme.typography.bodySmall)
            }
            Text("›", color = BrainTextMuted, fontSize = 28.sp)
        }
    }
}

@Composable
private fun SearchHitCard(hit: SearchHit) {
    Card(colors = CardDefaults.cardColors(containerColor = BrainSurface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    hit.role.uppercase(),
                    color = if (hit.role.equals("user", true)) BrainCyan else BrainPurple,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(8.dp))
                Text("rank ${hit.score}", color = BrainTextMuted, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                Text(prettyTime(hit.createdAt), color = BrainTextMuted, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(7.dp))
            Text(hit.conversationTitle, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(7.dp))
            Text(
                hit.content,
                color = Color(0xFFD7D7E0),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 9,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text("evidence:${hit.messageId.take(12)}", color = Color(0xFF6F6F7E), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun TimelineCard(item: TimelineItem) {
    Card(colors = CardDefaults.cardColors(containerColor = BrainSurface), shape = RoundedCornerShape(17.dp)) {
        Row(Modifier.padding(15.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(if (item.role == "user") BrainCyan else BrainPurple))
                Spacer(Modifier.height(4.dp))
                Box(Modifier.width(1.dp).height(58.dp).background(Color(0xFF2B2B36)))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row {
                    Text(item.conversationTitle, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    Text(prettyTime(item.timestamp), color = BrainTextMuted, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(5.dp))
                Text(item.content, color = Color(0xFFCBCBD5), maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PrivacyLine(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text("✓", color = BrainGreen, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color(0xFFD0D9D6), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ImportProgressBlock(progress: ImportProgress, active: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (active) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(progress.stage, color = if (active) BrainCyan else BrainGreen, fontWeight = FontWeight.SemiBold)
        Text(
            "${progress.conversationsSeen} conversations • ${progress.messagesSeen} messages • ${progress.attachmentsSeen} attachments",
            color = BrainTextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        if (progress.added + progress.updated + progress.unchanged > 0) {
            Text(
                "+${progress.added} new • ${progress.updated} changed • ${progress.unchanged} unchanged",
                color = BrainTextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ImportSummaryBlock(summary: ImportSummary) {
    val text = if (summary.duplicateArchive) {
        "Already imported — no work was repeated."
    } else {
        "Merged ${summary.conversationsSeen} conversations: +${summary.added} new, ${summary.updated} changed, ${summary.unchanged} unchanged."
    }
    Text(text, color = BrainGreen, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
}

@Composable
private fun EmptyStateCard(
    title: String,
    body: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Card(colors = CardDefaults.cardColors(containerColor = BrainSurface), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body, color = BrainTextMuted, style = MaterialTheme.typography.bodyMedium)
            if (action != null && onAction != null) {
                Spacer(Modifier.height(4.dp))
                Button(onClick = onAction) { Text(action) }
            }
        }
    }
}

private fun compact(value: Int): String = when {
    value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0)
    value >= 10_000 -> "%.1fk".format(value / 1_000.0)
    else -> value.toString()
}

private fun prettyTime(epochSeconds: Double?): String {
    if (epochSeconds == null || epochSeconds <= 0.0) return ""
    return runCatching {
        val instant = Instant.ofEpochMilli((epochSeconds * 1000.0).toLong())
        DATE_FORMAT.format(instant.atZone(ZoneId.systemDefault()))
    }.getOrDefault("")
}

private val DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy")
