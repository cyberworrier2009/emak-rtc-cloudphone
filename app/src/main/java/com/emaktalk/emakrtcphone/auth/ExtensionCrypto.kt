package com.emaktalk.emakrtcphone.auth

import android.util.Base64
import android.util.Log
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object ExtensionCrypto {

    private const val TAG = "ExtensionCrypto"

    private const val ENC_KEY = "8e2e51308a6fdd727ea63a7cbf4496fa8d089fdcc857d4e0c8bb61ba5936c8e1"

    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    private val key: SecretKeySpec by lazy {
        val derived = MessageDigest.getInstance("SHA-256")
            .digest(ENC_KEY.toByteArray(Charsets.UTF_8))
        SecretKeySpec(derived, "AES")
    }

    fun decryptPassword(encoded: String): String? {
        if (encoded.isBlank()) return null
        return runCatching {
            val blob = Base64.decode(
                encoded,
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            )
            require(blob.size > IV_LENGTH + TAG_BITS / 8) { "ciphertext too short" }

            val iv = blob.copyOfRange(0, IV_LENGTH)

            val cipherTextWithTag = blob.copyOfRange(IV_LENGTH, blob.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(cipherTextWithTag), Charsets.UTF_8)
        }.getOrElse {
            Log.w(TAG, "Failed to decrypt extension password: ${it.message}")
            null
        }
    }
}
