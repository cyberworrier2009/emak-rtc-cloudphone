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
data class CallQualityStats(
    val mos: Float,
    val downloadKbps: Float,
    val uploadKbps: Float,
    val jitterMs: Float,
    val roundTripMs: Float,
    val lossRate: Float
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
        val EMPTY = CallQualityStats(0f, 0f, 0f, 0f, 0f, 0f)
    }
}
