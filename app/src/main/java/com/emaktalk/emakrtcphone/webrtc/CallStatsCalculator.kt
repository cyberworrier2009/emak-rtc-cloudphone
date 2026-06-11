package com.emaktalk.emakrtcphone.webrtc

import com.emaktalk.emakrtcphone.sip.CallQualityStats
import org.webrtc.RTCStatsReport

/**
 * Folds a WebRTC [RTCStatsReport] into the app's [CallQualityStats], deriving a
 * MOS estimate the way Linphone handed us one for free in the reference project.
 *
 * MOS uses a simplified ITU-T G.107 E-model: compute a transmission rating R
 * from effective latency (RTT + jitter) and packet loss, then map R → MOS.
 * It's an approximation, but good enough to drive the same 0–5 signal-bars UI.
 *
 * Stateful: kept per [WebRtcSession] so byte/packet counters can be diffed
 * between the ~1Hz polls.
 */
class CallStatsCalculator {

    private var prevBytesReceived = 0.0
    private var prevBytesSent = 0.0
    private var prevPacketsLost = 0.0
    private var prevPacketsReceived = 0.0
    private var prevTimestampUs = 0.0

    fun consume(report: RTCStatsReport): CallQualityStats {
        var bytesReceived = 0.0
        var bytesSent = 0.0
        var packetsLost = 0.0
        var packetsReceived = 0.0
        var jitterSec = 0.0
        var rttSec = 0.0
        var timestampUs = 0.0

        for (stats in report.statsMap.values) {
            val m = stats.members
            when (stats.type) {
                "inbound-rtp" -> {
                    if (m["kind"]?.toString() != "audio" && m["mediaType"]?.toString() != "audio") continue
                    bytesReceived += num(m["bytesReceived"])
                    packetsReceived += num(m["packetsReceived"])
                    packetsLost += num(m["packetsLost"])
                    jitterSec = maxOf(jitterSec, num(m["jitter"]))
                    timestampUs = stats.timestampUs
                }
                "outbound-rtp" -> {
                    if (m["kind"]?.toString() != "audio" && m["mediaType"]?.toString() != "audio") continue
                    bytesSent += num(m["bytesSent"])
                }
                "remote-inbound-rtp" -> {
                    rttSec = maxOf(rttSec, num(m["roundTripTime"]))
                }
                "candidate-pair" -> {
                    if (m["state"]?.toString() == "succeeded" || num(m["currentRoundTripTime"]) > 0) {
                        if (rttSec <= 0) rttSec = num(m["currentRoundTripTime"])
                    }
                }
            }
        }

        val dtSec = if (prevTimestampUs > 0 && timestampUs > prevTimestampUs) {
            (timestampUs - prevTimestampUs) / 1_000_000.0
        } else 1.0

        val downKbps = ((bytesReceived - prevBytesReceived) * 8.0 / 1000.0 / dtSec)
            .coerceAtLeast(0.0)
        val upKbps = ((bytesSent - prevBytesSent) * 8.0 / 1000.0 / dtSec)
            .coerceAtLeast(0.0)

        val dLost = (packetsLost - prevPacketsLost).coerceAtLeast(0.0)
        val dRecv = (packetsReceived - prevPacketsReceived).coerceAtLeast(0.0)
        val lossPct = if (dRecv + dLost > 0) (dLost / (dRecv + dLost) * 100.0) else 0.0

        prevBytesReceived = bytesReceived
        prevBytesSent = bytesSent
        prevPacketsLost = packetsLost
        prevPacketsReceived = packetsReceived
        prevTimestampUs = timestampUs

        val jitterMs = jitterSec * 1000.0
        val rttMs = rttSec * 1000.0
        // No inbound data yet → report MOS 0 so the UI shows "Measuring…".
        val mos = if (packetsReceived <= 0.0) 0.0 else estimateMos(rttMs, jitterMs, lossPct)

        return CallQualityStats(
            mos = mos.toFloat(),
            downloadKbps = downKbps.toFloat(),
            uploadKbps = upKbps.toFloat(),
            jitterMs = jitterMs.toFloat(),
            roundTripMs = rttMs.toFloat(),
            lossRate = lossPct.toFloat()
        )
    }

    /** Simplified E-model: latency + loss → R factor → MOS (1.0–5.0). */
    private fun estimateMos(rttMs: Double, jitterMs: Double, lossPct: Double): Double {
        val effectiveLatency = rttMs + (jitterMs * 2) + 10.0
        var r = if (effectiveLatency < 160) {
            93.2 - (effectiveLatency / 40.0)
        } else {
            93.2 - ((effectiveLatency - 120.0) / 10.0)
        }
        // Each 1% of loss costs ~2.5 R points (Bjork/ITU approximation).
        r -= lossPct * 2.5
        if (r < 0) return 1.0
        val mos = 1.0 + (0.035 * r) + (r * (r - 60.0) * (100.0 - r) * 7e-6)
        return mos.coerceIn(1.0, 5.0)
    }

    private fun num(value: Any?): Double = when (value) {
        null -> 0.0
        is Number -> value.toDouble()
        else -> value.toString().toDoubleOrNull() ?: 0.0
    }
}
