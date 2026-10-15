package com.example.speechbridge.ai

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.example.speechbridge.Settings
import com.example.speechbridge.appConfig
import com.example.speechbridge.models.CatalogModel
import com.example.speechbridge.models.ModelCatalog
import com.example.speechbridge.models.ModelStore
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * One llama-server process, started on demand and then kept warm for the whole
 * dictation session.
 *
 * Starting it per utterance would reload the GGUF every time (seconds even at
 * 1.5 GB), so instead the process stays up and the app talks to it over HTTP on
 * localhost. The chat template comes from the GGUF metadata (`--jinja`), so no
 * prompt format is hard-coded here, and llama-server caches the prompt prefix,
 * which is what makes the system prompt free on every turn after the first.
 *
 * Locking: one [mutex] guards the fields; the actual process work is done
 * outside it so a slow model load never blocks [isReady].
 */
class LlamaServer(
    private val context: Context,
    private val settings: Settings,
    private val catalog: ModelCatalog,
    private val store: ModelStore,
    private val nativeLibraryDir: String,
) {
    private val mutex = ReentrantLock()
    private val bridge = NativeRun()

    /**
     * Forks on a thread that lives as long as the app.
     *
     * The child is given PR_SET_PDEATHSIG so it cannot outlive its owner, but that
     * signal is delivered when the parent *thread* exits rather than the parent
     * process. Forking straight from the preload worker therefore killed the server
     * the instant that worker returned, so the fork is handed to this single-thread
     * executor, whose thread stays alive for the life of the process.
     */
    private val spawner: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            val t = java.lang.Thread(runnable, "llama-spawner")
            t.setDaemon(true)
            t
        }

    private var pid = -1
    private var port = 0
    private var program: String? = null
    private var modelId: String? = null
    private var ready = false
    private var loading = false
    private var lastActivityMs = 0L
    private var watchdogStarted = false

    /** Why the last start failed, for the status line. */
    var lastError: String? = null
        private set

    // --- state ---------------------------------------------------------------------------------

    /** OpenAI-compatible base URL, or null until the server answers /health. */
    fun baseUrl(): String? = mutex.withLock {
        if (ready) "http://" + HOST + ":" + port + "/v1" else null
    }

    fun isReady(): Boolean = mutex.withLock { ready && bridge.alive(pid) }

    fun isBusy(): Boolean = mutex.withLock { loading }

    /** Note that the model was used, so the idle watchdog does not stop it mid-session. */
    fun touch() {
        mutex.withLock { lastActivityMs = System.currentTimeMillis() }
    }

    fun statusText(): String = mutex.withLock {
        if (loading) return@withLock "llama-server loading " + (modelId ?: "")
        if (ready && bridge.alive(pid)) return@withLock "llama-server ready on port " + port
        if (pid > 0) return@withLock "llama-server starting"
        "llama-server stopped"
    }

    // --- starting ------------------------------------------------------------------------------

    /**
     * Speculative start, called when a dictation session begins so the model is
     * loading while the user is still speaking. Skipped when the device does not
     * clearly have room for the model plus the STT engine.
     */
    fun preloadAsync() {
        val spec = selectedModel()
        if (spec == null) {
            lastError = "No local AI model selected"
            return
        }
        if (!store.isDownloaded(spec)) {
            lastError = "The local AI model has not been downloaded"
            return
        }
        if (isReady()) {
            touch()
            Log.i(TAG, "preload: already warm")
            return
        }
        if (!hasHeadroomFor(spec)) {
            lastError = "Not enough free memory to preload the model"
            Log.i(TAG, "preload skipped: " + lastError)
            return
        }
        var claimed = false
        mutex.withLock {
            if (!loading && !ready) {
                loading = true
                claimed = true
            }
        }
        if (!claimed) {
            Log.i(TAG, "preload: another start is already in flight")
            return
        }
        startWatchdog()
        thread {
            val ok = startBlocking(spec, LOAD_TIMEOUT_MS)
            Log.i(TAG, "preload " + (if (ok) "ready" else "failed: " + lastError))
        }
    }

    /**
     * Blocking start used by a real turn when the speculative preload did not
     * happen. Unlike [preloadAsync] this does not apply the memory gate: the user
     * is waiting for a result, and trying beats refusing.
     */
    fun ensureReady(timeoutMs: Long): Boolean {
        val spec = selectedModel()
        if (spec == null) {
            lastError = "No local AI model selected"
            return false
        }
        if (!store.isDownloaded(spec)) {
            lastError = "The local AI model has not been downloaded"
            return false
        }
        if (isReady()) {
            touch()
            return true
        }
        startWatchdog()
        return startBlocking(spec, timeoutMs)
    }

    fun stop() {
        val dying = mutex.withLock {
            val old = pid
            pid = -1
            port = 0
            ready = false
            loading = false
            modelId = null
            old
        }
        if (dying > 0) {
            bridge.stop(dying)
            Log.i(TAG, "stopped llama-server pid " + dying)
        }
    }

    /** Android is short on memory: the model is the first thing to give up. */
    fun onTrimMemory(level: Int) {
        // Only the RUNNING_* levels mean "the foreground app is short on memory". The
        // higher ones (UI_HIDDEN = 20, BACKGROUND = 40, MODERATE = 60, COMPLETE = 80)
        // describe background caching, so treating everything >= RUNNING_LOW as
        // pressure stopped the server whenever the app was sent to the background.
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            Log.i(TAG, "trim level " + level + ": stopping llama-server")
            stop()
        }
    }

    // --- internals -----------------------------------------------------------------------------

    private fun selectedModel(): CatalogModel? =
        catalog.find(settings.providerConfig(AiProvider.LOCAL).model)

    private fun startBlocking(spec: CatalogModel, timeoutMs: Long): Boolean {
        val current = mutex.withLock { if (ready && modelId == spec.id && bridge.alive(pid)) true else false }
        if (current) {
            touch()
            return true
        }
        stop()  // a different model, or a process that died

        val exe = programPath()
        if (exe == null) {
            return false
        }
        store.root.mkdirs()
        val log = java.io.File(store.root, LOG_NAME)
        val chosen = freePort()
        val setup = appConfig.llm
        val argv = mutableListOf<String>()
        argv.add(exe)
        argv.add("-m")
        argv.add(store.file(spec).getAbsolutePath())
        argv.add("--host")
        argv.add(HOST)
        argv.add("--port")
        argv.add(chosen.toString())
        // The chat template lives in the GGUF, so no prompt format is spelled out here.
        argv.add("--jinja")
        if (setup.reasoning) {
            argv.add("--reasoning")
            argv.add("auto")
        } else {
            // Dictation cannot wait for a scratchpad. "off" sets enable_thinking=false for
            // the template, and a zero budget ends any thinking a template still starts.
            argv.add("--reasoning")
            argv.add("off")
            argv.add("--reasoning-budget")
            argv.add("0")
        }
        argv.add("-c")
        argv.add(setup.maxContextTokens.toString())
        // A GBNF grammar makes the edit format unsamplable, so a model that would
        // rather answer in its tool-call dialect cannot. Written to the store so the
        // program can be pointed at it with --grammar-file.
        val grammarFile = writeGrammar(setup.grammar)
        if (grammarFile != null) {
            argv.add("--grammar-file")
            argv.add(grammarFile)
        }
        argv.add("-np")
        argv.add("1")
        argv.add("-t")
        argv.add(setup.threads.toString())
        argv.add("--no-ui")
        Log.i(TAG, "starting llama-server on port " + chosen + " with " + spec.id)
        val forked = java.util.concurrent.FutureTask<Int> { bridge.start(argv, log) }
        spawner.execute(forked)
        val newPid = try {
            forked.get()
        } catch (e: Throwable) {
            Log.w(TAG, "fork failed", e)
            -1
        }
        if (newPid <= 0) {
            lastError = "Could not start llama-server (libproc_jni missing?)"
            return false
        }
        mutex.withLock {
            pid = newPid
            port = chosen
            modelId = spec.id
            ready = false
            loading = true
            lastActivityMs = System.currentTimeMillis()
        }
        val ok = waitForHealth(newPid, timeoutMs)
        mutex.withLock {
            ready = ok
            loading = false
            if (!ok) {
                lastError = "llama-server did not become ready: " + tailOf(log)
            }
        }
        if (!ok) {
            stop()
        }
        return ok
    }

    /** llama-server answers /health with 200 once the model is loaded. */
    private fun waitForHealth(child: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!bridge.alive(child)) {
                return false  // it crashed on startup; the log has the reason
            }
            if (healthOk()) {
                return true
            }
            java.lang.Thread.sleep(HEALTH_POLL_MS)
        }
        return false
    }

    private fun healthOk(): Boolean = try {
        val url = URL("http://" + HOST + ":" + port + "/health")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = HEALTH_TIMEOUT_MS
            conn.readTimeout = HEALTH_TIMEOUT_MS
            conn.getResponseCode() == 200
        } finally {
            conn.disconnect()
        }
    } catch (e: Throwable) {
        false
    }

    /** The port is freed immediately, which leaves a small race with the OS. */
    private fun freePort(): Int = try {
        val socket = ServerSocket(0)
        val chosen = socket.getLocalPort()
        socket.close()
        chosen
    } catch (e: Throwable) {
        DEFAULT_PORT
    }

    /**
     * Writes the configured grammar next to the models and returns its path, or null when
     * no grammar is configured. An empty setting turns the constraint off.
     */
    private fun writeGrammar(grammar: String): String? {
        if (grammar.isBlank()) {
            return null
        }
        return try {
            store.root.mkdirs()
            val target = java.io.File(store.root, GRAMMAR_NAME)
            val out = java.io.FileOutputStream(target.getAbsolutePath())
            try {
                out.write(grammar.toByteArray(Charsets.UTF_8))
            } finally {
                out.close()
            }
            target.getAbsolutePath()
        } catch (e: Throwable) {
            Log.w(TAG, "could not write " + GRAMMAR_NAME, e)
            null
        }
    }

    /**
     * Where llama-server lives: the app's extracted native library directory.
     *
     * It ships as libllamaserver.so and is executed from there rather than copied
     * into the data directory, because SELinux denies an app the right to execute
     * files it wrote itself, while the native library directory is executable. (The
     * file is a plain executable; nothing dlopens it.)
     */
    private fun programPath(): String? {
        val cached = mutex.withLock { program }
        if (cached != null) {
            return cached
        }
        val target = java.io.File(nativeLibraryDir, PROGRAM_SO)
        if (!target.exists() || target.length() == 0L) {
            lastError = "llama-server is missing from " + nativeLibraryDir
            Log.w(TAG, lastError!!)
            return null
        }
        val path = target.getAbsolutePath()
        mutex.withLock { program = path }
        return path
    }

    /**
     * Speculative loads are only worth it when the model fits comfortably next to
     * the STT engine, so compare against the file size with room to spare.
     */
    private fun hasHeadroomFor(spec: CatalogModel): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        if (info.lowMemory) {
            return false
        }
        val file = spec.defaultFile()
        val size = if (file == null) 0L else file.sizeBytes
        val need = if (size > 0L) size * 3 / 2 else DEFAULT_MODEL_BYTES
        return info.availMem > need + STT_HEADROOM_BYTES
    }

    /** Free the model after a stretch with no dictation. */
    private fun startWatchdog() {
        mutex.withLock {
            if (watchdogStarted) return@withLock
            watchdogStarted = true
        }
        thread {
            while (true) {
                java.lang.Thread.sleep(IDLE_CHECK_MS)
                val idle = System.currentTimeMillis() - mutex.withLock { lastActivityMs }
                val busy = mutex.withLock { loading }
                if (!busy && lastActivityMs > 0L && idle > appConfig.llm.idleStopSeconds * 1000L) {
                    Log.i(TAG, "idle for " + (idle / 1000) + "s, stopping llama-server")
                    stop()
                }
            }
        }
    }

    /** Last few log lines, so a startup crash says why instead of just "failed". */
    private fun tailOf(log: java.io.File): String = try {
        val text = java.io.FileInputStream(log.getAbsolutePath())
            .use { it.bufferedReader().readText() }
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) "no output" else lines.takeLast(3).joinToString(" / ")
    } catch (e: Throwable) {
        "log unreadable"
    }
}

private const val TAG = "LlamaServer"
private const val HOST = "127.0.0.1"
private const val PROGRAM_SO = "libllamaserver.so"
private const val LOG_NAME = "llama-server.log"
private const val GRAMMAR_NAME = "grammar.gbnf"
private const val DEFAULT_PORT = 8080
private const val HEALTH_POLL_MS = 250L
private const val HEALTH_TIMEOUT_MS = 1_500
private const val LOAD_TIMEOUT_MS = 120_000L
private const val IDLE_CHECK_MS = 30_000L
private const val DEFAULT_MODEL_BYTES = 2L * 1024 * 1024 * 1024
private const val STT_HEADROOM_BYTES = 700L * 1024 * 1024

private var installed: LlamaServer? = null

/** The one local server, set by SpeechBridgeApp.onCreate. */
val llmServer: LlamaServer? get() = installed

fun installLlamaServer(server: LlamaServer) {
    installed = server
}
