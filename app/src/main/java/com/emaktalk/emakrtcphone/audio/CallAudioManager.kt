package com.emaktalk.emakrtcphone.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns Android's audio plumbing for an active call: communication audio mode,
 * audio focus, wired-headset detection, and the Bluetooth HFP SCO lifecycle.
 *
 * Unlike the reference project (where Linphone's `Core.pickAudioDevice` chose
 * the device), WebRTC's audio device module simply follows the platform
 * routing. So this class only manipulates [AudioManager] — speakerphone on/off
 * and Bluetooth SCO — and WebRTC plays/records through whatever the OS routes.
 * The platform AEC/NS are requested directly on the WebRTC audio module
 * (see [com.emaktalk.emakrtcphone.webrtc.WebRtcEngine]).
 */
class CallAudioManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val routePrefs = RoutePreferences(context)

    // Stored so we can restore the system audio mode after the call ends.
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private var savedSpeakerphoneOn = false
    private var focusRequest: AudioFocusRequest? = null

    private val _availableRoutes = MutableStateFlow<List<AudioRoute>>(listOf(AudioRoute.Earpiece, AudioRoute.Speaker))
    val availableRoutes: StateFlow<List<AudioRoute>> = _availableRoutes.asStateFlow()

    private val _currentRoute = MutableStateFlow<AudioRoute>(AudioRoute.Earpiece)
    val currentRoute: StateFlow<AudioRoute> = _currentRoute.asStateFlow()

    // Bluetooth HFP profile state -------------------------------------------
    private var bluetoothHeadset: BluetoothHeadset? = null
    private var connectedBtDevice: BluetoothDevice? = null
    private var connectedA2dpDevice: BluetoothDevice? = null
    private var connectedLeAudioDevice: BluetoothDevice? = null
    private var scoActive = false

    private val bluetoothScoReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            scoActive = state == AudioManager.SCO_AUDIO_STATE_CONNECTED
            Log.i(TAG, "SCO state -> $state (active=$scoActive)")
            refreshAvailableRoutes()
        }
    }

    private val headsetProfileListener = object : BluetoothProfile.ServiceListener {
        @SuppressLint("MissingPermission")
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            when (profile) {
                BluetoothProfile.HEADSET -> {
                    bluetoothHeadset = proxy as BluetoothHeadset
                    connectedBtDevice = runCatching { proxy.connectedDevices.firstOrNull() }.getOrNull()
                }
                BluetoothProfile.A2DP -> {
                    connectedA2dpDevice = runCatching { proxy.connectedDevices.firstOrNull() }.getOrNull()
                }
                LE_AUDIO_PROFILE -> {
                    connectedLeAudioDevice = runCatching { proxy.connectedDevices.firstOrNull() }.getOrNull()
                }
            }
            refreshAvailableRoutes()
        }
        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.HEADSET -> { bluetoothHeadset = null; connectedBtDevice = null }
                BluetoothProfile.A2DP -> { connectedA2dpDevice = null }
                LE_AUDIO_PROFILE -> { connectedLeAudioDevice = null }
            }
            refreshAvailableRoutes()
        }
    }

    private val bluetoothConnectionReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, BluetoothHeadset.STATE_DISCONNECTED)
            val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            when (state) {
                BluetoothHeadset.STATE_CONNECTED -> connectedBtDevice = device
                BluetoothHeadset.STATE_DISCONNECTED -> if (device == connectedBtDevice) connectedBtDevice = null
            }
            refreshAvailableRoutes()
        }
    }

    // Wired headset / general device hot-plug --------------------------------
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshAvailableRoutes()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshAvailableRoutes()
    }

    /**
     * Start the in-call audio session: switch to communication mode, grab audio
     * focus, and register hot-plug listeners.
     */
    @SuppressLint("MissingPermission")
    fun beginCall() {
        savedAudioMode = audioManager.mode
        savedSpeakerphoneOn = audioManager.isSpeakerphoneOn

        // VoIP profile: enables the platform's AEC/NS path, routes through the
        // earpiece by default, and ducks other media.
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        requestAudioFocus()

        registerDeviceListeners()
        connectBluetoothProfile()
        refreshAvailableRoutes()
        // Default to earpiece at the start of every call.
        applyRoute(_currentRoute.value)
    }

    /** Tear down the audio session and restore prior system state. */
    fun endCall() {
        stopScoIfActive()
        unregisterDeviceListeners()
        disconnectBluetoothProfile()

        abandonAudioFocus()

        // Restore — don't yank the mode out from under any other VoIP app.
        audioManager.mode = savedAudioMode
        audioManager.isSpeakerphoneOn = savedSpeakerphoneOn

        _currentRoute.value = AudioRoute.Earpiece
    }

    /** Pick an output device; WebRTC's ADM will follow the platform routing. */
    fun setRoute(route: AudioRoute) {
        _currentRoute.value = route
        applyRoute(route)
        routePrefs.remember(RoutePreferences.keyFor(route), route)
    }

    @SuppressLint("MissingPermission")
    private fun applyRoute(route: AudioRoute) {
        when (route) {
            is AudioRoute.Speaker -> {
                stopScoIfActive()
                audioManager.isSpeakerphoneOn = true
            }
            is AudioRoute.Earpiece -> {
                stopScoIfActive()
                audioManager.isSpeakerphoneOn = false
            }
            is AudioRoute.WiredHeadset -> {
                stopScoIfActive()
                audioManager.isSpeakerphoneOn = false
            }
            is AudioRoute.Bluetooth -> {
                audioManager.isSpeakerphoneOn = false
                startScoIfNeeded()
            }
        }
    }

    // region Bluetooth lifecycle

    @SuppressLint("MissingPermission")
    private fun connectBluetoothProfile() {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = btManager?.adapter
            ?: BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) return
        runCatching {
            adapter.getProfileProxy(context, headsetProfileListener, BluetoothProfile.HEADSET)
        }
        runCatching {
            adapter.getProfileProxy(context, headsetProfileListener, BluetoothProfile.A2DP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                adapter.getProfileProxy(context, headsetProfileListener, LE_AUDIO_PROFILE)
            }
        }
        context.registerReceiver(
            bluetoothConnectionReceiver,
            IntentFilter(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        )
        context.registerReceiver(
            bluetoothScoReceiver,
            IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        )
    }

    private fun disconnectBluetoothProfile() {
        runCatching { context.unregisterReceiver(bluetoothScoReceiver) }
        runCatching { context.unregisterReceiver(bluetoothConnectionReceiver) }
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        bluetoothHeadset?.let { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        bluetoothHeadset = null
        connectedBtDevice = null
    }

    private fun startScoIfNeeded() {
        if (scoActive) return
        @Suppress("DEPRECATION") audioManager.isBluetoothScoOn = true
        @Suppress("DEPRECATION") audioManager.startBluetoothSco()
    }

    private fun stopScoIfActive() {
        if (!scoActive && !audioManager.isBluetoothScoOn) return
        @Suppress("DEPRECATION") audioManager.stopBluetoothSco()
        @Suppress("DEPRECATION") audioManager.isBluetoothScoOn = false
        scoActive = false
    }

    // endregion

    private fun registerDeviceListeners() {
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
    }

    private fun unregisterDeviceListeners() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }

    @SuppressLint("MissingPermission")
    private fun refreshAvailableRoutes() {
        val routes = mutableListOf<AudioRoute>()
        routes += AudioRoute.Earpiece
        routes += AudioRoute.Speaker

        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val hasWired = outputs.any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && it.type == AudioDeviceInfo.TYPE_USB_HEADSET)
        }
        if (hasWired) routes += AudioRoute.WiredHeadset

        val btDevice = connectedBtDevice ?: connectedA2dpDevice ?: connectedLeAudioDevice
        val btName = runCatching { btDevice?.name }.getOrNull()
        if (btDevice != null) routes += AudioRoute.Bluetooth(btName ?: "Bluetooth")

        val prior = _availableRoutes.value
        _availableRoutes.value = routes

        if (routes != prior) {
            val newlyAvailable = routes - prior.toSet()
            val recall = newlyAvailable
                .firstNotNullOfOrNull { routePrefs.recall(RoutePreferences.keyFor(it), routes) }
            if (recall != null && recall != _currentRoute.value) {
                Log.i(TAG, "Recalling preferred route for new device: $recall")
                _currentRoute.value = recall
                applyRoute(recall)
                return
            }
        }

        // If the current route disappeared (e.g. headset unplugged), fall back.
        if (_currentRoute.value !in routes) {
            val fallback = if (hasWired) AudioRoute.WiredHeadset else AudioRoute.Earpiece
            setRoute(fallback)
        }
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { /* handled implicitly by communication mode */ }
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    companion object {
        private const val TAG = "CallAudioManager"
        /**
         * BluetoothProfile.LE_AUDIO. Resolved at runtime so we can compile
         * against API 24; available on Android 13+, getProfileProxy() returns
         * false on older devices.
         */
        private const val LE_AUDIO_PROFILE = 22
    }
}
