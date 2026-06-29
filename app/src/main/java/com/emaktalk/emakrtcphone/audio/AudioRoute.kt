package com.emaktalk.emakrtcphone.audio

sealed class AudioRoute(val label: String) {
    object Earpiece : AudioRoute("Phone")
    object Speaker : AudioRoute("Speaker")
    object WiredHeadset : AudioRoute("Headset")
    data class Bluetooth(val deviceName: String) : AudioRoute(deviceName)

    val id: String get() = when (this) {
        is Earpiece -> "earpiece"
        is Speaker -> "speaker"
        is WiredHeadset -> "wired"
        is Bluetooth -> "bt:$deviceName"
    }
}
