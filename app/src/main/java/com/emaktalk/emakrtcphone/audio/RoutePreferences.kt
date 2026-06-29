package com.emaktalk.emakrtcphone.audio

import android.content.Context
import android.content.SharedPreferences

class RoutePreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("audio_route_prefs", Context.MODE_PRIVATE)

    fun remember(key: String, route: AudioRoute) {
        prefs.edit().putString(key, route.id).apply()
    }

    fun recall(key: String, available: List<AudioRoute>): AudioRoute? {
        val id = prefs.getString(key, null) ?: return null
        return available.firstOrNull { it.id == id }
    }

    companion object {
        const val KEY_DEFAULT = "__default__"
        fun keyFor(route: AudioRoute): String = when (route) {
            is AudioRoute.Bluetooth -> "bt:${route.deviceName}"
            else -> KEY_DEFAULT
        }
    }
}
