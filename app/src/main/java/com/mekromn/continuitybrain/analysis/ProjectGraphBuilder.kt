package com.mekromn.continuitybrain.analysis

import android.database.Cursor
import com.mekromn.continuitybrain.data.BrainDatabase
import com.mekromn.continuitybrain.data.CryptoVault
import com.mekromn.continuitybrain.data.EncryptedBlob

/** Builds an explainable, runtime graph without duplicating private text at rest. */
class ProjectGraphBuilder(
    private val database: BrainDatabase,
    private val crypto: CryptoVault,
    private val analyzer: ProjectAnalyzer,
) {
    enum class NodeKind { PROJECT, CONVERSATION, EVIDENCE, ARTIFACT }
    enum class EdgeKind {
        CONTAINS_CONVERSATION,
        CONTAINS_EVIDENCE,
        PRODUCED_ARTIFACT,
        SUPERSEDES,
        CONTRADICTS,
        TESTED_AFTER_BUILD,
        SOURCE_MESSAGE,
    }

    data class Node(
        val id: String,
        val kind: NodeKind,
        val label: String,
        val timestamp: Double? = null,
        val evidenceMessageId: String? = null,
    )

    data class Edge(
        val from: String,
        val to: String,
        val kind: EdgeKind,
        val confidence: Double,
    )

    data class Graph(
        val projectId: String,
        val nodes: List<Node>,
        val edges: List<Edge>,
    )

    fun build(projectId: String): Graph {
        val report = analyzer.analyze(projectId)
        val nodes = LinkedHashMap<String, Node>()
        val edges = LinkedHashMap<String, Edge>()

        fun node(value: Node) { nodes.putIfAbsent(value.id, value) }
        fun edge(value: Edge) {
            edges.putIfAbsent("${value.from}|${value.to}|${value.kind}", value)
        }

        val projectNodeId = "project:${report.projectId}"
        node(Node(projectNodeId, NodeKind.PROJECT, report.name, report.latestActivity))

        val conversationNames = projectConversations(projectId)
        conversationNames.forEach { conversation ->
            val id = "conversation:${conversation.id}"
            node(Node(id, NodeKind.CONVERSATION, conversation.title, conversation.timestamp))
            edge(Edge(projectNodeId, id, EdgeKind.CONTAINS_CONVERSATION, conversation.confidence))
        }

        val allFacts = buildList {
            addAll(report.hardInvariants)
            addAll(report.authoritativeRequirements)
            addAll(report.decisions)
            addAll(report.rejectedOrRemoved)
            addAll(report.unresolvedWork)
            addAll(report.bugs)
            addAll(report.testResults)
            addAll(report.builds)
        }.distinctBy(ProjectAnalyzer.EvidenceFact::id)

        allFacts.forEach { fact ->
            val evidenceId = "evidence:${fact.id}"
            node(
                Node(
                    id = evidenceId,
                    kind = NodeKind.EVIDENCE,
                    label = "${fact.kind}: ${fact.payload.take(180)}",
                    timestamp = fact.timestamp,
                    evidenceMessageId = fact.messageId,
                ),
            )
            edge(Edge(projectNodeId, evidenceId, EdgeKind.CONTAINS_EVIDENCE, fact.confidence))
            edge(
                Edge(
                    from = "conversation:${fact.conversationId}",
                    to = evidenceId,
                    kind = EdgeKind.SOURCE_MESSAGE,
                    confidence = 1.0,
                ),
            )
        }

        // Same derived subject: newest fact supersedes the prior candidate. This
        // is separate from CONTRADICTS because same-polarity refinements can also
        // supersede an older formulation.
        allFacts.groupBy(ProjectAnalyzer.EvidenceFact::subjectHash).values.forEach { group ->
            val ordered = group.sortedWith(compareBy<ProjectAnalyzer.EvidenceFact> { it.timestamp ?: 0.0 }.thenBy { it.id })
            ordered.zipWithNext().forEach { (older, newer) ->
                edge(
                    Edge(
                        from = "evidence:${newer.id}",
                        to = "evidence:${older.id}",
                        kind = EdgeKind.SUPERSEDES,
                        confidence = minOf(older.confidence, newer.confidence),
                    ),
                )
            }
        }

        report.contradictions.forEach { contradiction ->
            edge(
                Edge(
                    from = "evidence:${contradiction.newer.id}",
                    to = "evidence:${contradiction.older.id}",
                    kind = EdgeKind.CONTRADICTS,
                    confidence = minOf(contradiction.older.confidence, contradiction.newer.confidence),
                ),
            )
        }

        report.buildMatrix.forEach { row ->
            row.nearbyResults.forEach { result ->
                edge(
                    Edge(
                        from = "evidence:${result.id}",
                        to = "evidence:${row.build.id}",
                        kind = EdgeKind.TESTED_AFTER_BUILD,
                        confidence = 0.65,
                    ),
                )
            }
        }

        report.codeArtifacts.forEach { artifact ->
            val artifactId = "artifact:${artifact.id}"
            node(
                Node(
                    id = artifactId,
                    kind = NodeKind.ARTIFACT,
                    label = artifact.label,
                    timestamp = artifact.timestamp,
                    evidenceMessageId = artifact.messageId,
                ),
            )
            edge(Edge(projectNodeId, artifactId, EdgeKind.PRODUCED_ARTIFACT, 1.0))
            val sourceFact = allFacts.firstOrNull { it.messageId == artifact.messageId }
            if (sourceFact != null) {
                edge(Edge("evidence:${sourceFact.id}", artifactId, EdgeKind.PRODUCED_ARTIFACT, 1.0))
            }
        }

        return Graph(report.projectId, nodes.values.toList(), edges.values.toList())
    }

    private fun projectConversations(projectId: String): List<ConversationNode> =
        database.readableDatabase.rawQuery(
            """
            SELECT c.id,c.title_iv,c.title_ct,COALESCE(c.updated_at,c.created_at),pc.confidence
            FROM project_conversations pc
            JOIN conversations c ON c.id=pc.conversation_id
            WHERE pc.project_id=?
            ORDER BY COALESCE(c.updated_at,c.created_at,0) DESC
            """.trimIndent(),
            arrayOf(projectId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    add(
                        ConversationNode(
                            id = id,
                            title = crypto.decryptString(
                                EncryptedBlob(cursor.getBlob(1), cursor.getBlob(2)),
                                "conversation-title:$id",
                            ),
                            timestamp = cursor.nullableDouble(3),
                            confidence = cursor.getDouble(4),
                        ),
                    )
                }
            }
        }

    private data class ConversationNode(
        val id: String,
        val title: String,
        val timestamp: Double?,
        val confidence: Double,
    )

    private fun Cursor.nullableDouble(index: Int): Double? = if (isNull(index)) null else getDouble(index)
}
