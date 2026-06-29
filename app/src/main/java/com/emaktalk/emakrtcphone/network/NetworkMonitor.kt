package com.emaktalk.emakrtcphone.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NetworkType { NONE, WIFI, CELLULAR, ETHERNET, OTHER }

data class NetworkSnapshot(
    val type: NetworkType,
    val networkId: Long,
    val isMetered: Boolean
) {
    companion object {
        val DISCONNECTED = NetworkSnapshot(NetworkType.NONE, -1, false)
    }
}

class NetworkMonitor(private val context: Context) {

    fun interface Listener {
        fun onNetworkChanged(snapshot: NetworkSnapshot)
    }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _state = MutableStateFlow(NetworkSnapshot.DISCONNECTED)
    val state: StateFlow<NetworkSnapshot> = _state.asStateFlow()

    private var listener: Listener? = null
    private var registered = false

    private var current: NetworkSnapshot = NetworkSnapshot.DISCONNECTED

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = cm.getNetworkCapabilities(network)
            publish(snapshotFor(network, caps))
        }

        override fun onLost(network: Network) {
            if (network.networkHandle == current.networkId) {
                publish(NetworkSnapshot.DISCONNECTED)
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (network.networkHandle == current.networkId || current == NetworkSnapshot.DISCONNECTED) {
                publish(snapshotFor(network, capabilities))
            }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (network.networkHandle == current.networkId) {
                listener?.onNetworkChanged(current)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start(listener: Listener) {
        if (registered) return
        this.listener = listener
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
        registered = true

        val active = cm.activeNetwork
        if (active != null) {
            val caps = cm.getNetworkCapabilities(active)
            publish(snapshotFor(active, caps))
        }
    }

    fun stop() {
        if (!registered) return
        runCatching { cm.unregisterNetworkCallback(callback) }
        registered = false
        listener = null
    }

    private fun publish(snapshot: NetworkSnapshot) {
        val changed = snapshot != current
        current = snapshot
        _state.value = snapshot
        if (changed) {
            Log.i(TAG, "Network changed -> $snapshot")
            listener?.onNetworkChanged(snapshot)
        }
    }

    private fun snapshotFor(network: Network, caps: NetworkCapabilities?): NetworkSnapshot {
        val type = when {
            caps == null -> NetworkType.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            else -> NetworkType.OTHER
        }
        val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        return NetworkSnapshot(type, network.networkHandle, metered)
    }

    companion object { private const val TAG = "NetworkMonitor" }
}
