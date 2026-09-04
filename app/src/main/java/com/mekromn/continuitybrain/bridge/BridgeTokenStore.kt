package com.mekromn.continuitybrain.bridge

import android.util.Base64
import com.mekromn.continuitybrain.data.BrainRepository
import java.security.SecureRandom

class BridgeTokenStore(private val repository: BrainRepository) {
    fun token(): String {
        repository.getEncryptedSetting(KEY)?.takeIf { it.length >= 32 }?.let { return it }
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val token = Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        repository.setEncryptedSetting(KEY, token)
        return token
    }

    fun rotate(): String {
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val token = Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        repository.setEncryptedSetting(KEY, token)
        return token
    }

    companion object {
        private const val KEY = "bridge.session.token.v1"
    }
}
