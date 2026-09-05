package com.mekromn.continuitybrain.bridge

import com.mekromn.continuitybrain.data.BrainRepository
import com.mekromn.continuitybrain.retrieval.BrainRetrievalService
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tiny authenticated HTTP/1.1 server bound strictly to loopback.
 *
 * There is intentionally no wildcard/LAN bind and no outbound networking. The
 * Continuity extension can call this endpoint from the same device after the
 * user pairs it with the random bearer token.
 */
class LocalBrainServer(
    private val repository: BrainRepository,
    private val retrieval: BrainRetrievalService,
    private val tokenProvider: () -> String,
    private val port: Int = DEFAULT_PORT,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val workers = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "continuity-brain-bridge-worker").apply { isDaemon = true }
    }
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val socket = ServerSocket(port, 16, InetAddress.getLoopbackAddress())
        socket.reuseAddress = true
        serverSocket = socket
        acceptThread = Thread({ acceptLoop(socket) }, "continuity-brain-bridge").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        running.set(false)
        runCatching { serverSocket?.close() }
        workers.shutdownNow()
        serverSocket = null
        acceptThread = null
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running.get()) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            workers.execute { runCatching { handle(client) }.also { runCatching { client.close() } } }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 15_000
        val input = socket.getInputStream()
        val output = BufferedOutputStream(socket.getOutputStream())
        val requestLine = readAsciiLine(input) ?: return
        val parts = requestLine.split(' ', limit = 3)
        if (parts.size < 2) return respond(output, 400, jsonError("Malformed request"))
        val method = parts[0].uppercase(Locale.ROOT)
        val path = parts[1].substringBefore('?')
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readAsciiLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase(Locale.ROOT)] =
                    line.substring(colon + 1).trim()
            }
        }

        if (method == "OPTIONS") {
            return respond(output, 204, "")
        }

        if (path == "/v1/health") {
            return respond(
                output,
                200,
                JSONObject()
                    .put("ok", true)
                    .put("service", "continuity-brain")
                    .put("protocol", 2)
                    .put("auth_required", true)
                    .toString(),
            )
        }

        val authorization = headers["authorization"].orEmpty()
        val expected = "Bearer ${tokenProvider()}"
        if (!constantTimeEquals(authorization, expected)) {
            return respond(output, 401, jsonError("Unauthorized"))
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength !in 0..MAX_REQUEST_BYTES) {
            return respond(output, 413, jsonError("Request too large"))
        }
        val bodyBytes = if (contentLength > 0) readExactly(input, contentLength) else ByteArray(0)
        val body = if (bodyBytes.isNotEmpty()) {
            runCatching { JSONObject(String(bodyBytes, StandardCharsets.UTF_8)) }.getOrElse {
                return respond(output, 400, jsonError("Invalid JSON"))
            }
        } else JSONObject()

        when {
            method == "GET" && path == "/v1/stats" -> {
                val stats = repository.stats()
                respond(
                    output,
                    200,
                    JSONObject()
                        .put("conversations", stats.conversations)
                        .put("messages", stats.messages)
                        .put("projects", stats.projects)
                        .put("insights", stats.insights)
                        .put("artifacts", stats.artifacts)
                        .put("attachments", stats.attachments)
                        .put("imports", stats.imports)
                        .toString(),
                )
            }

            method == "GET" && path == "/v1/projects" -> {
                val array = JSONArray()
                repository.listProjects(200).forEach { project ->
                    array.put(
                        JSONObject()
                            .put("id", project.id)
                            .put("name", project.name)
                            .put("conversation_count", project.conversationCount)
                            .put("message_count", project.messageCount)
                            .put("insight_count", project.insightCount)
                            .put("updated_at", project.updatedAt),
                    )
                }
                respond(output, 200, JSONObject().put("projects", array).toString())
            }

            method == "POST" && path == "/v1/search" -> {
                val query = body.optString("query").trim()
                if (query.isBlank()) return respond(output, 400, jsonError("query is required"))
                val limit = body.optInt("limit", 30).coerceIn(1, 100)
                val hits = JSONArray()
                retrieval.search(query, limit).forEach { hit ->
                    hits.put(
                        JSONObject()
                            .put("message_id", hit.messageId)
                            .put("conversation_id", hit.conversationId)
                            .put("conversation_title", hit.conversationTitle)
                            .put("role", hit.role)
                            .put("created_at", hit.createdAt)
                            .put("content", hit.content.take(MAX_RESPONSE_EXCERPT_CHARS))
                            .put("score", hit.score),
                    )
                }
                respond(output, 200, JSONObject().put("query", query).put("hits", hits).toString())
            }

            method == "POST" && path == "/v1/context" -> {
                val query = body.optString("query").trim()
                if (query.isBlank()) return respond(output, 400, jsonError("query is required"))
                val maxChars = body.optInt("max_chars", 60_000).coerceIn(4_000, 120_000)
                val pack = retrieval.buildContextPack(query, maxChars)
                respond(
                    output,
                    200,
                    JSONObject()
                        .put("query", pack.query)
                        .put("context", pack.text)
                        .put("evidence_count", pack.evidenceCount)
                        .put("truncated", pack.truncated)
                        .toString(),
                )
            }

            method == "POST" && path == "/v1/live/message" -> {
                val conversationId = body.optString("conversation_id").trim()
                val messageId = body.optString("message_id").trim()
                val role = body.optString("role").trim()
                val content = body.optString("content")
                if (conversationId.isBlank() || messageId.isBlank() || role.isBlank() || content.isBlank()) {
                    return respond(output, 400, jsonError("conversation_id, message_id, role and content are required"))
                }
                val delta = repository.ingestLiveMessage(
                    conversationId = conversationId,
                    title = body.optString("title", "Untitled chat"),
                    messageId = messageId,
                    parentId = body.optString("parent_id").takeIf(String::isNotBlank),
                    role = role,
                    content = content,
                    createdAt = body.optDoubleOrNull("created_at"),
                    updatedAt = body.optDoubleOrNull("updated_at"),
                    ordinal = body.optInt("ordinal", 0),
                )
                respond(
                    output,
                    200,
                    JSONObject()
                        .put("ok", true)
                        .put("added", delta.added)
                        .put("updated", delta.updated)
                        .put("unchanged", delta.unchanged)
                        .toString(),
                )
            }

            else -> respond(output, 404, jsonError("Not found"))
        }
    }

    private fun respond(output: BufferedOutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val statusText = when (status) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            else -> "Error"
        }
        val header = buildString {
            append("HTTP/1.1 $status $statusText\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: Authorization, Content-Type\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        output.write(header)
        if (bytes.isNotEmpty()) output.write(bytes)
        output.flush()
    }

    private fun readAsciiLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(128)
        while (buffer.size() < MAX_HEADER_LINE_BYTES) {
            val value = input.read()
            if (value < 0) return if (buffer.size() == 0) null else buffer.toString(StandardCharsets.US_ASCII.name())
            if (value == '\n'.code) break
            if (value != '\r'.code) buffer.write(value)
        }
        return buffer.toString(StandardCharsets.US_ASCII.name())
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bytes, offset, length - offset)
            if (count < 0) error("Unexpected end of request")
            offset += count
        }
        return bytes
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val left = a.toByteArray(StandardCharsets.UTF_8)
        val right = b.toByteArray(StandardCharsets.UTF_8)
        var diff = left.size xor right.size
        val length = maxOf(left.size, right.size)
        for (index in 0 until length) {
            val l = if (index < left.size) left[index].toInt() else 0
            val r = if (index < right.size) right[index].toInt() else 0
            diff = diff or (l xor r)
        }
        return diff == 0
    }

    private fun jsonError(message: String) = JSONObject().put("error", message).toString()

    private fun JSONObject.optDoubleOrNull(name: String): Double? =
        if (has(name) && !isNull(name)) optDouble(name).takeUnless(Double::isNaN) else null

    companion object {
        const val DEFAULT_PORT = 8765
        private const val MAX_REQUEST_BYTES = 256 * 1024
        private const val MAX_HEADER_LINE_BYTES = 16 * 1024
        private const val MAX_RESPONSE_EXCERPT_CHARS = 24_000
    }
}
