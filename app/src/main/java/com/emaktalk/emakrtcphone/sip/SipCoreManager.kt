package com.emaktalk.emakrtcphone.sip

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.emaktalk.emakrtcphone.audio.AudioRoute
import com.emaktalk.emakrtcphone.audio.CallAudioManager
import com.emaktalk.emakrtcphone.audio.Ringer
import com.emaktalk.emakrtcphone.auth.AuthManager
import com.emaktalk.emakrtcphone.audio.MediaButtonHandle
import com.emaktalk.emakrtcphone.network.NetworkMonitor
import com.emaktalk.emakrtcphone.network.NetworkSnapshot
import com.emaktalk.emakrtcphone.network.NetworkType
import com.emaktalk.emakrtcphone.sipws.SipClient
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

object SipCoreManager {

    private const val TAG = "SipCoreManager"

    private const val FORCE_RELAY_FOR_TEST = false

    private lateinit var appContext: Context
    private lateinit var audio: CallAudioManager
    private lateinit var ringer: Ringer
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var mediaButtons: MediaButtonHandle
    private lateinit var accountStore: AccountStore
    private val sip = SipClient()

    private val scope = CoroutineScope(Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private val _registrationState = MutableStateFlow(RegistrationState.None)
    val registrationState: StateFlow<RegistrationState> = _registrationState.asStateFlow()

    private val _registrationMessage = MutableStateFlow("")
    val registrationMessage: StateFlow<String> = _registrationMessage.asStateFlow()

    private val _callState = MutableStateFlow<CallUiState?>(null)
    val callState: StateFlow<CallUiState?> = _callState.asStateFlow()

    private val _callError = MutableStateFlow<String?>(null)
    val callError: StateFlow<String?> = _callError.asStateFlow()

    private var session: WebRtcSession? = null
    private var callId: String? = null
    private var pendingIncomingOffer: String? = null
    private var callIsOutgoing = false
    private var remoteAnswerApplied = false
    private var callDisplayName = ""
    private var callRemoteUri = ""
    private var currentState = CallState.Idle

    private var turnFallbackTried = false
    private var dialedDestination = ""
    private var dialedCallerId = ""
    private var incomingOfferForRetry: String? = null

    private var heldSession: WebRtcSession? = null
    private var heldCallId: String? = null
    private var heldDisplayName = ""
    private var heldRemoteUri = ""
    private var heldIsOutgoing = false
    private var onHold = false
    private var addingCall = false
    private var transferring = false
    private var isConferenceLeg = false

    private var muted = false
    private var phase: ConnectionPhase = ConnectionPhase.Healthy
    private var quality: CallQualityStats = CallQualityStats.EMPTY
    private var lastNetwork: NetworkSnapshot = NetworkSnapshot.DISCONNECTED

    private var mediaConnected = false

    private var toneGenerator: ToneGenerator? = null

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

    private val statsRunnable = object : Runnable {
        override fun run() {
            val s = session ?: return
            s.pollStats { stats -> handler.post { onStatsUpdated(stats) } }
            handler.postDelayed(this, 1000)
        }
    }

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
        ringer = Ringer(appContext)
        networkMonitor = NetworkMonitor(appContext)
        mediaButtons = MediaButtonHandle(appContext)
        accountStore = AccountStore(appContext)
        WebRtcEngine.initialize(appContext)

        networkMonitor.start { snapshot -> onNetworkChanged(snapshot) }

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

    fun register(
        username: String,
        password: String,
        domain: String,
        transport: VertoTransport = VertoTransport.WSS,
        host: String = domain
    ) {
        val cleanUser = username.trim()
        val cleanDomain = domain.trim()
        val cleanHost = host.trim().ifBlank { cleanDomain }
        val wsUrl = buildWsUrl(cleanHost, transport)
        val reg = PendingRegistration(cleanUser, password, cleanDomain, transport, wsUrl)
        pendingRegistration = reg

        accountStore.save(SavedAccount(cleanUser, password, cleanDomain, transport, cleanHost))

        applyRegistration(reg, force = true)
    }

    fun restoreSession() {
        if (pendingRegistration != null) return
        val saved = accountStore.load() ?: return
        Log.i(TAG, "Restoring saved SIP session for ${saved.username}")
        register(saved.username, saved.password, saved.domain, saved.transport, saved.host)
    }

    private fun applyRegistration(reg: PendingRegistration, force: Boolean = false) {
        if (!force && sip.isLoggedIn) {
            Log.i(TAG, "Already logged in; skipping redundant reconnect")
            return
        }
        _registrationMessage.value = ""
        _registrationState.value = RegistrationState.Progress
        val loginId = if (reg.username.contains("@")) reg.username else "${reg.username}@${reg.domain}"
        Log.i(TAG, "SIP REGISTER $loginId via ${reg.wsUrl}")
        sip.connect(reg.wsUrl, loginId, reg.password, sipListener)
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
        sip.disconnect("logout")
        _registrationState.value = RegistrationState.Cleared
    }

    val isRegistered: Boolean
        get() = _registrationState.value == RegistrationState.Ok

    fun savedAccount(): SavedAccount? = accountStore.load()

    fun startCall(rawNumber: String) {
        val number = rawNumber.trim()
        if (number.isEmpty()) return
        _callError.value = null

        if (callId != null || session != null) {
            _callError.value = "Already in a call"
            return
        }
        if (!sip.isLoggedIn) {
            _callError.value = "Register an account first"
            return
        }
        beginOutgoing(number)
    }

    private fun beginOutgoing(
        rawNumber: String,
        displayName: String? = null,
        conference: Boolean = false
    ) {
        val reg = pendingRegistration
        val destination = normalizeDestination(rawNumber.trim())
        val callerId = reg?.username ?: "anonymous"
        val id = UUID.randomUUID().toString()
        callId = id
        callIsOutgoing = true
        remoteAnswerApplied = false
        muted = false
        isConferenceLeg = conference
        callDisplayName = displayName ?: destination
        callRemoteUri = if (reg != null) "sip:$destination@${reg.domain}" else destination

        dialedDestination = destination
        dialedCallerId = callerId

        changeState(CallState.OutgoingInit)

        scope.launch {

            val firstIceServers = if (FORCE_RELAY_FOR_TEST) {
                fetchTurnServers()
            } else {
                WebRtcEngine.defaultIceServers
            }
            val newSession = WebRtcEngine.createSession(
                id, firstIceServers, webRtcEvents, forceRelay = FORCE_RELAY_FOR_TEST
            )
            session = newSession

            val offer = runCatching { newSession.createOffer() }.getOrElse {
                Log.w(TAG, "createOffer failed", it)
                _callError.value = "Couldn't start the call (media setup failed)"
                cleanupCall()
                changeState(CallState.Released)
                return@launch
            }
            sip.invite(id, destination, callerId, offer)
            changeState(CallState.OutgoingProgress)
        }
    }

    fun acceptCall() {
        val id = callId ?: return
        val offer = pendingIncomingOffer ?: return

        ringer.stop()
        runCatching { audio.beginCall() }.onFailure { Log.w(TAG, "audio.beginCall failed", it) }
        runCatching { CallForegroundService.start(appContext, callDisplayName.ifBlank { "Call" }) }
            .onFailure { Log.w(TAG, "CallForegroundService.start failed", it) }
        scope.launch {

            val firstIceServers = if (FORCE_RELAY_FOR_TEST) {
                fetchTurnServers()
            } else {
                WebRtcEngine.defaultIceServers
            }
            val s = WebRtcEngine.createSession(
                id, firstIceServers, webRtcEvents, forceRelay = FORCE_RELAY_FOR_TEST
            )
            session = s

            handler.removeCallbacks(statsRunnable)
            handler.postDelayed(statsRunnable, 1000)
            val answer = runCatching { s.createAnswer(offer) }.getOrElse {
                Log.w(TAG, "createAnswer failed", it)
                terminateCall()
                return@launch
            }
            sip.answer(id, answer)
            pendingIncomingOffer = null
            changeState(CallState.Connected)
        }
    }

    private suspend fun fetchTurnServers(): List<PeerConnection.IceServer> {
        AuthManager.refreshIfNeeded()
        return WebRtcEngine.fetchTurnIceServers(AuthManager.userId, AuthManager.accessToken)
    }

    private fun retryWithTurn() {
        val failedId = callId ?: return
        val outgoing = callIsOutgoing
        val destination = dialedDestination
        val callerId = dialedCallerId
        val offer = incomingOfferForRetry

        Log.w(TAG, "Direct media path failed; retrying over TURN relay (outgoing=$outgoing)")
        phase = ConnectionPhase.Reconnecting
        _callState.value = _callState.value?.copy(connectionPhase = phase)

        scope.launch {
            val iceServers = fetchTurnServers()
            if (callId != failedId || currentState.isTerminated) return@launch

            session?.close()
            mediaConnected = false
            remoteAnswerApplied = false

            if (outgoing) {
                sip.bye(failedId)
                val retryId = UUID.randomUUID().toString()
                callId = retryId
                val s = WebRtcEngine.createSession(retryId, iceServers, webRtcEvents)
                session = s
                val newOffer = runCatching { s.createOffer() }.getOrElse {
                    Log.w(TAG, "TURN retry createOffer failed", it)
                    terminateCall()
                    return@launch
                }
                sip.invite(retryId, destination, callerId, newOffer)
            } else {
                if (offer == null) {
                    Log.w(TAG, "No stored incoming offer; cannot retry with TURN")
                    return@launch
                }
                val s = WebRtcEngine.createSession(failedId, iceServers, webRtcEvents)
                session = s
                val newAnswer = runCatching { s.createAnswer(offer) }.getOrElse {
                    Log.w(TAG, "TURN retry createAnswer failed", it)
                    return@launch
                }
                sip.answer(failedId, newAnswer)
            }
        }
    }

    fun terminateCall() {
        val id = callId
        if (id != null) sip.bye(id)

        if (heldCallId != null) {
            session?.close()
            promoteHeldLeg()
            return
        }
        cleanupCall()
        changeState(CallState.Released)
    }

    fun clearCallError() {
        _callError.value = null
    }

    fun holdCall() {
        val id = callId ?: return
        if (onHold) return
        onHold = true
        sip.modify(id, "hold")
        session?.setHold(true)
        _callState.value = _callState.value?.copy(isOnHold = true)
    }

    fun resumeCall() {
        val id = callId ?: return
        if (!onHold) return
        onHold = false
        sip.modify(id, "unhold")
        session?.setHold(false)
        session?.setMuted(muted)
        _callState.value = _callState.value?.copy(isOnHold = false)
    }

    fun addCall() {
        val id = callId ?: return
        if (heldCallId != null) return

        sip.modify(id, "hold")
        session?.setHold(true)

        heldSession = session
        heldCallId = id
        heldDisplayName = callDisplayName
        heldRemoteUri = callRemoteUri
        heldIsOutgoing = callIsOutgoing

        session = null
        callId = null
        onHold = false
        addingCall = true
        publishAddingCall()
    }

    fun cancelAddCall() {
        if (!addingCall) return
        addingCall = false
        promoteHeldLeg()
    }

    fun placeSecondCall(rawNumber: String) {
        if (!addingCall) return
        val number = rawNumber.trim()
        if (number.isEmpty()) return
        addingCall = false
        beginOutgoing(number)
    }

    fun swapCalls() {
        val heldId = heldCallId ?: return
        val activeId = callId

        if (activeId != null) {
            sip.modify(activeId, "hold")
            session?.setHold(true)
        }
        sip.modify(heldId, "unhold")
        heldSession?.setHold(false)

        val s = session; val cid = callId; val name = callDisplayName
        val uri = callRemoteUri; val out = callIsOutgoing
        session = heldSession; callId = heldCallId; callDisplayName = heldDisplayName
        callRemoteUri = heldRemoteUri; callIsOutgoing = heldIsOutgoing
        heldSession = s; heldCallId = cid; heldDisplayName = name
        heldRemoteUri = uri; heldIsOutgoing = out

        onHold = false
        session?.setMuted(muted)
        currentState = CallState.StreamsRunning
        _callState.value = snapshot(currentState)
    }

    fun mergeCalls() {
        val activeId = callId ?: return
        val heldId = heldCallId ?: return
        val room = conferenceRoomNumber()
        Log.i(TAG, "Merging legs $heldId + $activeId into conference room $room")

        sip.transfer(heldId, room)
        sip.transfer(activeId, room)

        heldSession?.close()
        heldSession = null
        heldCallId = null
        heldDisplayName = ""
        heldRemoteUri = ""
        session?.close()
        session = null
        callId = null
        onHold = false

        beginOutgoing(room, displayName = "Conference", conference = true)
    }

    private fun conferenceRoomNumber(): String {
        val extDigits = (pendingRegistration?.username ?: "").filter { it.isDigit() }
        val offset = extDigits.takeLast(2).toIntOrNull() ?: 0
        return (3500 + offset % 100).toString()
    }

    fun beginTransfer() {
        if (callId == null) return
        if (transferring || addingCall) return
        transferring = true
        _callState.value = snapshot(CallState.Connected)
    }

    fun cancelTransfer() {
        if (!transferring) return
        transferring = false
        _callState.value = snapshot(currentState)
    }

    fun completeBlindTransfer(rawNumber: String) {
        if (!transferring) return
        val id = callId ?: return
        val destination = normalizeDestination(rawNumber.trim())
        if (destination.isEmpty()) return
        transferring = false
        Log.i(TAG, "Blind-transferring leg $id to $destination")

        sip.transfer(id, destination)

        if (heldCallId != null) {
            session?.close()
            session = null
            callId = null
            onHold = false
            promoteHeldLeg()
            return
        }
        cleanupCall()
        changeState(CallState.Released)
    }

    private fun promoteHeldLeg() {
        val heldId = heldCallId
        if (heldId == null) {
            cleanupCall()
            changeState(CallState.Released)
            return
        }

        session = heldSession
        callId = heldCallId
        callDisplayName = heldDisplayName
        callRemoteUri = heldRemoteUri
        callIsOutgoing = heldIsOutgoing
        heldSession = null
        heldCallId = null
        heldDisplayName = ""
        heldRemoteUri = ""

        addingCall = false
        onHold = false
        sip.modify(heldId, "unhold")
        session?.setHold(false)
        session?.setMuted(muted)
        currentState = CallState.StreamsRunning
        _callState.value = snapshot(currentState)
    }

    private fun publishAddingCall() {
        _callState.value = snapshot(CallState.Connected)
    }

    fun toggleMute(): Boolean {
        muted = !muted
        session?.setMuted(muted)
        _callState.value = _callState.value?.copy(isMuted = muted)
        return muted
    }

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

    fun sendDtmf(digit: Char) {
        callId?.let { sip.sendDtmf(it, digit) }
        session?.sendDtmf(digit)
        playKeypadTone(digit)
    }

    fun playKeypadTone(digit: Char) {
        val tone = toneFor(digit) ?: return
        runCatching {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_DTMF, 70)
            }
            toneGenerator?.startTone(tone, 150)
        }.onFailure { Log.w(TAG, "tone failed", it) }
    }

    fun forceReconnect() {
        Log.i(TAG, "Manual reconnect requested")
        phase = ConnectionPhase.Reconnecting
        consecutivePoorSamples = 0
        consecutiveGoodSamples = 0
        degradedByQuality = false
        pendingRegistration?.let { applyRegistration(it) }
        _callState.value = _callState.value?.copy(connectionPhase = phase)
    }

    fun onHeadsetHook() {
        when (currentState) {
            CallState.IncomingReceived -> acceptCall()
            CallState.StreamsRunning, CallState.Connected,
            CallState.OutgoingInit, CallState.OutgoingProgress, CallState.OutgoingRinging ->
                terminateCall()
            else -> Unit
        }
    }

    private val sipListener = object : SipClient.Listener {
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
            Log.w(TAG, "SIP disconnected: $reason")
            _registrationState.value = RegistrationState.None
            if (session != null) {
                phase = ConnectionPhase.Lost
                _callState.value = _callState.value?.copy(connectionPhase = phase)
            }

            pendingRegistration?.let { reg ->
                handler.postDelayed({
                    if (pendingRegistration === reg && !sip.isLoggedIn) applyRegistration(reg)
                }, 3000)
            }
        }

        override fun onIncomingCall(
            callId: String,
            callerName: String,
            callerNumber: String,
            sdpOffer: String
        ) {
            Log.i(TAG, "Incoming call $callId from ${callerNumber.ifBlank { callerName }}")

            if (this@SipCoreManager.callId != null || session != null) {
                Log.w(TAG, "Busy (active call ${this@SipCoreManager.callId}); rejecting $callId as USER_BUSY")
                sip.bye(callId, "USER_BUSY")
                return
            }
            this@SipCoreManager.callId = callId
            pendingIncomingOffer = sdpOffer

            incomingOfferForRetry = sdpOffer
            callIsOutgoing = false
            remoteAnswerApplied = false
            muted = false
            callDisplayName = callerName.ifBlank { callerNumber }
            callRemoteUri = callerNumber

            changeState(CallState.IncomingReceived)
        }

        override fun onRinging(callId: String) {

            if (callId != this@SipCoreManager.callId) return
            if (currentState == CallState.OutgoingInit || currentState == CallState.OutgoingProgress) {
                changeState(CallState.OutgoingRinging)
            }
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

            changeState(if (mediaConnected) CallState.StreamsRunning else CallState.Connected)
        }

        override fun onBye(callId: String, cause: String) {

            if (callId == heldCallId) {
                Log.i(TAG, "Held leg remote hung up ($cause)")
                heldSession?.close()
                heldSession = null
                heldCallId = null
                heldDisplayName = ""
                heldRemoteUri = ""
                _callState.value = _callState.value?.copy(heldCallTitle = null, canMerge = false)
                return
            }
            if (callId != this@SipCoreManager.callId) return
            Log.i(TAG, "Remote hung up ($cause)")

            if (heldCallId != null) {
                session?.close()
                promoteHeldLeg()
                return
            }
            cleanupCall()
            changeState(CallState.Released)
        }
    }

    private fun applyRemoteAnswer(sdp: String) {
        val s = session ?: return
        scope.launch {

            runCatching { s.setRemoteAnswer(sdp) }
                .onSuccess { remoteAnswerApplied = true }
                .onFailure { Log.w(TAG, "setRemoteAnswer failed", it) }
        }
    }

    private val webRtcEvents = object : WebRtcSession.Events {
        override fun onConnectionState(callId: String, state: PeerConnection.PeerConnectionState) {
            handler.post {
                if (session == null) return@post

                if (callId != this@SipCoreManager.callId) return@post
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {

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

                        handler.removeCallbacks(disconnectGraceRunnable)
                        handler.postDelayed(disconnectGraceRunnable, DISCONNECT_GRACE_MS)
                    }
                    PeerConnection.PeerConnectionState.FAILED -> {

                        handler.removeCallbacks(disconnectGraceRunnable)
                        if (!turnFallbackTried && !currentState.isTerminated) {
                            turnFallbackTried = true
                            retryWithTurn()
                        } else {
                            phase = ConnectionPhase.Reconnecting
                            _callState.value = _callState.value?.copy(connectionPhase = phase)
                            Log.w(TAG, "PeerConnection FAILED; no further TURN fallback")
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun changeState(state: CallState) {
        currentState = state

        if (state != CallState.IncomingReceived) ringer.stop()
        when (state) {
            CallState.OutgoingInit, CallState.IncomingReceived -> {
                muted = false
                quality = CallQualityStats.EMPTY
                phase = ConnectionPhase.Healthy
                mediaConnected = false
                turnFallbackTried = false
                consecutivePoorSamples = 0
                consecutiveGoodSamples = 0
                degradedByQuality = false
                runCatching { mediaButtons.acquire() }
                    .onFailure { Log.w(TAG, "mediaButtons.acquire failed", it) }
                if (state == CallState.IncomingReceived) {

                    runCatching { ringer.start() }
                        .onFailure { Log.w(TAG, "ringer.start failed", it) }
                    runCatching {
                        CallForegroundService.startIncoming(
                            appContext, callDisplayName.ifBlank { "Incoming call" }
                        )
                    }.onFailure { Log.w(TAG, "CallForegroundService.startIncoming failed", it) }
                } else {
                    runCatching { audio.beginCall() }
                        .onFailure { Log.w(TAG, "audio.beginCall failed", it) }
                    runCatching { CallForegroundService.start(appContext, callDisplayName.ifBlank { "Call" }) }
                        .onFailure { Log.w(TAG, "CallForegroundService.start failed", it) }
                    handler.removeCallbacks(statsRunnable)
                    handler.postDelayed(statsRunnable, 1000)
                }
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
        quality = quality,
        isOnHold = onHold,
        heldCallTitle = heldCallId?.let { heldDisplayName.ifBlank { heldRemoteUri } },
        isAddingCall = addingCall,
        isTransferring = transferring,
        canMerge = heldCallId != null && state.isConnected && !addingCall,
        isConference = isConferenceLeg
    )

    private fun cleanupCall() {
        session?.close()
        session = null
        callId = null
        pendingIncomingOffer = null
        incomingOfferForRetry = null
        remoteAnswerApplied = false

        heldSession?.close()
        heldSession = null
        heldCallId = null
        heldDisplayName = ""
        heldRemoteUri = ""
        onHold = false
        addingCall = false
        transferring = false
        isConferenceLeg = false
    }

    private fun onStatsUpdated(stats: CallQualityStats) {
        quality = stats
        val mos = stats.mos

        Log.i(
            TAG,
            "media: up=${stats.uploadKbps.toInt()}kbps down=${stats.downloadKbps.toInt()}kbps " +
                "loss=${stats.lossRate.toInt()}% rtt=${stats.roundTripMs.toInt()}ms mos=$mos " +
                "path=${stats.mediaPath}"
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

                phase = ConnectionPhase.Reconnecting
                handler.postDelayed({ pendingRegistration?.let { applyRegistration(it, force = true) } }, 200)
            }
            cameOnline -> {

                phase = ConnectionPhase.Healthy
                if (!sip.isLoggedIn) pendingRegistration?.let { applyRegistration(it, force = true) }
            }
        }

        _callState.value = _callState.value?.copy(
            connectionPhase = phase,
            networkType = snapshot.type
        )
    }

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
