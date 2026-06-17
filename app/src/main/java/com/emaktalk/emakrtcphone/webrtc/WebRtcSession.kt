package com.emaktalk.emakrtcphone.webrtc

import android.util.Log
import com.emaktalk.emakrtcphone.sip.CallQualityStats
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wraps a single call's WebRTC [PeerConnection]: the local mic track, the
 * SDP offer/answer dance, ICE gathering, DTMF and live stats.
 *
 * Verto (like classic SIP) expects a *complete* SDP — it does not trickle ICE —
 * so [createOffer] / [createAnswer] deliberately block on ICE gathering before
 * returning the SDP that [com.emaktalk.emakrtcphone.verto.VertoClient] should send.
 */
class WebRtcSession(
    val callId: String,
    private val factory: PeerConnectionFactory,
    iceServers: List<PeerConnection.IceServer>,
    private val events: Events
) {

    /** Lifecycle callbacks surfaced to [com.emaktalk.emakrtcphone.sip.SipCoreManager]. */
    interface Events {
        /** PeerConnection transport state changed (CONNECTED, FAILED, ...). */
        fun onConnectionState(state: PeerConnection.PeerConnectionState)
    }

    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private val statsCalculator = CallStatsCalculator()
    private var iceGatheringComplete = CompletableDeferred<Unit>()

    private val pcObserver = object : PeerConnection.Observer {
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
            Log.d(TAG, "[$callId] ICE gathering -> $newState")
            if (newState == PeerConnection.IceGatheringState.COMPLETE &&
                !iceGatheringComplete.isCompleted
            ) {
                iceGatheringComplete.complete(Unit)
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            Log.i(TAG, "[$callId] connection -> $newState")
            events.onConnectionState(newState)
        }

        override fun onIceCandidate(candidate: IceCandidate?) { /* non-trickle: bundled into SDP */ }
        override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            // Useful when diagnosing a stuck/Reconnecting call: this tells you
            // exactly where ICE dies (CHECKING -> FAILED means no candidate pair
            // reached FreeSWITCH; CONNECTED -> DISCONNECTED means a transient drop).
            Log.i(TAG, "[$callId] ICE connection -> $state")
        }
        override fun onIceConnectionReceivingChange(p0: Boolean) {}
        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
        override fun onAddStream(p0: MediaStream?) {}
        override fun onRemoveStream(p0: MediaStream?) {}
        override fun onDataChannel(p0: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            // Remote audio from FreeSWITCH. Native Android WebRTC renders it
            // through the audio device module automatically; we just make sure
            // the track is enabled and log it so we can confirm downstream media.
            val track = receiver?.track()
            if (track is AudioTrack) {
                track.setEnabled(true)
                Log.i(TAG, "[$callId] remote audio track received (${track.id()})")
            }
        }
    }

    init {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // ALL (not RELAY): gather host + server-reflexive (STUN) + relay (TURN)
            // candidates and let ICE pick by priority. Direct/STUN paths win when
            // they work; the TURN relay is only used when no direct path can be
            // established — i.e. TURN is the fallback, not the default route.
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            // Gather all candidates up front; we send a full SDP (no trickle).
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            // MAX_COMPAT, not MAX_BUNDLE: mod_verto's answer SDP does not echo an
            // "a=group:BUNDLE" line, and under MAX_BUNDLE WebRTC then rejects the
            // whole answer with "cannot remove m= section ... from already-
            // established BUNDLE group" — so the remote description never applies
            // and there is no media at all. MAX_COMPAT tolerates a bundle-less
            // answer (fine for a single audio m-line).
            bundlePolicy = PeerConnection.BundlePolicy.MAXCOMPAT
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }
        peerConnection = factory.createPeerConnection(rtcConfig, pcObserver)
        addLocalAudio()
    }

    private fun addLocalAudio() {
        // Empty constraints: let WebRTC's APM use its defaults and the hardware
        // AEC/NS configured on the JavaAudioDeviceModule. Passing goog* keys as
        // mandatory can be rejected on some builds and silently kill capture.
        val source = factory.createAudioSource(MediaConstraints())
        val track = factory.createAudioTrack("audio_$callId", source)
        track.setEnabled(true)
        peerConnection?.addTrack(track, listOf("stream_$callId"))
        audioSource = source
        localAudioTrack = track

        // Force the audio transceiver to send AND receive. addTrack defaults to
        // SEND_RECV under Unified Plan, but being explicit guards against a
        // send-only m-line (a classic "I can't hear them" cause).
        peerConnection?.transceivers?.firstOrNull {
            it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO
        }?.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
    }

    // region SDP --------------------------------------------------------------

    /** Builds our offer, sets it locally, and returns the ICE-complete SDP. */
    suspend fun createOffer(): String {
        val offer = callSdp { obs -> peerConnection?.createOffer(obs, offerConstraints()) }
        setLocal(offer)
        val full = awaitFullLocalSdp(offer)
        logSdp("local OFFER", full)
        return full
    }

    /**
     * Applies the remote offer, builds our answer, and returns the ICE-complete
     * answer SDP. Used for incoming calls.
     */
    suspend fun createAnswer(remoteOffer: String): String {
        logSdp("remote OFFER", remoteOffer)
        setRemote(SessionDescription(SessionDescription.Type.OFFER, remoteOffer))
        val answer = callSdp { obs -> peerConnection?.createAnswer(obs, offerConstraints()) }
        setLocal(answer)
        val full = awaitFullLocalSdp(answer)
        logSdp("local ANSWER", full)
        return full
    }

    /** Applies a remote answer to our outgoing offer (verto.media / verto.answer). */
    suspend fun setRemoteAnswer(sdp: String) {
        logSdp("remote ANSWER", sdp)
        setRemote(SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    /** Logs the audio m-line/direction/codecs and candidate count for diagnosis. */
    private fun logSdp(label: String, sdp: String) {
        val lines = sdp.lines()
        val direction = lines.firstOrNull {
            it.startsWith("a=sendrecv") || it.startsWith("a=sendonly") ||
                it.startsWith("a=recvonly") || it.startsWith("a=inactive")
        }?.removePrefix("a=") ?: "?"
        val candidates = lines.count { it.startsWith("a=candidate") }
        val audio = lines.firstOrNull { it.startsWith("m=audio") } ?: "no m=audio!"
        Log.i(TAG, "[$callId] $label: $audio | dir=$direction | candidates=$candidates")
    }

    private suspend fun awaitFullLocalSdp(fallback: SessionDescription): String {
        // Block (briefly) until ICE gathering finishes so the SDP we hand to
        // Verto carries every candidate. FreeSWITCH won't trickle for us.
        withTimeoutOrNull(ICE_GATHERING_TIMEOUT_MS) { iceGatheringComplete.await() }
        return peerConnection?.localDescription?.description ?: fallback.description
    }

    private fun offerConstraints() = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
    }

    private suspend fun callSdp(block: (SdpObserver) -> Unit): SessionDescription =
        suspendCancellableCoroutine { cont ->
            block(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) { cont.resume(desc) }
                override fun onCreateFailure(error: String?) {
                    cont.resumeWithException(IllegalStateException("createSdp failed: $error"))
                }
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            })
        }

    private suspend fun setLocal(desc: SessionDescription) = setDesc(desc, local = true)
    private suspend fun setRemote(desc: SessionDescription) = setDesc(desc, local = false)

    private suspend fun setDesc(desc: SessionDescription, local: Boolean) =
        suspendCancellableCoroutine<Unit> { cont ->
            val obs = object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
                override fun onSetSuccess() { cont.resume(Unit) }
                override fun onSetFailure(error: String?) {
                    cont.resumeWithException(IllegalStateException("setDesc failed: $error"))
                }
            }
            if (local) peerConnection?.setLocalDescription(obs, desc)
            else peerConnection?.setRemoteDescription(obs, desc)
        }

    // endregion

    // region Controls ---------------------------------------------------------

    /** Mutes/unmutes the local mic. Returns the new muted state. */
    fun setMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    /** Sends DTMF in-band over RTP (RFC 4733) as a backup to Verto's verto.info. */
    fun sendDtmf(digit: Char) {
        val sender = peerConnection?.senders?.firstOrNull { it.track()?.kind() == "audio" } ?: return
        sender.dtmf()?.insertDtmf(digit.toString(), 160, 70)
    }

    /** Polls live media stats and folds them into a [CallQualityStats] via the E-model. */
    fun pollStats(onResult: (CallQualityStats) -> Unit) {
        val pc = peerConnection ?: return
        pc.getStats { report -> onResult(statsCalculator.consume(report)) }
    }

    fun close() {
        runCatching { peerConnection?.dispose() }
        runCatching { localAudioTrack?.dispose() }
        runCatching { audioSource?.dispose() }
        peerConnection = null
        localAudioTrack = null
        audioSource = null
        if (!iceGatheringComplete.isCompleted) iceGatheringComplete.cancel()
    }

    // endregion

    companion object {
        private const val TAG = "WebRtcSession"
        // Verto doesn't trickle, so the SDP we send must already carry every
        // candidate. Give gathering enough headroom (srflx via STUN can take a
        // beat) before we fall back to whatever we've got.
        private const val ICE_GATHERING_TIMEOUT_MS = 5000L
    }
}
