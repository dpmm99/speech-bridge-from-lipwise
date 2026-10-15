package com.example.speechbridge.session

import android.os.SystemClock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Remembers the last message dictated for each calling app (by uid) for a short time, so a new
 * recognition session started by the keyboard can still see what was just said. This is only a
 * guess about the field's contents; the accessibility bridge gives the real text.
 */
class ContextStore {

    private val mutex = java.util.concurrent.locks.ReentrantLock()
    private val entries = HashMap<Int, ContextEntry>()

    fun get(uid: Int, ttlMs: Long): String = mutex.withLock {
        val e = entries[uid] ?: return ""
        if (android.os.SystemClock.elapsedRealtime() - e.atMs <= ttlMs) e.text else ""
    }

    fun put(uid: Int, text: String) {
        mutex.withLock {
            entries[uid] = ContextEntry(text.takeLast(MAX_CHARS), android.os.SystemClock.elapsedRealtime())
        }
    }

    fun clear() {
        mutex.withLock { entries.clear() }
    }
}

class ContextEntry(val text: String, val atMs: Long)

private const val MAX_CHARS = 1_500

/** The one store the app runs. */
val contextStore = ContextStore()
