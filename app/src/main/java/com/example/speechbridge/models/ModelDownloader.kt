package com.example.speechbridge.models

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Progress report handed to the callback the UI passes to [download]. */
class DownloadProgress(val stage: String, val bytesDone: Long, val bytesTotal: Long)

private const val REPORT_BYTES = 256L * 1024L
private const val FETCHED = 1
private const val CANCELLED = 0

/**
 * Streams a catalog file into the store. Tries huggingface.co first, then the mirrors (mirrors are
 * only trusted for files that carry a sha256). A partial file is kept as `<name>.part` so an
 * interrupted download resumes with a Range request.
 */
class ModelDownloader(private val mirrors: List<String>, private val store: ModelStore) {

    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var lastFailure: String? = null

    /** Ask a running download to stop; [download] then returns false. */
    fun cancelDownload(model: CatalogModel) {
        cancelled.add(model.id)
    }

    /** @return true when the file is in place. False means the user cancelled. */
    suspend fun download(model: CatalogModel, onProgress: (DownloadProgress) -> Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val spec = model.defaultFile()
            if (spec == null) throw java.io.IOException("Model ${model.id} lists no files")
            if (store.isDownloaded(model)) return@withContext true
            if (!active.add(model.id)) throw java.io.IOException("${model.id} is already downloading")
            val dest = store.file(model)
            dest.getParentFile()?.mkdirs()
            try {
                val part = java.io.File("${dest.getAbsolutePath()}.part")
                val ok = runAll(model, spec, part, onProgress)
                if (!ok) {
                    val why = lastFailure
                    if (why != null) throw java.io.IOException(why)
                    // Cancelled: the user does not want this model, so drop the partial file
                    // rather than leaving a .part to rot. A failure keeps it, because there a
                    // resume has something to continue from.
                    part.delete()
                    return@withContext false
                }
                part.renameTo(dest)
                true
            } finally {
                active.remove(model.id)
                cancelled.remove(model.id)
            }
        }

    private suspend fun runAll(model: CatalogModel, spec: CatalogFile, part: java.io.File, onProgress: (DownloadProgress) -> Boolean): Boolean {
        for (url in urls(model, spec)) {
            val outcome = try {
                fetch(url, part, spec.sizeBytes, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastFailure = e.message ?: e.javaClass.simpleName
                -1
            }
            if (outcome == FETCHED) {
                if (verify(part, spec)) return true
                part.delete()
                lastFailure = "Size or hash mismatch for ${spec.filename}"
            } else if (outcome == CANCELLED) {
                lastFailure = null
                return false
            }
        }
        return false
    }

    private fun urls(model: CatalogModel, spec: CatalogFile): List<String> = buildList {
        add("https://huggingface.co/${model.id}/resolve/${model.revision}/${spec.filename}")
        if (spec.sha256 != null) {
            mirrors.forEach { add("${it.trimEnd('/')}/${model.id}/${model.revision}/${spec.filename}") }
        }
    }

    /** 1 = bytes fetched, 0 = user cancelled, -1 = failed (reason in [lastFailure]). */
    private suspend fun fetch(url: String, part: java.io.File, expected: Long, onProgress: (DownloadProgress) -> Boolean): Int {
        var have = if (part.exists()) part.length() else 0L
        if (have > expected) {
            part.delete()
            have = 0L
        }
        if (expected > 0L && have == expected) return FETCHED

        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "SpeechBridge/0.1")
            if (have > 0L) conn.setRequestProperty("Range", "bytes=$have-")

            val append: Boolean = when (conn.getResponseCode()) {
                206 -> true
                200 -> {
                    have = 0L
                    false
                }
                else -> {
                    lastFailure = "HTTP ${conn.getResponseCode()} from $url"
                    return -1
                }
            }

            val input = conn.getInputStream()
            val out = java.io.FileOutputStream(part, append)
            try {
                val buf = ByteArray(64 * 1024)
                var done = have
                var lastReport = 0L
                while (true) {
                    currentCoroutineContext()?.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - lastReport >= REPORT_BYTES) {
                        lastReport = done
                        if (!onProgress(DownloadProgress("downloading", done, expected))) {
                            out.close()
                            return CANCELLED
                        }
                    }
                }
            } finally {
                out.close()
                input.close()
            }
            return FETCHED
        } finally {
            conn.disconnect()
        }
    }

    private fun verify(part: java.io.File, spec: CatalogFile): Boolean {
        if (spec.sizeBytes > 0L && part.length() != spec.sizeBytes) return false
        if (spec.sha256 == null) return true
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val input = java.io.FileInputStream(part)
        try {
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        } finally {
            input.close()
        }
        val hex = digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return hex == spec.sha256
    }
}
