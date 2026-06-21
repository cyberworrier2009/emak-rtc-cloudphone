package com.emaktalk.emakrtcphone.sip

/**
 * Snapshot of the active call's media quality, refreshed by polling the WebRTC
 * [org.webrtc.PeerConnection]'s `getStats()` (~1Hz, see [com.emaktalk.emakrtcphone.webrtc.WebRtcSession]).
 *
 * Linphone surfaced a ready-made MOS estimate; WebRTC does not, so we derive one
 * from packet loss + jitter + round-trip time via a simplified ITU-T G.107
 * E-model in [com.emaktalk.emakrtcphone.webrtc.CallStatsCalculator]. The scale is
 * the same: 0–5, where <2.0 means audio is likely garbled and 3.5+ is "sounds fine".
 */
/** How the call's media is actually flowing, from the selected ICE candidate pair. */
enum class MediaPath {
    /** Not yet known (no nominated candidate pair reported). */
    Unknown,

    /** Direct/STUN path — host or server-reflexive candidate (the simple case). */
    Direct,

    /** Relayed through a TURN server (the fallback when direct couldn't connect). */
    Relay
}

data class CallQualityStats(
    val mos: Float,
    val downloadKbps: Float,
    val uploadKbps: Float,
    val jitterMs: Float,
    val roundTripMs: Float,
    val lossRate: Float,
    /** Whether media is going direct or via TURN relay (selected candidate pair). */
    val mediaPath: MediaPath = MediaPath.Unknown
) {
    /** Five buckets so we can drive a signal-bars indicator without flicker. */
    val bars: Int get() = when {
        mos <= 0f -> 0           // No data yet.
        mos < 1.5f -> 1
        mos < 2.5f -> 2
        mos < 3.5f -> 3
        mos < 4.2f -> 4
        else -> 5
    }

    companion object {
        val EMPTY = CallQualityStats(0f, 0f, 0f, 0f, 0f, 0f, MediaPath.Unknown)
    }
}
