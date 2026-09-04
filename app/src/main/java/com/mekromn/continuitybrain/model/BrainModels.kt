package com.mekromn.continuitybrain.model

data class ImportedMessage(
    val id: String,
    val parentId: String?,
    val role: String,
    val createdAt: Double?,
    val updatedAt: Double?,
    val content: String,
    val contentType: String?,
    val ordinal: Int,
)

data class ImportedConversation(
    val id: String,
    val title: String,
    val createdAt: Double?,
    val updatedAt: Double?,
    val currentNodeId: String? = null,
    val messages: List<ImportedMessage>,
)

data class ImportProgress(
    val stage: String,
    val conversationsSeen: Int = 0,
    val messagesSeen: Int = 0,
    val attachmentsSeen: Int = 0,
    val added: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
)

data class ImportSummary(
    val duplicateArchive: Boolean,
    val conversationsSeen: Int,
    val messagesSeen: Int,
    val attachmentsSeen: Int = 0,
    val added: Int,
    val updated: Int,
    val unchanged: Int,
)

data class ImportDelta(
    val added: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
) {
    operator fun plus(other: ImportDelta) = ImportDelta(
        added = added + other.added,
        updated = updated + other.updated,
        unchanged = unchanged + other.unchanged,
    )
}

data class BrainStats(
    val conversations: Int = 0,
    val messages: Int = 0,
    val projects: Int = 0,
    val insights: Int = 0,
    val artifacts: Int = 0,
    val attachments: Int = 0,
    val imports: Int = 0,
)

data class SearchHit(
    val messageId: String,
    val conversationId: String,
    val conversationTitle: String,
    val role: String,
    val createdAt: Double?,
    val content: String,
    val score: Int,
)

data class ProjectSummary(
    val id: String,
    val name: String,
    val conversationCount: Int,
    val messageCount: Int,
    val insightCount: Int,
    val updatedAt: Double?,
)

data class TimelineItem(
    val messageId: String,
    val conversationId: String,
    val conversationTitle: String,
    val role: String,
    val timestamp: Double?,
    val content: String,
)

data class DerivedInsight(
    val kind: String,
    val payload: String,
    val subject: String,
    val polarity: Int,
    val confidence: Double,
)

data class ArtifactDraft(
    val kind: String,
    val label: String,
    val language: String?,
    val content: String,
)

data class ProjectCandidate(
    val name: String,
    val canonicalKey: String,
    val confidence: Double,
    val basis: String,
)

data class ContextPack(
    val query: String,
    val text: String,
    val evidenceCount: Int,
    val truncated: Boolean,
)

data class BridgeStatus(
    val enabled: Boolean,
    val port: Int,
    val token: String,
)
