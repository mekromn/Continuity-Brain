package com.mekromn.continuitybrain.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.DigestInputStream
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class EncryptedBlob(
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

data class EncryptedStreamResult(
    val sha256: String,
    val plaintextBytes: Long,
)

/**
 * Device-local cryptographic boundary for Continuity Brain.
 *
 * Sensitive strings are encrypted with AES-256-GCM before they reach SQLite.
 * Search terms and stable private fingerprints use a separate HMAC-SHA-256 key,
 * allowing useful lookup without persisting readable terms. Neither key is
 * exportable from Android Keystore.
 */
class CryptoVault {
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private val contentKey: SecretKey by lazy { getOrCreateContentKey() }
    private val indexKey: SecretKey by lazy { getOrCreateIndexKey() }

    fun encrypt(text: String, aad: String): EncryptedBlob =
        encrypt(text.toByteArray(StandardCharsets.UTF_8), aad)

    fun encrypt(bytes: ByteArray, aad: String): EncryptedBlob {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, contentKey)
        cipher.updateAAD(aad.toByteArray(StandardCharsets.UTF_8))
        return EncryptedBlob(
            iv = cipher.iv,
            ciphertext = cipher.doFinal(bytes),
        )
    }

    fun decrypt(blob: EncryptedBlob, aad: String): ByteArray {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, contentKey, GCMParameterSpec(GCM_TAG_BITS, blob.iv))
        cipher.updateAAD(aad.toByteArray(StandardCharsets.UTF_8))
        return cipher.doFinal(blob.ciphertext)
    }

    fun decryptString(blob: EncryptedBlob, aad: String): String =
        String(decrypt(blob, aad), StandardCharsets.UTF_8)

    /**
     * Streaming encrypted-file format used for attachments. Header fields reveal
     * only the format version and random IV; names and file contents remain
     * encrypted. The plaintext SHA-256 is returned for local deduplication.
     */
    fun encryptStream(input: InputStream, output: OutputStream, aad: String): EncryptedStreamResult {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, contentKey)
        cipher.updateAAD(aad.toByteArray(StandardCharsets.UTF_8))
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L

        DataOutputStream(output).use { data ->
            data.write(FILE_MAGIC)
            data.writeInt(FILE_VERSION)
            data.writeInt(cipher.iv.size)
            data.write(cipher.iv)
            CipherOutputStream(data, cipher).use { encrypted ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    digest.update(buffer, 0, read)
                    encrypted.write(buffer, 0, read)
                    total += read
                }
            }
        }
        return EncryptedStreamResult(digest.digest().toHex(), total)
    }

    fun decryptStream(input: InputStream, output: OutputStream, aad: String) {
        val data = DataInputStream(input)
        val magic = ByteArray(FILE_MAGIC.size)
        data.readFully(magic)
        require(magic.contentEquals(FILE_MAGIC)) { "Not a Continuity Brain encrypted file" }
        require(data.readInt() == FILE_VERSION) { "Unsupported encrypted file version" }
        val ivLength = data.readInt()
        require(ivLength in 12..32) { "Invalid encrypted file IV" }
        val iv = ByteArray(ivLength)
        data.readFully(iv)
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, contentKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad.toByteArray(StandardCharsets.UTF_8))
        CipherInputStream(data, cipher).use { decrypted -> decrypted.copyTo(output) }
    }

    /**
     * Keyed one-way value suitable for private equality indexes. The namespace
     * prevents the same plaintext from having the same blind value in unrelated
     * index domains.
     */
    fun blind(value: String, namespace: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(indexKey)
        mac.update(namespace.toByteArray(StandardCharsets.UTF_8))
        mac.update(0.toByte())
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)).toHex()
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun sha256(input: InputStream): String = DigestInputStream(
        input,
        MessageDigest.getInstance("SHA-256"),
    ).use { digestInput ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (digestInput.read(buffer) >= 0) Unit
        digestInput.messageDigest.digest().toHex()
    }

    private fun getOrCreateContentKey(): SecretKey {
        (keyStore.getKey(CONTENT_KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                CONTENT_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun getOrCreateIndexKey(): SecretKey {
        (keyStore.getKey(INDEX_KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                INDEX_KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val CONTENT_KEY_ALIAS = "continuity_brain_content_v1"
        private const val INDEX_KEY_ALIAS = "continuity_brain_index_v1"
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val GCM_TAG_BITS = 128
        private const val FILE_VERSION = 1
        private val FILE_MAGIC = byteArrayOf(0x43, 0x42, 0x45, 0x4E, 0x43, 0x31) // CBENC1
    }
}
