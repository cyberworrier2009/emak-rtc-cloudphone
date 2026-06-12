package com.emaktalk.emakrtcphone.sip

import android.content.Context

/** A saved Verto account, persisted so the user stays signed in across restarts. */
data class SavedAccount(
    val username: String,
    val password: String,
    /** SIP domain used in the Verto login id (`username@domain`). */
    val domain: String,
    val transport: VertoTransport,
    /**
     * Host the Verto WebSocket connects to. Often the same as [domain], but the
     * SIP domain (an `accountcode`) and the signaling host can differ — so it is
     * stored separately. Defaults to [domain] for accounts saved before this
     * field existed.
     */
    val host: String = domain
)

/**
 * Persists the Verto login so the user remains "logged in" until they explicitly
 * sign out — even after the app is force-closed or the device reboots. On the
 * next launch [SipCoreManager] reads it back and re-opens the WebSocket
 * automatically.
 *
 * NOTE: this uses plain [android.content.SharedPreferences], so the password is
 * stored in the app's private (but unencrypted) prefs. For a production build,
 * back this with EncryptedSharedPreferences (androidx.security:security-crypto)
 * or the Android Keystore.
 */
class AccountStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(account: SavedAccount) {
        prefs.edit()
            .putString(KEY_USERNAME, account.username)
            .putString(KEY_PASSWORD, account.password)
            .putString(KEY_DOMAIN, account.domain)
            .putString(KEY_TRANSPORT, account.transport.name)
            .putString(KEY_HOST, account.host)
            .apply()
    }

    fun load(): SavedAccount? {
        val username = prefs.getString(KEY_USERNAME, null) ?: return null
        val domain = prefs.getString(KEY_DOMAIN, null) ?: return null
        val password = prefs.getString(KEY_PASSWORD, "").orEmpty()
        val transport = runCatching {
            VertoTransport.valueOf(prefs.getString(KEY_TRANSPORT, VertoTransport.WSS.name)!!)
        }.getOrDefault(VertoTransport.WSS)
        val host = prefs.getString(KEY_HOST, domain).orEmpty().ifBlank { domain }
        return SavedAccount(username, password, domain, transport, host)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS = "verto_account"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_DOMAIN = "domain"
        const val KEY_TRANSPORT = "transport"
        const val KEY_HOST = "host"
    }
}
