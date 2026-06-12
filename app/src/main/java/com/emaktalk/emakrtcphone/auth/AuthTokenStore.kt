package com.emaktalk.emakrtcphone.auth

import android.content.Context

/**
 * The OAuth tokens returned by `POST /api/v1/auth/login`, plus the absolute
 * (wall-clock) instants the access and refresh tokens expire. The server hands
 * back relative lifetimes (`expires_in` / `refresh_expires_in`, in seconds); we
 * convert them to absolute timestamps at save time so a later launch can tell
 * whether the saved session is still valid without re-deriving "now minus then".
 */
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val accessExpiresAtMillis: Long,
    val refreshExpiresAtMillis: Long
)

/**
 * Persists the login tokens so the user stays signed in across app restarts and
 * reboots, and the login screen is skipped on the next launch while the session
 * is still valid.
 *
 * NOTE: this uses plain [android.content.SharedPreferences], so the tokens are
 * stored in the app's private (but unencrypted) prefs — the same trade-off as
 * [com.emaktalk.emakrtcphone.sip.AccountStore]. For a production build, back
 * this with EncryptedSharedPreferences (androidx.security:security-crypto).
 */
class AuthTokenStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(tokens: AuthTokens) {
        prefs.edit()
            .putString(KEY_ACCESS, tokens.accessToken)
            .putString(KEY_REFRESH, tokens.refreshToken)
            .putString(KEY_TYPE, tokens.tokenType)
            .putLong(KEY_ACCESS_EXP, tokens.accessExpiresAtMillis)
            .putLong(KEY_REFRESH_EXP, tokens.refreshExpiresAtMillis)
            .apply()
    }

    fun load(): AuthTokens? {
        val access = prefs.getString(KEY_ACCESS, null) ?: return null
        return AuthTokens(
            accessToken = access,
            refreshToken = prefs.getString(KEY_REFRESH, "").orEmpty(),
            tokenType = prefs.getString(KEY_TYPE, "Bearer").orEmpty(),
            accessExpiresAtMillis = prefs.getLong(KEY_ACCESS_EXP, 0L),
            refreshExpiresAtMillis = prefs.getLong(KEY_REFRESH_EXP, 0L)
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /**
     * True when a saved session exists and the refresh token has not yet expired,
     * i.e. the user can be sent straight into the app without logging in again.
     */
    val hasValidSession: Boolean
        get() {
            val tokens = load() ?: return false
            return tokens.refreshExpiresAtMillis > System.currentTimeMillis()
        }

    private companion object {
        const val PREFS = "emak_auth"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_TYPE = "token_type"
        const val KEY_ACCESS_EXP = "access_expires_at"
        const val KEY_REFRESH_EXP = "refresh_expires_at"
    }
}
