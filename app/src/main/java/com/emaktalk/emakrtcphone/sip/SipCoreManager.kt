package com.emaktalk.emakrtcphone.sip

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.emaktalk.emakrtcphone.audio.AudioRoute
import com.emaktalk.emakrtcphone.audio.CallAudioManager
import com.emaktalk.emakrtcphone.audio.MediaButtonHandle
import com.emaktalk.emakrtcphone.network.NetworkMonitor
import com.emaktalk.emakrtcphone.network.NetworkSnapshot
import com.emaktalk.emakrtcphone.network.NetworkType
import com.emaktalk.emakrtcphone.verto.VertoClient
import com.emaktalk.emakrtcphone.webrtc.WebRtcEngine
import com.emaktalk.emakrtcphone.webrtc.WebRtcSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.PeerConnection
import java.util.UUID

/**
 * Central hub for the app's telephony, the WebRTC + Verto analogue of the
 * reference project's Linphone `SipCoreManager`. It owns:
 *
 *  - the [VertoClient] WebSocket signaling to FreeSWITCH `mod_verto`,
 *  - the per-call WebRTC [WebRtcSession] (media via `mod_rtc`),
 *  - the platform audio session ([CallAudioManager]),
 *  - network-handoff handling ([NetworkMonitor]),
 *  - and the public StateFlows the UI renders.
 *
 * Public methods are expected to be called from the main thread; Verto and
 * WebRTC callbacks are marshalled back onto it before they touch state.
 */
object SipCoreManager {

    private const val TAG = "SipCoreManager"

    private lateinit var appContext: Context
    private lateinit var audio: CallAudioManager
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var mediaButtons: MediaButtonHandle
    private lateinit var accountStore: AccountStore
    private val verto = VertoClient()

    private val scope = CoroutineScope(Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private val _registrationState = MutableStateFlow(RegistrationState.None)
    val registrationState: StateFlow<RegistrationState> = _registrationState.asStateFlow()

    private val _registrationMessage = MutableStateFlow("")
    val registrationMessage: StateFlow<String> = _registrationMessage.asStateFlow()

    private val _callState = MutableStateFlow<CallUiState?>(null)
    val callState: StateFlow<CallUiState?> = _callState.asStateFlow()

    /** One-shot reason the last call failed/ended, shown to the user then cleared. */
    private val _callError = MutableStateFlow<String?>(null)
    val callError: StateFlow<String?> = _callError.asStateFlow()

    // ----- Active-call bookkeeping ------------------------------------------
    private var session: WebRtcSession? = null
    private var callId: String? = null
    private var pendingIncomingOffer: String? = null
    private var callIsOutgoing = false
    private var remoteAnswerApplied = false
    private var callDisplayName = ""
    private var callRemoteUri = ""
    private var currentState = CallState.Idle

    private var muted = false
    private var phase: ConnectionPhase = ConnectionPhase.Healthy
    private var quality: CallQualityStats = CallQualityStats.EMPTY
    private var lastNetwork: NetworkSnapshot = NetworkSnapshot.DISCONNECTED

    // True once the WebRTC media path has reported CONNECTED at least once for
    // the current call. Used so a connected call never shows the Reconnecting
    // banner just because the signaling answer hasn't landed yet, and so a
    // transient DISCONNECTED can be told apart from a never-connected call.
    private var mediaConnected = false

    private var toneGenerator: ToneGenerator? = null

    // Quality-degradation hysteresis (identical policy to the reference): avoid
    // flapping the "Reconnecting" banner on a single bad stats sample.
    private var consecutivePoorSamples = 0
    private var consecutiveGoodSamples = 0
    private var degradedByQuality = false

    private const val MOS_FLOOR = 2.0f
    private const val MOS_HEALTHY = 3.2f
    private const val POOR_SAMPLES_TO_DEGRADE = 3
    private const val GOOD_SAMPLES_TO_RECOVER = 5

    private var pendingRegistration: PendingRegistration? = null

    private data class PendingRegistration(
        val username: String,
        val password: String,
        val domain: String,
        val transport: VertoTransport,
        val wsUrl: String
    )

    // Stats are polled (~1Hz) from the active WebRtcSession while a call is up.
    private val statsRunnable = object : Runnable {
        override fun run() {
            val s = session ?: return
            s.pollStats { stats -> handler.post { onStatsUpdated(stats) } }
            handler.postDelayed(this, 1000)
        }
    }

    // WebRTC briefly reports DISCONNECTED on transient loss and usually returns
    // to CONNECTED on its own. We only surface "Reconnecting" if it stays down
    // past this grace window, so a healthy call doesn't flicker the banner.
    private const val DISCONNECT_GRACE_MS = 4000L
    private val disconnectGraceRunnable = Runnable {
        if (session != null && mediaConnected) {
            phase = ConnectionPhase.Reconnecting
            _callState.value = _callState.value?.copy(connectionPhase = phase)
            Log.w(TAG, "Media still DISCONNECTED after grace; surfacing Reconnecting banner")
        }
    }

    fun initialize(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext

        audio = CallAudioManager(appContext)
        networkMonitor = NetworkMonitor(appContext)
        mediaButtons = MediaButtonHandle(appContext)
        accountStore = AccountStore(appContext)
        WebRtcEngine.initialize(appContext)

        networkMonitor.start { snapshot -> onNetworkChanged(snapshot) }

        // Plumb route/picker state from the audio manager into the call snapshot.
        scope.launch {
            audio.currentRoute.collect { route ->
                _callState.value = _callState.value?.copy(
                    audioRoute = route,
                    isSpeakerOn = route is AudioRoute.Speaker
                )
            }
        }
        scope.launch {
            audio.availableRoutes.collect { routes ->
                _callState.value = _callState.value?.copy(availableRoutes = routes)
            }
        }
    }

    // region Registration ----------------------------------------------------

    /**
     * "Registers" with FreeSWITCH by opening the Verto WebSocket and logging in.
     * [domain] is the FreeSWITCH host; it may include an explicit `:port` or a
     * full `ws(s)://host:port` URL, otherwise the default verto port for the
     * chosen [transport] is used.
     */
    fun register(
        username: String,
        password: String,
        domain: String,
        transport: VertoTransport = VertoTransport.WSS
    ) {
        val cleanUser = username.trim()
        val cleanDomain = domain.trim()
        val wsUrl = buildWsUrl(cleanDomain, transport)
        val reg = PendingRegistration(cleanUser, password, cleanDomain, transport, wsUrl)
        pendingRegistration = reg
        // Persist so the user stays signed in across app restarts / reboots.
        accountStore.save(SavedAccount(cleanUser, password, cleanDomain, transport))
        // Explicit user action: always (re)connect, replacing any prior session.
        applyRegistration(reg, force = true)
    }

    /**
     * Re-opens the session from the saved account, if any. Called once at app
     * launch so a previously signed-in user is logged straight back in without
     * re-entering credentials.
     */
    fun restoreSession() {
        if (pendingRegistration != null) return
        val saved = accountStore.load() ?: return
        Log.i(TAG, "Restoring saved Verto session for ${saved.username}")
        register(saved.username, saved.password, saved.domain, saved.transport)
    }

    /**
     * Opens the Verto socket and logs in. Idempotent: if we're already logged in
     * and [force] is false, this is a no-op — so spurious "network came online"
     * blips (doze/validation/screen-on) don't tear down a healthy session and
     * bounce the UI back to "Connecting". [force] is used for genuine reconnects
     * (explicit register, network handoff to a new IP, manual reconnect).
     */
    private fun applyRegistration(reg: PendingRegistration, force: Boolean = false) {
        if (!force && verto.isLoggedIn) {
            Log.i(TAG, "Already logged in; skipping redundant reconnect")
            return
        }
        _registrationMessage.value = ""
        _registrationState.value = RegistrationState.Progress
        val loginId = if (reg.username.contains("@")) reg.username else "${reg.username}@${reg.domain}"
        Log.i(TAG, "Verto login $loginId via ${reg.wsUrl}")
        verto.connect(reg.wsUrl, loginId, reg.password, vertoListener)
    }

    private fun buildWsUrl(domain: String, transport: VertoTransport): String {
        if (domain.startsWith("ws://") || domain.startsWith("wss://")) return domain
        val hasPort = domain.substringAfterLast(':', "").toIntOrNull() != null
        val hostPort = if (hasPort) domain else "$domain:${transport.defaultPort}"
        return "${transport.scheme}://$hostPort"
    }

    fun unregister() {
        pendingRegistration = null
        accountStore.clear()
        verto.disconnect("logout")
        _registrationState.value = RegistrationState.Cleared
    }

    val isRegistered: Boolean
        get() = _registrationState.value == RegistrationState.Ok

    /** The currently saved account (for prefilling the account form), if any. */
    fun savedAccount(): SavedAccount? = accountStore.load()

    // endregion

    // region Call control -----------------------------------------------------

    fun startCall(rawNumber: String) {
        val number = rawNumber.trim()
        if (number.isEmpty()) return
        _callError.value = null

        if (session != null) {
            _callError.value = "Already in a call"
            return
        }
        if (!verto.isLoggedIn) {
            _callError.value = "Register an account first"
            return
        }

        val reg = pendingRegistration
        val destination = normalizeDestination(number)
        val id = UUID.randomUUID().toString()
        callId = id
        callIsOutgoing = true
        remoteAnswerApplied = false
        muted = false
        callDisplayName = destination
        callRemoteUri = if (reg != null) "sip:$destination@${reg.domain}" else destination

        val newSession = WebRtcEngine.createSession(id, webRtcEvents)
        session = newSession

        // Publish the in-call screen immediately (optimistic, like the reference).
        changeState(CallState.OutgoingInit)

        scope.launch {
            val offer = runCatching { newSession.createOffer() }.getOrElse {
                Log.w(TAG, "createOffer failed", it)
                _callError.value = "Couldn't start the call (media setup failed)"
                cleanupCall()
                changeState(CallState.Released)
                return@launch
            }
            val callerId = reg?.username ?: "anonymous"
            verto.invite(id, destination, callerId, offer)
            changeState(CallState.OutgoingProgress)
        }
    }

    fun acceptCall() {
        val id = callId ?: return
        val offer = pendingIncomingOffer ?: return
        val s = session ?: return
        scope.launch {
            val answer = runCatching { s.createAnswer(offer) }.getOrElse {
                Log.w(TAG, "createAnswer failed", it)
                terminateCall()
                return@launch
            }
            verto.answer(id, answer)
            pendingIncomingOffer = null
            changeState(CallState.Connected)
        }
    }

    fun terminateCall() {
        val id = callId
        if (id != null) verto.bye(id)
        cleanupCall()
        changeState(CallState.Released)
    }

    fun clearCallError() {
        _callError.value = null
    }

    fun toggleMute(): Boolean {
        muted = !muted
        session?.setMuted(muted)
        _callState.value = _callState.value?.copy(isMuted = muted)
        return muted
    }

    /** Cycles between earpiece and speaker; for richer routing use [setAudioRoute]. */
    fun toggleSpeaker(): Boolean {
        val next = if (audio.currentRoute.value is AudioRoute.Speaker) {
            AudioRoute.Earpiece
        } else {
            AudioRoute.Speaker
        }
        audio.setRoute(next)
        return next is AudioRoute.Speaker
    }

    fun setAudioRoute(route: AudioRoute) {
        audio.setRoute(route)
    }

    /** Sends a DTMF digit over the active call (Verto info + RTP) and plays a local tone. */
    fun sendDtmf(digit: Char) {
        callId?.let { verto.sendDtmf(it, digit) }
        session?.sendDtmf(digit)
        playKeypadTone(digit)
    }

    /** Plays a short local DTMF tone for keypad feedback when not in a call. */
    fun playKeypadTone(digit: Char) {
        val tone = toneFor(digit) ?: return
        runCatching {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_DTMF, 70)
            }
            toneGenerator?.startTone(tone, 150)
        }.onFailure { Log.w(TAG, "tone failed", it) }
    }

    /**
     * Manually re-establishes the Verto session (and marks the call as
     * Reconnecting). Exposed to the UI as a "Reconnect" button when the call is
     * stuck in [ConnectionPhase.Lost] / [ConnectionPhase.Reconnecting].
     *
     * Note: a full media (ICE) restart over Verto would require a re-INVITE; for
     * now we re-login so signaling recovers and a fresh call can be placed.
     */
    fun forceReconnect() {
        Log.i(TAG, "Manual reconnect requested")
        phase = ConnectionPhase.Reconnecting
        consecutivePoorSamples = 0
        consecutiveGoodSamples = 0
        degradedByQuality = false
        pendingRegistration?.let { applyRegistration(it) }
        _callState.value = _callState.value?.copy(connectionPhase = phase)
    }

    /** Called from MediaButtonReceiver when the user presses a headset hook. */
    fun onHeadsetHook() {
        when (currentState) {
            CallState.IncomingReceived -> acceptCall()
            CallState.StreamsRunning, CallState.Connected,
            CallState.OutgoingInit, CallState.OutgoingProgress, CallState.OutgoingRinging ->
                terminateCall()
            else -> Unit
        }
    }

    // endregion

    // region Verto signaling callbacks ---------------------------------------

    private val vertoListener = object : VertoClient.Listener {
        override fun onConnecting() {
            _registrationState.value = RegistrationState.Progress
        }

        override fun onLoginSuccess() {
            _registrationMessage.value = ""
            _registrationState.value = RegistrationState.Ok
        }

        override fun onLoginFailed(reason: String) {
            _registrationMessage.value = reason
            _registrationState.value = RegistrationState.Failed
        }

        override fun onDisconnected(reason: String) {
            Log.w(TAG, "Verto disconnected: $reason")
            _registrationState.value = RegistrationState.None
            if (session != null) {
                phase = ConnectionPhase.Lost
                _callState.value = _callState.value?.copy(connectionPhase = phase)
            }
            // Auto-reconnect if the user is still meant to be signed in.
            pendingRegistration?.let { reg ->
                handler.postDelayed({
                    if (pendingRegistration === reg && !verto.isLoggedIn) applyRegistration(reg)
                }, 3000)
            }
        }

        override fun onIncomingCall(
            callId: String,
            callerName: String,
            callerNumber: String,
            sdpOffer: String
        ) {
            // Reject a second concurrent call.
            if (session != null) {
                verto.bye(callId, "USER_BUSY")
                return
            }
            this@SipCoreManager.callId = callId
            pendingIncomingOffer = sdpOffer
            callIsOutgoing = false
            remoteAnswerApplied = false
            muted = false
            callDisplayName = callerName.ifBlank { callerNumber }
            callRemoteUri = callerNumber
            session = WebRtcEngine.createSession(callId, webRtcEvents)
            changeState(CallState.IncomingReceived)
        }

        override fun onMedia(callId: String, sdpAnswer: String) {
            if (callId != this@SipCoreManager.callId) return
            if (!remoteAnswerApplied) {
                applyRemoteAnswer(sdpAnswer)
                changeState(CallState.OutgoingRinging)
            }
        }

        override fun onCallAnswered(callId: String, sdpAnswer: String?) {
            if (callId != this@SipCoreManager.callId) return
            if (sdpAnswer != null && !remoteAnswerApplied) applyRemoteAnswer(sdpAnswer)
            // If the media path already reported CONNECTED during early media,
            // go straight to StreamsRunning so the call doesn't sit on a stale
            // "Connected" (and never shows a banner for an up call).
            changeState(if (mediaConnected) CallState.StreamsRunning else CallState.Connected)
        }

        override fun onBye(callId: String, cause: String) {
            if (callId != this@SipCoreManager.callId) return
            Log.i(TAG, "Remote hung up ($cause)")
            cleanupCall()
            changeState(CallState.Released)
        }
    }

    private fun applyRemoteAnswer(sdp: String) {
        val s = session ?: return
        scope.launch {
            // Only latch remoteAnswerApplied on success — otherwise a rejected
            // early-media SDP would block the real verto.answer that follows.
            runCatching { s.setRemoteAnswer(sdp) }
                .onSuccess { remoteAnswerApplied = true }
                .onFailure { Log.w(TAG, "setRemoteAnswer failed", it) }
        }
    }

    // endregion

    // region WebRTC callbacks -------------------------------------------------

    private val webRtcEvents = object : WebRtcSession.Events {
        override fun onConnectionState(state: PeerConnection.PeerConnectionState) {
            handler.post {
                if (session == null) return@post
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        // Media is up. Clear any Reconnecting banner unconditionally
                        // and cancel a pending disconnect grace. With Verto the
                        // PeerConnection often reaches CONNECTED during early media
                        // (ringing), before verto.answer — so we don't force the
                        // call to StreamsRunning here unless it has already been
                        // answered; otherwise we let onCallAnswered promote it (it
                        // checks mediaConnected).
                        mediaConnected = true
                        handler.removeCallbacks(disconnectGraceRunnable)
                        phase = ConnectionPhase.Healthy
                        if (!currentState.isTerminated &&
                            (currentState == CallState.Connected || currentState == CallState.StreamsRunning)
                        ) {
                            changeState(CallState.StreamsRunning)
                        } else {
                            _callState.value = _callState.value?.copy(connectionPhase = phase)
                        }
                    }
                    PeerConnection.PeerConnectionState.DISCONNECTED -> {
                        // Transient: WebRTC usually recovers on its own. Only show
                        // the banner if it's still down after the grace window.
                        handler.removeCallbacks(disconnectGraceRunnable)
                        handler.postDelayed(disconnectGraceRunnable, DISCONNECT_GRACE_MS)
                    }
                    PeerConnection.PeerConnectionState.FAILED -> {
                        // Hard failure — ICE could not (re)establish a media path.
                        handler.removeCallbacks(disconnectGraceRunnable)
                        phase = ConnectionPhase.Reconnecting
                        _callState.value = _callState.value?.copy(connectionPhase = phase)
                        Log.w(TAG, "PeerConnection FAILED; surfacing Reconnecting banner")
                    }
                    else -> Unit
                }
            }
        }
    }

    // endregion

    // region Call-state transitions & side effects ----------------------------

    private fun changeState(state: CallState) {
        currentState = state
        when (state) {
            CallState.OutgoingInit, CallState.IncomingReceived -> {
                muted = false
                quality = CallQualityStats.EMPTY
                phase = ConnectionPhase.Healthy
                mediaConnected = false
                consecutivePoorSamples = 0
                consecutiveGoodSamples = 0
                degradedByQuality = false
                runCatching { audio.beginCall() }
                    .onFailure { Log.w(TAG, "audio.beginCall failed", it) }
                runCatching { mediaButtons.acquire() }
                    .onFailure { Log.w(TAG, "mediaButtons.acquire failed", it) }
                runCatching { CallForegroundService.start(appContext, callDisplayName.ifBlank { "Call" }) }
                    .onFailure { Log.w(TAG, "CallForegroundService.start failed", it) }
                handler.removeCallbacks(statsRunnable)
                handler.postDelayed(statsRunnable, 1000)
            }
            CallState.Connected, CallState.StreamsRunning -> {
                if (phase == ConnectionPhase.Reconnecting) phase = ConnectionPhase.Healthy
            }
            CallState.Error -> {
                _callError.value = "Call failed"
            }
            else -> Unit
        }

        if (state == CallState.Released) {
            handler.removeCallbacks(statsRunnable)
            handler.removeCallbacks(disconnectGraceRunnable)
            mediaConnected = false
            runCatching { audio.endCall() }.onFailure { Log.w(TAG, "audio.endCall failed", it) }
            runCatching { mediaButtons.release() }.onFailure { Log.w(TAG, "mediaButtons.release failed", it) }
            runCatching { CallForegroundService.stop(appContext) }.onFailure { Log.w(TAG, "FGS stop failed", it) }
            _callState.value = null
        } else {
            _callState.value = snapshot(state)
        }
    }

    private fun snapshot(state: CallState) = CallUiState(
        state = state,
        remoteUri = callRemoteUri,
        displayName = callDisplayName,
        isOutgoing = callIsOutgoing,
        isMuted = muted,
        isSpeakerOn = audio.currentRoute.value is AudioRoute.Speaker,
        audioRoute = audio.currentRoute.value,
        availableRoutes = audio.availableRoutes.value,
        connectionPhase = phase,
        networkType = lastNetwork.type,
        quality = quality
    )

    /** Releases call media without sending signaling (caller handles state). */
    private fun cleanupCall() {
        session?.close()
        session = null
        callId = null
        pendingIncomingOffer = null
        remoteAnswerApplied = false
    }

    private fun onStatsUpdated(stats: CallQualityStats) {
        quality = stats
        val mos = stats.mos

        // Surfaces whether RTP is actually flowing each way. up=0 for several
        // seconds → our mic isn't being sent; down=0 → we're not receiving from
        // FreeSWITCH. Both 0 with ICE CONNECTED → DTLS/SRTP problem.
        Log.i(
            TAG,
            "media: up=${stats.uploadKbps.toInt()}kbps down=${stats.downloadKbps.toInt()}kbps " +
                "loss=${stats.lossRate.toInt()}% rtt=${stats.roundTripMs.toInt()}ms mos=$mos"
        )

        if (mos in 0.01f..MOS_FLOOR) {
            consecutivePoorSamples++
            consecutiveGoodSamples = 0
            if (consecutivePoorSamples >= POOR_SAMPLES_TO_DEGRADE && phase == ConnectionPhase.Healthy) {
                degradedByQuality = true
                phase = ConnectionPhase.Reconnecting
                Log.w(TAG, "Quality degraded (MOS=$mos), surfacing Reconnecting banner")
            }
        } else if (mos >= MOS_HEALTHY) {
            consecutiveGoodSamples++
            consecutivePoorSamples = 0
            if (consecutiveGoodSamples >= GOOD_SAMPLES_TO_RECOVER &&
                degradedByQuality && phase == ConnectionPhase.Reconnecting
            ) {
                degradedByQuality = false
                phase = ConnectionPhase.Healthy
                Log.i(TAG, "Quality recovered (MOS=$mos), clearing banner")
            }
        }

        _callState.value = _callState.value?.copy(quality = quality, connectionPhase = phase)
    }

    // endregion

    // region Network handoff --------------------------------------------------

    private fun onNetworkChanged(snapshot: NetworkSnapshot) {
        val previous = lastNetwork
        lastNetwork = snapshot

        val cameOnline = previous.type == NetworkType.NONE && snapshot.type != NetworkType.NONE
        val wentOffline = snapshot.type == NetworkType.NONE
        val swapped = previous.networkId != snapshot.networkId &&
            previous.type != NetworkType.NONE &&
            snapshot.type != NetworkType.NONE

        when {
            wentOffline -> phase = ConnectionPhase.Lost
            swapped -> {
                // Genuinely a different network (new local IP): the old socket is
                // stale, so force a reconnect even if we still think we're logged in.
                phase = ConnectionPhase.Reconnecting
                handler.postDelayed({ pendingRegistration?.let { applyRegistration(it, force = true) } }, 200)
            }
            cameOnline -> {
                // Regained connectivity. Only (re)connect if we're not already
                // logged in — otherwise a transient NONE->WiFi blip would needlessly
                // drop a healthy session and flip the badge back to "Connecting".
                phase = ConnectionPhase.Healthy
                if (!verto.isLoggedIn) pendingRegistration?.let { applyRegistration(it, force = true) }
            }
        }

        _callState.value = _callState.value?.copy(
            connectionPhase = phase,
            networkType = snapshot.type
        )
    }

    // endregion

    private fun normalizeDestination(input: String): String {
        var n = input
        if (n.startsWith("sip:")) n = n.removePrefix("sip:")
        if (n.contains("@")) n = n.substringBefore("@")
        return n
    }

    private fun toneFor(digit: Char): Int? = when (digit) {
        '0' -> ToneGenerator.TONE_DTMF_0
        '1' -> ToneGenerator.TONE_DTMF_1
        '2' -> ToneGenerator.TONE_DTMF_2
        '3' -> ToneGenerator.TONE_DTMF_3
        '4' -> ToneGenerator.TONE_DTMF_4
        '5' -> ToneGenerator.TONE_DTMF_5
        '6' -> ToneGenerator.TONE_DTMF_6
        '7' -> ToneGenerator.TONE_DTMF_7
        '8' -> ToneGenerator.TONE_DTMF_8
        '9' -> ToneGenerator.TONE_DTMF_9
        '*' -> ToneGenerator.TONE_DTMF_S
        '#' -> ToneGenerator.TONE_DTMF_P
        else -> null
    }
}
