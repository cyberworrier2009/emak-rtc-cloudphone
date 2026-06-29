package com.emaktalk.emakrtcphone.webrtc

import com.emaktalk.emakrtcphone.sip.CallQualityStats
import com.emaktalk.emakrtcphone.sip.MediaPath
import org.webrtc.RTCStatsReport

class CallStatsCalculator {

    private var prevBytesReceived = 0.0
    private var prevBytesSent = 0.0
    private var prevPacketsLost = 0.0
    private var prevPacketsReceived = 0.0
    private var prevTimestampUs = 0.0

    private var lastMediaPath = MediaPath.Unknown

    fun consume(report: RTCStatsReport): CallQualityStats {
        var bytesReceived = 0.0
        var bytesSent = 0.0
        var packetsLost = 0.0
        var packetsReceived = 0.0
        var jitterSec = 0.0
        var rttSec = 0.0
        var timestampUs = 0.0

        val candidateTypeById = HashMap<String, String>()
        val candidatePairs = ArrayList<Pair<String, Map<String, Any>>>()
        var selectedPairId: String? = null

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
                    candidatePairs.add(stats.id to m)
                }
                "transport" -> {
                    m["selectedCandidatePairId"]?.toString()?.let { selectedPairId = it }
                }
                "local-candidate", "remote-candidate" -> {
                    m["candidateType"]?.toString()?.let { candidateTypeById[stats.id] = it }
                }
            }
        }

        lastMediaPath = resolveMediaPath(candidateTypeById, candidatePairs, selectedPairId) ?: lastMediaPath

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

        val mos = if (packetsReceived <= 0.0) 0.0 else estimateMos(rttMs, jitterMs, lossPct)

        return CallQualityStats(
            mos = mos.toFloat(),
            downloadKbps = downKbps.toFloat(),
            uploadKbps = upKbps.toFloat(),
            jitterMs = jitterMs.toFloat(),
            roundTripMs = rttMs.toFloat(),
            lossRate = lossPct.toFloat(),
            mediaPath = lastMediaPath
        )
    }

    private fun resolveMediaPath(
        candidateTypeById: Map<String, String>,
        candidatePairs: List<Pair<String, Map<String, Any>>>,
        selectedPairId: String?
    ): MediaPath? {
        val pair = candidatePairs.firstOrNull { it.first == selectedPairId }
            ?: candidatePairs.firstOrNull {
                it.second["nominated"]?.toString() == "true" && it.second["state"]?.toString() == "succeeded"
            }
            ?: candidatePairs.firstOrNull { it.second["state"]?.toString() == "succeeded" }
            ?: return null

        val localType = candidateTypeById[pair.second["localCandidateId"]?.toString()]
        val remoteType = candidateTypeById[pair.second["remoteCandidateId"]?.toString()]
        return when {
            localType == null && remoteType == null -> null
            localType == "relay" || remoteType == "relay" -> MediaPath.Relay
            else -> MediaPath.Direct
        }
    }

    private fun estimateMos(rttMs: Double, jitterMs: Double, lossPct: Double): Double {
        val effectiveLatency = rttMs + (jitterMs * 2) + 10.0
        var r = if (effectiveLatency < 160) {
            93.2 - (effectiveLatency / 40.0)
        } else {
            93.2 - ((effectiveLatency - 120.0) / 10.0)
        }

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
