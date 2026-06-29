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

object AuthManager {

    private const val TAG = "AuthManager"

    private const val SIP_WS_HOST = "f1-dev.emaktech.com"

    enum class State {

        Unknown,

        LoggedOut,

        LoggingIn,

        SelectingExtension,

        LoggedIn,

        Failed
    }

    data class ExtensionOption(val extension: String, val callerIdName: String) {

        val label: String get() = callerIdName.ifBlank { extension }
    }

    private lateinit var tokenStore: AuthTokenStore
    private val api = AuthApi()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val refreshMutex = Mutex()

    private const val ACCESS_EXPIRY_SKEW_MS = 30_000L

    private val _state = MutableStateFlow(State.Unknown)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _error = MutableStateFlow("")
    val error: StateFlow<String> = _error.asStateFlow()

    private val _availableExtensions = MutableStateFlow<List<ExtensionOption>>(emptyList())
    val availableExtensions: StateFlow<List<ExtensionOption>> = _availableExtensions.asStateFlow()

    private var pendingExtensions: List<SipCredentials> = emptyList()

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

    val accessToken: String?
        get() = if (::tokenStore.isInitialized) tokenStore.load()?.accessToken else null

    val userId: String?
        get() = accessToken?.let { TokenClaims.userId(it) }

    suspend fun refreshIfNeeded(): Boolean {
        if (!::tokenStore.isInitialized) return false
        val current = tokenStore.load() ?: return false
        if (current.accessExpiresAtMillis - ACCESS_EXPIRY_SKEW_MS > System.currentTimeMillis()) {
            return true
        }
        return refreshMutex.withLock {

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

                    Log.w(TAG, "Token refresh failed: ${e.message}")
                    false
                }
            )
        }
    }

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

    fun selectExtension(extension: String) {
        if (_state.value != State.SelectingExtension) return
        val creds = pendingExtensions.firstOrNull { it.extension == extension } ?: return
        pendingExtensions = emptyList()
        _availableExtensions.value = emptyList()
        _state.value = State.LoggedIn
        Log.i(TAG, "User selected extension ${creds.extension}")
        registerSip(creds)
    }

    fun logout() {
        if (::tokenStore.isInitialized) tokenStore.clear()
        SipCoreManager.unregister()
        pendingExtensions = emptyList()
        _availableExtensions.value = emptyList()
        _error.value = ""
        _state.value = State.LoggedOut
    }

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
