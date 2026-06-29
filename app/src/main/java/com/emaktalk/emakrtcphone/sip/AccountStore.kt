package com.emaktalk.emakrtcphone.sip

import android.content.Context

data class SavedAccount(
    val username: String,
    val password: String,

    val domain: String,
    val transport: VertoTransport,

    val host: String = domain
)

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
