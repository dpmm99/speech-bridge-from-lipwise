package com.example.speechbridge.ai

/**
 * Starts and stops native programs. Backed by libproc_jni.so (plain fork/execv,
 * see app/src/main/cpp/proc_jni.cpp), which exists because Kotlin 2.x dropped
 * java.os.Posix and the JVM has no other way to spawn a process.
 *
 * [start] returns immediately: llama-server takes seconds to load a model, and the
 * caller polls its HTTP port for readiness instead of blocking here.
 */
class NativeRun {
    /** Arguments are joined with a separator the C side splits on. */
    private external fun nativeStart(argvLine: String, logPath: String): Int

    private external fun nativeStop(pid: Int)

    private external fun nativeAlive(pid: Int): Boolean

    /** Pid of the started program, or -1 when it could not be started. */
    fun start(argv: List<String>, log: java.io.File): Int =
        nativeStart(argv.joinToString(ARG_SEPARATOR), log.getAbsolutePath())

    fun stop(pid: Int) {
        nativeStop(pid)
    }

    fun alive(pid: Int): Boolean = nativeAlive(pid)
}

/**
 * Load libproc_jni.so out of the app's own native library directory.
 *
 * System.loadLibrary asks the JVM for the bare soname, and on this device the
 * app's extracted lib directory is not in the JVM's dlopen search path, so the
 * loader reports "not found" even though the library is right there. Resolving
 * ApplicationInfo.nativeLibraryDir and loading the absolute path sidesteps that
 * search entirely and leaves the symbols in the global scope, which is what makes
 * [NativeRun]'s `external fun`s bind.
 */
fun loadProcessBridge(nativeLibraryDir: String): Boolean = try {
    val so = java.io.File(nativeLibraryDir, "libproc_jni.so").getAbsolutePath()
    java.lang.System.load(so)
    android.util.Log.i("NativeRun", "loaded " + so)
    true
} catch (e: Throwable) {
    android.util.Log.w("NativeRun", "libproc_jni.so not available", e)
    false
}

private const val ARG_SEPARATOR = "\u001f"
