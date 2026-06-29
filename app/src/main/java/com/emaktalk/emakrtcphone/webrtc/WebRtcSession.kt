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

class WebRtcSession(
    val callId: String,
    private val factory: PeerConnectionFactory,
    iceServers: List<PeerConnection.IceServer>,
    private val events: Events,

    private val forceRelay: Boolean = false
) {

    interface Events {

        fun onConnectionState(callId: String, state: PeerConnection.PeerConnectionState)
    }

    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteAudioTrack: AudioTrack? = null

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
            events.onConnectionState(callId, newState)
        }

        override fun onIceCandidate(candidate: IceCandidate?) {  }
        override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {

            Log.i(TAG, "[$callId] ICE connection -> $state")
        }
        override fun onIceConnectionReceivingChange(p0: Boolean) {}
        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
        override fun onAddStream(p0: MediaStream?) {}
        override fun onRemoveStream(p0: MediaStream?) {}
        override fun onDataChannel(p0: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {

            val track = receiver?.track()
            if (track is AudioTrack) {
                track.setEnabled(true)
                remoteAudioTrack = track
                Log.i(TAG, "[$callId] remote audio track received (${track.id()})")
            }
        }
    }

    init {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

            iceTransportsType = if (forceRelay) {
                PeerConnection.IceTransportsType.RELAY
            } else {
                PeerConnection.IceTransportsType.ALL
            }

            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE

            bundlePolicy = PeerConnection.BundlePolicy.MAXCOMPAT
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }
        peerConnection = factory.createPeerConnection(rtcConfig, pcObserver)
        addLocalAudio()
    }

    private fun addLocalAudio() {

        val source = factory.createAudioSource(MediaConstraints())
        val track = factory.createAudioTrack("audio_$callId", source)
        track.setEnabled(true)
        peerConnection?.addTrack(track, listOf("stream_$callId"))
        audioSource = source
        localAudioTrack = track

        peerConnection?.transceivers?.firstOrNull {
            it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO
        }?.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
    }

    suspend fun createOffer(): String {
        val offer = callSdp { obs -> peerConnection?.createOffer(obs, offerConstraints()) }
        setLocal(offer)
        val full = awaitFullLocalSdp(offer)
        logSdp("local OFFER", full)
        return full
    }

    suspend fun createAnswer(remoteOffer: String): String {
        logSdp("remote OFFER", remoteOffer)
        setRemote(SessionDescription(SessionDescription.Type.OFFER, remoteOffer))
        val answer = callSdp { obs -> peerConnection?.createAnswer(obs, offerConstraints()) }
        setLocal(answer)
        val full = awaitFullLocalSdp(answer)
        logSdp("local ANSWER", full)
        return full
    }

    suspend fun setRemoteAnswer(sdp: String) {
        logSdp("remote ANSWER", sdp)
        setRemote(SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

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

    fun setMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    fun setHold(onHold: Boolean) {
        localAudioTrack?.setEnabled(!onHold)
        remoteAudioTrack?.setEnabled(!onHold)
    }

    fun sendDtmf(digit: Char) {
        val sender = peerConnection?.senders?.firstOrNull { it.track()?.kind() == "audio" } ?: return
        sender.dtmf()?.insertDtmf(digit.toString(), 160, 70)
    }

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

    companion object {
        private const val TAG = "WebRtcSession"

        private const val ICE_GATHERING_TIMEOUT_MS = 5000L
    }
}
