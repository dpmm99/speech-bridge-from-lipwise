package com.example.speechbridge.engine

/**
 * Placeholder engine so the plumbing can be tested end to end without a model:
 * reports one partial per second of audio and a fixed utterance on [finish].
 */
class FakeSttEngine : SttEngine {
    override val sampleRate = 16_000

    private var listener: SttListener? = null
    private var samples = 0L
    private var lastSecond = 0L
    private var announced = false

    override fun start(config: SttConfig, listener: SttListener) {
        this.listener = listener
        samples = 0L
        lastSecond = 0L
        announced = false
    }

    override fun acceptAudio(pcm: ShortArray, length: Int) {
        val l = listener ?: return
        if (!announced) {
            announced = true
            l.onSpeechStart()
        }
        samples += length
        val second = samples / sampleRate
        if (second > lastSecond) {
            lastSecond = second
            l.onPartial("(fake engine heard ${second}s)")
        }
    }

    override suspend fun finish() {
        val l = listener ?: return
        if (samples > 0) l.onUtteranceFinal("hello from the fake engine")
    }

    override fun cancel() {
        listener = null
    }
}
