package com.emaktalk.emakrtcphone.audio

interface VoiceProcessor {

    fun processFrame(pcm: ShortArray, sampleRate: Int)

    fun release()
}

object PassthroughVoiceProcessor : VoiceProcessor {
    override fun processFrame(pcm: ShortArray, sampleRate: Int) = Unit
    override fun release() = Unit
}

object VoiceProcessing {
    @Volatile var current: VoiceProcessor = PassthroughVoiceProcessor
        private set

    fun install(processor: VoiceProcessor) {
        current.release()
        current = processor
    }
}
