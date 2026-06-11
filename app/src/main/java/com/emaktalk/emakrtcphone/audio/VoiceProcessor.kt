package com.emaktalk.emakrtcphone.audio

/**
 * Pluggable audio frame post-processor for advanced noise suppression / echo
 * cancellation. The default implementation is a no-op; production builds drop
 * in a Krisp or RNNoise-backed implementation that actually scrubs frames.
 *
 * **Why this is an interface, not a concrete impl:** WebRTC owns the capture
 * pipeline through its native audio device module and the built-in APM
 * (AEC3/NS/AGC). To intercept frames you either (a) supply a custom
 * `JavaAudioDeviceModule.AudioRecordSamplesReadyCallback` / samples interceptor
 * that mutates the PCM before it is encoded, or (b) integrate an SDK like Krisp
 * that hooks the Android audio path. Both require external work; the interface
 * marks the seam so the rest of the app doesn't change when that work happens.
 *
 * Wire-up path for an RNNoise implementation:
 *  1. Compile RNNoise as `librnnoise.so` and ship in `app/src/main/jniLibs/`.
 *  2. Implement [VoiceProcessor] calling `rnnoise_process_frame()` per 10ms /
 *     480-sample buffer at 48kHz.
 *  3. Feed WebRTC's captured frames through it via a samples-ready callback on
 *     the `JavaAudioDeviceModule` builder, writing the cleaned PCM back.
 *
 * For Krisp: link against `krisp-android.aar`, implement [VoiceProcessor]
 * forwarding to its SDK, and route WebRTC capture through the same callback.
 */
interface VoiceProcessor {
    /** Process a 16-bit mono PCM frame in place at the given sample rate. */
    fun processFrame(pcm: ShortArray, sampleRate: Int)
    /** Free native resources. */
    fun release()
}

/** No-op default. Replace via [VoiceProcessing.install] at app start. */
object PassthroughVoiceProcessor : VoiceProcessor {
    override fun processFrame(pcm: ShortArray, sampleRate: Int) = Unit
    override fun release() = Unit
}

/** App-wide handle so the media layer can find the processor. */
object VoiceProcessing {
    @Volatile var current: VoiceProcessor = PassthroughVoiceProcessor
        private set

    fun install(processor: VoiceProcessor) {
        current.release()
        current = processor
    }
}
