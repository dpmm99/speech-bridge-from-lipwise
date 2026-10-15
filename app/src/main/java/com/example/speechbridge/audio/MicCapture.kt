package com.example.speechbridge.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.locks.ReentrantLock
import kotlin.collections.ArrayDeque
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Records 16-bit mono PCM on a background thread and hands ~100 ms chunks to the consumer
 * attached with [attach].
 *
 * Capture and delivery are deliberately separate. The microphone is opened as soon as a
 * recognition session is requested, before the fields and the model are sorted out, and every
 * chunk captured in the meantime is held in [pending] and delivered - in order - the moment a
 * consumer attaches. Recording then some throwing work away before the consumer exists is what
 * used to cut the first words off a sentence.
 *
 * AudioRecord itself buffers unread audio up to the size its constructor asked for (see the
 * Android reference: that size "determines how long an AudioRecord can record before
 * over-running data that has not been read yet"), so the read loop below stays ahead of it as
 * long as it starts promptly - which [start] does.
 */
class MicCapture(private val sampleRate: Int) {

    private val lock = ReentrantLock()
    private val pending = ArrayDeque<ShortArray>()

    private var consumer: ((ShortArray) -> Unit)? = null
    private var record: AudioRecord? = null

    @Volatile private var running = false
    @Volatile private var finished = false

    @SuppressLint("MissingPermission") // checked by the caller
    fun start(): Boolean {
        val min = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (min <= 0) return false
        // Room for about two seconds of unread audio at the hardware level, so a slow moment
        // in the read loop cannot drop anything.
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, sampleRate * 2 * 2),
        )
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        this.record = record
        running = true
        finished = false
        record.startRecording()
        startCaptureThread(record)
        startDeliveryThread()
        return true
    }

    /**
     * Hands buffered chunks to [onChunk] oldest first, then continues live. Safe to call at any
     * point after [start]; nothing captured before the call is lost.
     */
    fun attach(onChunk: (ShortArray) -> Unit) {
        lock.withLock { consumer = onChunk }
    }

    /** Stops capture. With [join] = true, waits until the last chunk has been delivered. */
    fun stop(join: Boolean) {
        running = false
        if (join) while (!finished) java.lang.Thread.sleep(10)
    }

    private fun startCaptureThread(record: AudioRecord) {
        thread {
            val buf = ShortArray(sampleRate / 10)
            try {
                while (running) {
                    val n = record.read(buf, 0, buf.size)
                    if (n <= 0) break
                    val chunk = ShortArray(n)
                    for (i in 0 until n) chunk[i] = buf[i]
                    lock.withLock {
                        // Bounded so a consumer that never attaches cannot exhaust memory.
                        if (pending.size < MAX_PENDING_CHUNKS) pending.addLast(chunk)
                    }
                }
            } finally {
                try {
                    record.stop()
                } catch (e: Throwable) {
                }
                record.release()
                running = false
            }
        }
    }

    private fun startDeliveryThread() {
        thread {
            while (true) {
                val next = lock.withLock {
                    val sink = consumer
                    if (sink == null || pending.isEmpty()) null else pending.removeFirst() to sink
                }
                if (next == null) {
                    if (!running && lock.withLock { pending.isEmpty() }) {
                        finished = true
                        break
                    }
                    java.lang.Thread.sleep(5)
                    continue
                }
                next.second.invoke(next.first)
            }
        }
    }

    /** Maps RMS level to roughly the -2..10 range that RecognitionListener.onRmsChanged uses. */
    fun toRmsDb(buf: ShortArray, n: Int): Float {
        if (n <= 0) return -2f
        var sum = 0.0
        for (i in 0 until n) sum += buf[i].toDouble() * buf[i].toDouble()
        val rms = sqrt(sum / n) / 32768.0
        val db = 20 * log10(rms + 1e-9)
        return ((db + 60) / 60 * 12 - 2).toFloat().coerceIn(-2f, 10f)
    }
}

/** 60 seconds; far more than the pause between requesting a session and the model being ready. */
private const val MAX_PENDING_CHUNKS = 600
