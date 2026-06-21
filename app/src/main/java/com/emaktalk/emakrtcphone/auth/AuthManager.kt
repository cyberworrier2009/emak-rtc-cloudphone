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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

        /**
         * Credentials accepted, but the token carries multiple extensions — the
         * user must pick one (see [availableExtensions]) before [selectExtension].
         */
        SelectingExtension,

        /** Authenticated with a valid (or restored) session. */
        LoggedIn,

        /** The last login attempt failed; see [error] for the reason. */
        Failed
    }

    /** A pickable extension shown on the selection screen. */
    data class ExtensionOption(val extension: String, val callerIdName: String) {
        /** Label for the list row: caller-id name when present, else the number. */
        val label: String get() = callerIdName.ifBlank { extension }
    }

    private lateinit var tokenStore: AuthTokenStore
    private val api = AuthApi()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Serializes token refreshes so two concurrent authed calls don't both spend
    // the (rotating) refresh token; the loser sees the already-refreshed token.
    private val refreshMutex = Mutex()

    // Refresh a little before the access token actually lapses, to absorb clock
    // skew and in-flight request latency.
    private const val ACCESS_EXPIRY_SKEW_MS = 30_000L

    private val _state = MutableStateFlow(State.Unknown)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Last login error, shown on the login screen then superseded by the next attempt. */
    private val _error = MutableStateFlow("")
    val error: StateFlow<String> = _error.asStateFlow()

    /** Extensions to choose from while [state] is [State.SelectingExtension]. */
    private val _availableExtensions = MutableStateFlow<List<ExtensionOption>>(emptyList())
    val availableExtensions: StateFlow<List<ExtensionOption>> = _availableExtensions.asStateFlow()

    /** Decrypted credentials for the extensions awaiting selection. */
    private var pendingExtensions: List<SipCredentials> = emptyList()

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

    /** The platform user id (for TURN credential requests), or null if signed out. */
    val userId: String?
        get() = accessToken?.let { TokenClaims.userId(it) }

    /**
     * Ensures [accessToken]/[userId] return a non-expired token, refreshing it
     * via the (rotating) refresh token when it has lapsed. Call this from a
     * coroutine before any authenticated backend request (e.g. the TURN fetch) —
     * the backend access token is short-lived (~3h) and was previously never
     * refreshed, so calls like TURN silently 401'd once it expired.
     *
     * @return true if a usable, non-expired access token is now stored; false if
     *   the refresh token is also gone/expired (the user must sign in again — we
     *   flip [state] to [State.LoggedOut] so the UI routes back to login).
     */
    suspend fun refreshIfNeeded(): Boolean {
        if (!::tokenStore.isInitialized) return false
        val current = tokenStore.load() ?: return false
        if (current.accessExpiresAtMillis - ACCESS_EXPIRY_SKEW_MS > System.currentTimeMillis()) {
            return true // still valid; no refresh needed
        }
        return refreshMutex.withLock {
            // Re-check under the lock: a concurrent caller may have just refreshed.
            val latest = tokenStore.load() ?: return@withLock false
            val now = System.currentTimeMillis()
            if (latest.accessExpiresAtMillis - ACCESS_EXPIRY_SKEW_MS > now) return@withLock true

            if (latest.refreshToken.isBlank() || latest.refreshExpiresAtMillis <= now) {
                Log.w(TAG, "Refresh token missing/expired; sign-in required")
                _state.value = State.LoggedOut
                return@withLock false
            }

            api.refresh(latest.refreshToken).fold(
                onSuccess = { fresh ->
                    tokenStore.save(fresh)
                    Log.i(TAG, "Access token refreshed")
                    true
                },
                onFailure = { e ->
                    // Transient (network) failure: keep the session, let the caller
                    // proceed/fail; don't log the user out over a blip.
                    Log.w(TAG, "Token refresh failed: ${e.message}")
                    false
                }
            )
        }
    }

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
                    Log.i(TAG, "Login succeeded for $username")
                    // The access token carries the user's FreeSWITCH extension(s)
                    // and SIP password(s). One extension → register straight away;
                    // several → ask the user which to sign in with.
                    val extensions = TokenClaims.allSipCredentials(tokens.accessToken)
                    if (extensions.size > 1) {
                        pendingExtensions = extensions
                        _availableExtensions.value = extensions.map {
                            ExtensionOption(it.extension, it.callerIdName)
                        }
                        _state.value = State.SelectingExtension
                        Log.i(TAG, "Token has ${extensions.size} extensions; awaiting selection")
                    } else {
                        _state.value = State.LoggedIn
                        extensions.firstOrNull()?.let { registerSip(it) }
                    }
                }
                .onFailure { e ->
                    _error.value = e.message ?: "Login failed"
                    _state.value = State.Failed
                    Log.w(TAG, "Login failed: ${e.message}")
                }
        }
    }

    /**
     * Completes sign-in by registering the chosen [extension] (from
     * [availableExtensions]). No-op unless we're awaiting a selection.
     */
    fun selectExtension(extension: String) {
        if (_state.value != State.SelectingExtension) return
        val creds = pendingExtensions.firstOrNull { it.extension == extension } ?: return
        pendingExtensions = emptyList()
        _availableExtensions.value = emptyList()
        _state.value = State.LoggedIn
        Log.i(TAG, "User selected extension ${creds.extension}")
        registerSip(creds)
    }

    /** Clears the saved session, signs the softphone out, and returns to login. */
    fun logout() {
        if (::tokenStore.isInitialized) tokenStore.clear()
        SipCoreManager.unregister()
        pendingExtensions = emptyList()
        _availableExtensions.value = emptyList()
        _error.value = ""
        _state.value = State.LoggedOut
    }

    /**
     * Registers the Verto softphone with [creds]. The SIP domain is the
     * extension's `accountcode`. The credentials are persisted by
     * [SipCoreManager], so a later launch reconnects via its own session restore
     * without re-decoding or re-prompting for the extension.
     */
    private fun registerSip(creds: SipCredentials) {
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
