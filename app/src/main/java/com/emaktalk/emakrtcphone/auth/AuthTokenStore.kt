package com.emaktalk.emakrtcphone.auth

import android.content.Context

data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val accessExpiresAtMillis: Long,
    val refreshExpiresAtMillis: Long
)

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
