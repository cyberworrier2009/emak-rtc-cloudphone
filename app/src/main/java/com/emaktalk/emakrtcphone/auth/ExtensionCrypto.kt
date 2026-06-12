package com.emaktalk.emakrtcphone.auth

import android.util.Base64
import android.util.Log
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts the SIP extension password carried (encrypted) inside the login
 * token. The backend encrypts each extension password with AES-256-GCM and
 * hands it to the client base64url-encoded as `iv ‖ ciphertext ‖ authTag`:
 *
 *  - a 12-byte GCM nonce/IV, prepended;
 *  - the ciphertext;
 *  - a 16-byte (128-bit) GCM authentication tag, appended.
 *
 * SECURITY: the shared key is compiled into the app, so anyone who unpacks the
 * APK can recover it. That's an inherent weakness of decrypting on the client —
 * it only obscures the password in transit/at rest in the token, it does not
 * make it secret from a determined attacker. Prefer having the backend issue an
 * already-usable credential (or a short-lived per-registration secret) so the
 * key never ships in the client.
 */
object ExtensionCrypto {

    private const val TAG = "ExtensionCrypto"

    /**
     * Shared secret (EXTENSION_ENC_KEY). The backend derives the AES-256 key by
     * taking the SHA-256 digest of this string's UTF-8 bytes (the string is used
     * as a passphrase, NOT hex-decoded), so we do the same.
     */
    private const val ENC_KEY = "8e2e51308a6fdd727ea63a7cbf4496fa8d089fdcc857d4e0c8bb61ba5936c8e1"

    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    private val key: SecretKeySpec by lazy {
        val derived = MessageDigest.getInstance("SHA-256")
            .digest(ENC_KEY.toByteArray(Charsets.UTF_8))
        SecretKeySpec(derived, "AES")
    }

    /**
     * Decrypts a base64url-encoded, AES-256-GCM encrypted extension password.
     * Returns null (and logs) if the input is malformed or authentication fails
     * — e.g. wrong key or a different packing layout than expected.
     */
    fun decryptPassword(encoded: String): String? {
        if (encoded.isBlank()) return null
        return runCatching {
            val blob = Base64.decode(
                encoded,
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            )
            require(blob.size > IV_LENGTH + TAG_BITS / 8) { "ciphertext too short" }

            val iv = blob.copyOfRange(0, IV_LENGTH)
            // Android's Cipher expects ciphertext and tag together for GCM, which
            // is exactly the `ciphertext ‖ tag` tail after the IV.
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
