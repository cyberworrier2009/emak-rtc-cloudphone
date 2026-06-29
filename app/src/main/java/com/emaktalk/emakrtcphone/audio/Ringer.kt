package com.emaktalk.emakrtcphone.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

class Ringer(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val vibrator: Vibrator = resolveVibrator(context)

    private var player: MediaPlayer? = null
    private var vibrating = false

    fun start() {
        if (player != null || vibrating) return
        when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> {
                Log.i(TAG, "Ringer mode SILENT; not alerting")
            }
            AudioManager.RINGER_MODE_VIBRATE -> startVibration()
            else -> {
                startRingtone()
                startVibration()
            }
        }
    }

    fun stop() {
        player?.let { p ->
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.release() }
        }
        player = null
        if (vibrating) {
            runCatching { vibrator.cancel() }
            vibrating = false
        }
    }

    private fun startRingtone() {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(context, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        }.onFailure {
            Log.w(TAG, "Could not start ringtone", it)
            player = null
        }
    }

    private fun startVibration() {
        if (!vibrator.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(VIBRATE_PATTERN,  0)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(VIBRATE_PATTERN,  0)
            }
            vibrating = true
        }.onFailure { Log.w(TAG, "Could not start vibration", it) }
    }

    private fun resolveVibrator(context: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    companion object {
        private const val TAG = "Ringer"

        private val VIBRATE_PATTERN = longArrayOf(0, 800, 1000)
    }
}
