package com.mekromn.continuitybrain.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class EncryptedBlob(
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

/**
 * Device-local cryptographic boundary for Continuity Brain.
 *
 * Sensitive strings are encrypted with AES-256-GCM before they reach SQLite.
 * Search terms and stable private fingerprints use a separate HMAC-SHA-256 key,
 * allowing equality/prefix lookup without persisting the original terms.
 * Neither key is exportable from Android Keystore.
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
     * Keyed one-way value suitable for private equality indexes. The namespace
     * prevents the same plaintext from having the same blind value in unrelated
     * index domains.
     */
    fun blind(value: String, namespace: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(indexKey)
        mac.update(namespace.toByteArray(StandardCharsets.UTF_8))
        mac.update(0)
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)).toHex()
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

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

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val CONTENT_KEY_ALIAS = "continuity_brain_content_v1"
        private const val INDEX_KEY_ALIAS = "continuity_brain_index_v1"
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val GCM_TAG_BITS = 128
    }
}
