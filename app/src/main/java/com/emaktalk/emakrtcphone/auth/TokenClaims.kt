package com.emaktalk.emakrtcphone.auth

import android.util.Base64
import android.util.Log
import org.json.JSONObject

data class SipCredentials(
    val extension: String,
    val password: String,

    val domain: String,
    val callerIdName: String
)

object TokenClaims {

    private const val TAG = "TokenClaims"

    fun decodePayload(jwt: String): JSONObject? {
        val parts = jwt.split(".")
        if (parts.size < 2) return null
        return runCatching {
            val bytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            JSONObject(String(bytes, Charsets.UTF_8))
        }.getOrNull()
    }

    fun userId(accessToken: String): String? {
        val payload = decodePayload(accessToken) ?: return null
        return payload.optString("sub").takeIf { it.isNotBlank() }
    }

    fun sipCredentials(accessToken: String): SipCredentials? =
        allSipCredentials(accessToken).firstOrNull()

    fun allSipCredentials(accessToken: String): List<SipCredentials> {
        val payload = decodePayload(accessToken) ?: run {
            Log.w(TAG, "Access token is not a decodable JWT")
            return emptyList()
        }
        val extensions = payload.optJSONArray("userExtensions") ?: return emptyList()
        val result = mutableListOf<SipCredentials>()
        for (i in 0 until extensions.length()) {
            val ext = extensions.optJSONObject(i) ?: continue
            if (ext.optString("enabled", "true").equals("false", ignoreCase = true)) continue

            val extension = ext.optString("extension")
            val encryptedPassword = ext.optString("password")
            if (extension.isBlank() || encryptedPassword.isBlank()) continue

            val password = ExtensionCrypto.decryptPassword(encryptedPassword)
            if (password.isNullOrBlank()) {
                Log.w(TAG, "Could not decrypt password for extension $extension")
                continue
            }

            result.add(
                SipCredentials(
                    extension = extension,
                    password = password,
                    domain = ext.optString("accountcode"),
                    callerIdName = ext.optString("effective_caller_id_name")
                )
            )
        }
        if (result.isEmpty()) Log.w(TAG, "Token had no enabled extension with a password")
        return result
    }
}
