package com.emaktalk.emakrtcphone.auth

import android.util.Base64
import android.util.Log
import org.json.JSONObject

/** SIP/Verto login pulled out of the access token's `userExtensions` claim. */
data class SipCredentials(
    val extension: String,
    val password: String,
    /** FreeSWITCH SIP domain — the extension's `accountcode` (e.g. emak.emaktalk.com). */
    val domain: String,
    val callerIdName: String
)

/**
 * Reads the claims carried inside the login access token. The backend issues a
 * JWT whose payload embeds the user's FreeSWITCH extension(s) and their SIP
 * passwords, so the app can register the softphone straight after login without
 * a second round-trip.
 *
 * The signature is NOT verified here — we only decode the payload to read the
 * extension we were already handed over an authenticated call. Never trust these
 * claims for an authorization decision on the client.
 */
object TokenClaims {

    private const val TAG = "TokenClaims"

    /** Decodes the (unverified) payload of a JWT, or null if it isn't a well-formed JWT. */
    fun decodePayload(jwt: String): JSONObject? {
        val parts = jwt.split(".")
        if (parts.size < 2) return null
        return runCatching {
            val bytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            JSONObject(String(bytes, Charsets.UTF_8))
        }.getOrNull()
    }

    /**
     * Resolves the platform user id from the access token's `sub` claim, used as
     * the `user` parameter when requesting TURN credentials. Returns null if the
     * token isn't a decodable JWT or carries no `sub`.
     */
    fun userId(accessToken: String): String? {
        val payload = decodePayload(accessToken) ?: return null
        return payload.optString("sub").takeIf { it.isNotBlank() }
    }

    /**
     * Extracts the first enabled extension from an access token, ready to feed
     * into the Verto registration. Returns null if the token carries no usable
     * extension.
     */
    fun sipCredentials(accessToken: String): SipCredentials? =
        allSipCredentials(accessToken).firstOrNull()

    /**
     * Extracts every enabled extension (with a decryptable password) from the
     * access token. When this returns more than one, the user is asked to pick
     * which extension to sign in with; a single entry registers directly.
     */
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

            // The extension password is AES-256-GCM encrypted in the token; the
            // SIP stack needs the plaintext to register.
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
