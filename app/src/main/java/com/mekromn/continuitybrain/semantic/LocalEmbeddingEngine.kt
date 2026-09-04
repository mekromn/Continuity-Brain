package com.mekromn.continuitybrain.semantic

import android.content.ContentValues
import android.content.Context
import com.mekromn.continuitybrain.data.BrainDatabase
import com.mekromn.continuitybrain.data.CryptoVault
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.sqrt

/**
 * Telemetry-free local text embedding runner using standalone LiteRT.
 *
 * Continuity Brain intentionally does not use MediaPipe Tasks because current
 * MediaPipe binaries document utilization/performance telemetry. This engine
 * accepts string-input TFLite embedders directly. The official Universal
 * Sentence Encoder model is supported by its inp_text/res_context/res_text
 * input names and query_encoding output.
 */
class LocalEmbeddingEngine(
    private val context: Context,
    private val database: BrainDatabase,
    private val crypto: CryptoVault,
) : Closeable {
    data class ModelInfo(
        val hash: String,
        val path: String,
        val dimensions: Int,
    )

    data class Embedding(
        val quantized: ByteArray,
        val dimensions: Int,
        val signature: Long,
    )

    private val lock = Any()
    @Volatile private var loadedHash: String? = null
    @Volatile private var interpreter: Interpreter? = null

    fun installModel(input: InputStream): ModelInfo {
        val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
        val temp = File(modelsDir, "embedding-model.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        temp.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val target = File(modelsDir, "$hash.tflite")
        if (!target.exists()) {
            check(temp.renameTo(target)) { "Unable to install embedding model" }
        } else {
            temp.delete()
        }

        val validation = openInterpreter(target)
        val dimensions = try {
            validateModel(validation)
        } finally {
            validation.close()
        }

        val db = database.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE embedding_models SET active=0")
            val values = ContentValues().apply {
                put("model_hash", hash)
                put("engine", ENGINE)
                put("model_path", target.absolutePath)
                put("dimensions", dimensions)
                put("installed_at", nowSeconds())
                put("active", 1)
            }
            db.insertWithOnConflict(
                "embedding_models",
                null,
                values,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        synchronized(lock) {
            interpreter?.close()
            interpreter = null
            loadedHash = null
        }
        return ModelInfo(hash, target.absolutePath, dimensions)
    }

    fun activeModel(): ModelInfo? = database.readableDatabase.rawQuery(
        "SELECT model_hash,model_path,dimensions FROM embedding_models WHERE active=1 ORDER BY installed_at DESC LIMIT 1",
        null,
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        ModelInfo(cursor.getString(0), cursor.getString(1), cursor.getInt(2))
    }

    fun isReady(): Boolean = activeModel()?.let { File(it.path).isFile } == true

    fun embed(text: String): Embedding {
        require(text.isNotBlank()) { "Cannot embed empty text" }
        val model = activeModel() ?: error("No local embedding model is installed")
        return synchronized(lock) {
            val runtime = ensureInterpreter(model)
            val vector = runModel(runtime, text.take(MAX_INPUT_CHARS))
            val normalized = normalize(vector)
            val quantized = ByteArray(normalized.size) { index ->
                (normalized[index].coerceIn(-1f, 1f) * 127f)
                    .toInt()
                    .coerceIn(-127, 127)
                    .toByte()
            }
            Embedding(
                quantized = quantized,
                dimensions = quantized.size,
                signature = similaritySignature(normalized),
            )
        }
    }

    override fun close() {
        synchronized(lock) {
            interpreter?.close()
            interpreter = null
            loadedHash = null
        }
    }

    private fun ensureInterpreter(model: ModelInfo): Interpreter {
        interpreter?.takeIf { loadedHash == model.hash }?.let { return it }
        interpreter?.close()
        return openInterpreter(File(model.path)).also {
            validateModel(it)
            interpreter = it
            loadedHash = model.hash
        }
    }

    private fun openInterpreter(file: File): Interpreter {
        require(file.isFile) { "Embedding model file is missing" }
        return Interpreter(
            file,
            Interpreter.Options().apply {
                setNumThreads(minOf(4, Runtime.getRuntime().availableProcessors().coerceAtLeast(1)))
            },
        )
    }

    private fun validateModel(runtime: Interpreter): Int {
        require(runtime.inputTensorCount in 1..3) {
            "Embedding model must have one string input, or the three Universal Sentence Encoder inputs"
        }
        for (index in 0 until runtime.inputTensorCount) {
            val tensor = runtime.getInputTensor(index)
            require(tensor.dataType() == DataType.STRING) {
                "This private runner currently supports string-input TFLite embedders only; input '${tensor.name()}' is ${tensor.dataType()}"
            }
        }
        val outputIndex = queryOutputIndex(runtime)
        val output = runtime.getOutputTensor(outputIndex)
        require(output.dataType() == DataType.FLOAT32) {
            "Embedding output '${output.name()}' must be FLOAT32"
        }
        val dimensions = output.numElements()
        require(dimensions in 32..8192) { "Unexpected embedding dimension: $dimensions" }
        return dimensions
    }

    private fun runModel(runtime: Interpreter, text: String): FloatArray {
        val inputs = Array<Any>(runtime.inputTensorCount) { index ->
            val name = runtime.getInputTensor(index).name().lowercase()
            when {
                runtime.inputTensorCount == 1 -> arrayOf(text)
                "inp_text" in name || "query" in name -> arrayOf(text)
                "res_context" in name || "context" in name -> arrayOf("")
                "res_text" in name || "response" in name -> arrayOf("")
                index == 0 -> arrayOf(text)
                else -> arrayOf("")
            }
        }
        val outputIndex = queryOutputIndex(runtime)
        val tensor = runtime.getOutputTensor(outputIndex)
        val output = ByteBuffer.allocateDirect(tensor.numBytes()).order(ByteOrder.nativeOrder())
        runtime.runForMultipleInputsOutputs(inputs, mapOf(outputIndex to output))
        output.rewind()
        return FloatArray(tensor.numElements()) { output.float }
    }

    private fun queryOutputIndex(runtime: Interpreter): Int {
        for (index in 0 until runtime.outputTensorCount) {
            val name = runtime.getOutputTensor(index).name().lowercase()
            if ("query_encoding" in name || ("query" in name && "encoding" in name)) return index
        }
        return 0
    }

    private fun normalize(vector: FloatArray): FloatArray {
        var sumSquares = 0.0
        for (value in vector) sumSquares += value.toDouble() * value.toDouble()
        val norm = sqrt(sumSquares).toFloat()
        if (norm <= 1e-12f) return vector
        return FloatArray(vector.size) { vector[it] / norm }
    }

    /** 64 deterministic random hyperplanes; useful for fast Hamming shortlist. */
    private fun similaritySignature(vector: FloatArray): Long {
        val accumulators = FloatArray(64)
        vector.forEachIndexed { dimension, value ->
            val bits = mix64(dimension.toLong() + SIGNATURE_SEED)
            for (bit in 0 until 64) {
                accumulators[bit] += if (((bits ushr bit) and 1L) != 0L) value else -value
            }
        }
        var signature = 0L
        for (bit in 0 until 64) {
            if (accumulators[bit] >= 0f) signature = signature or (1L shl bit)
        }
        return signature
    }

    private fun mix64(value: Long): Long {
        var z = value
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        return z xor (z ushr 31)
    }

    private fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0

    companion object {
        private const val ENGINE = "litert-string-v1"
        private const val MAX_INPUT_CHARS = 12_000
        private const val SIGNATURE_SEED = -7046029254386353131L
    }
}
