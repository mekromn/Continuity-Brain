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
    val messages: List<ImportedMessage>,
)

data class ImportProgress(
    val stage: String,
    val conversationsSeen: Int = 0,
    val messagesSeen: Int = 0,
    val added: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
)

data class ImportSummary(
    val duplicateArchive: Boolean,
    val conversationsSeen: Int,
    val messagesSeen: Int,
    val added: Int,
    val updated: Int,
    val unchanged: Int,
)

data class BrainStats(
    val conversations: Int = 0,
    val messages: Int = 0,
    val projects: Int = 0,
    val insights: Int = 0,
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

data class InsightDraft(
    val kind: String,
    val payload: String,
    val confidence: Double,
)
