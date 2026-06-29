package com.emaktalk.emakrtcphone.sip

enum class MediaPath {

    Unknown,

    Direct,

    Relay
}

data class CallQualityStats(
    val mos: Float,
    val downloadKbps: Float,
    val uploadKbps: Float,
    val jitterMs: Float,
    val roundTripMs: Float,
    val lossRate: Float,

    val mediaPath: MediaPath = MediaPath.Unknown
) {

    val bars: Int get() = when {
        mos <= 0f -> 0
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
