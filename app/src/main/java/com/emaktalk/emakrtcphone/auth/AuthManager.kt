package com.emaktalk.emakrtcphone.auth

import android.content.Context
import android.util.Log
import com.emaktalk.emakrtcphone.sip.SipCoreManager
import com.emaktalk.emakrtcphone.sip.VertoTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the app-level sign-in gate that sits in front of the dialer. It checks
 * credentials against the Emak auth backend ([AuthApi]) and persists the
 * returned tokens ([AuthTokenStore]) so a returning user is sent straight into
 * the app while their session is still valid.
 *
 * This is intentionally separate from the Verto/SIP registration in
 * [com.emaktalk.emakrtcphone.sip.SipCoreManager]: this gate decides whether the
 * user may open the app at all; SIP registration decides whether they can place
 * calls. Modeled as an `object` for the same reason — a single process-wide hub
 * the UI observes via [state].
 */
object AuthManager {

    private const val TAG = "AuthManager"

    /**
     * Host the Verto WebSocket connects to. The SIP domain is the extension's
     * `accountcode`, but signaling lives on this dedicated FreeSWITCH host.
     */
    private const val SIP_WS_HOST = "f1-dev.emaktech.com"

    /** Where the user is in the sign-in flow; drives the login-vs-app navigation. */
    enum class State {
        /** Saved session not yet checked (initial value before [initialize]). */
        Unknown,

        /** No valid session — the login screen must be shown. */
        LoggedOut,

        /** A login request is in flight. */
        LoggingIn,

        /** Authenticated with a valid (or restored) session. */
        LoggedIn,

        /** The last login attempt failed; see [error] for the reason. */
        Failed
    }

    private lateinit var tokenStore: AuthTokenStore
    private val api = AuthApi()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(State.Unknown)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Last login error, shown on the login screen then superseded by the next attempt. */
    private val _error = MutableStateFlow("")
    val error: StateFlow<String> = _error.asStateFlow()

    /**
     * Reads back any saved session. Call once at app launch (before the first
     * frame) so the start destination can be chosen without a flash of the login
     * screen for an already-signed-in user.
     */
    fun initialize(context: Context) {
        if (::tokenStore.isInitialized) return
        tokenStore = AuthTokenStore(context.applicationContext)
        _state.value = if (tokenStore.hasValidSession) {
            Log.i(TAG, "Restored saved session")
            State.LoggedIn
        } else {
            State.LoggedOut
        }
    }

    val isLoggedIn: Boolean
        get() = _state.value == State.LoggedIn

    /** The bearer token for authenticating backend calls, or null if signed out. */
    val accessToken: String?
        get() = if (::tokenStore.isInitialized) tokenStore.load()?.accessToken else null

    /**
     * Authenticates [username]/[password] against the backend and, on success,
     * persists the tokens and flips [state] to [State.LoggedIn]. Blank input is a
     * no-op. Safe to call from the main thread; the network request runs off it.
     */
    fun login(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) return
        if (_state.value == State.LoggingIn) return

        _error.value = ""
        _state.value = State.LoggingIn
        scope.launch {
            api.login(username.trim(), password)
                .onSuccess { tokens ->
                    tokenStore.save(tokens)
                    _error.value = ""
                    _state.value = State.LoggedIn
                    Log.i(TAG, "Login succeeded for $username")
                    // The access token carries the user's FreeSWITCH extension and
                    // SIP password — register the softphone with them so the user
                    // is ready to call right after signing in.
                    registerSipFromToken(tokens.accessToken)
                }
                .onFailure { e ->
                    _error.value = e.message ?: "Login failed"
                    _state.value = State.Failed
                    Log.w(TAG, "Login failed: ${e.message}")
                }
        }
    }

    /** Clears the saved session, signs the softphone out, and returns to login. */
    fun logout() {
        if (::tokenStore.isInitialized) tokenStore.clear()
        SipCoreManager.unregister()
        _error.value = ""
        _state.value = State.LoggedOut
    }

    /**
     * Decodes the access token, pulls the user's extension/SIP password out of
     * its `userExtensions` claim, and registers the Verto softphone. The SIP
     * domain is the extension's `accountcode`. No-op if the token carries no
     * usable extension. The credentials are persisted by [SipCoreManager], so a
     * later launch reconnects via its own session restore without re-decoding.
     */
    private fun registerSipFromToken(accessToken: String) {
        val creds = TokenClaims.sipCredentials(accessToken) ?: return
        if (creds.domain.isBlank()) {
            Log.w(TAG, "Extension ${creds.extension} has no accountcode; skipping SIP registration")
            return
        }
        Log.i(TAG, "Registering SIP extension ${creds.extension}@${creds.domain} via $SIP_WS_HOST")
        SipCoreManager.register(
            username = creds.extension,
            password = creds.password,
            domain = creds.domain,
            transport = VertoTransport.WSS,
            host = SIP_WS_HOST
        )
    }
}
