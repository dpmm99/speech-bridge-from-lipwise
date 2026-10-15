// libproc_jni.so - the app's fork/exec bridge.
//
// Kotlin 2.x dropped java.os.Posix, so the JVM has no way to start a program.
// This exposes exactly what LlamaServer needs: start a long-lived child, check
// it is alive, and stop it.
//
// JNI symbol names
// ----------------
// The JVM looks up `Java_<class>_<method>` with the class name mangled
// ('.' -> '_'). The class is com.example.speechbridge.ai.NativeRun, so the real
// symbol is Java_com_example_speechbridge_ai_NativeRun_nativeStart. The
// length-prefixed spelling is exported too because an earlier revision of this
// file used it; an extra alias costs nothing and removes the guess.

#include <jni.h>

#include <csignal>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include <fcntl.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <unistd.h>

#define SYM(cls, meth) Java_com_example_speechbridge_ai_##cls##_##meth
#define SYM_ALT(cls, meth) Java_4com_7example_12speechbridge_2ai_9##cls##9##meth

namespace {

// argv arrives as one string with the arguments separated by \x1f, so no JSON or
// escaping rules are needed on either side.
std::vector<std::string> splitArgs(JNIEnv *jni, jstring argv_line) {
    std::vector<std::string> parts;
    const char *raw = jni->GetStringUTFChars(argv_line, nullptr);
    if (raw == nullptr) {
        return parts;
    }
    std::string cur;
    for (const char *c = raw;; ++c) {
        if (*c == '\x1f' || *c == '\0') {
            if (!cur.empty()) {
                parts.push_back(cur);
            }
            if (*c == '\0') {
                break;
            }
            cur.clear();
        } else {
            cur.push_back(*c);
        }
    }
    jni->ReleaseStringUTFChars(argv_line, raw);
    return parts;
}

std::string utf(JNIEnv *jni, jstring s, const char *fallback) {
    if (s == nullptr) {
        return std::string(fallback);
    }
    const char *raw = jni->GetStringUTFChars(s, nullptr);
    std::string out = raw != nullptr ? std::string(raw) : std::string(fallback);
    if (raw != nullptr) {
        jni->ReleaseStringUTFChars(s, raw);
    }
    return out;
}

// ---------------------------------------------------------------------------
// start(argv, logPath): Int
//   Forks the program and returns its pid immediately (the caller polls the
//   server's HTTP port for readiness). Returns -1 on failure. The child's stdout
//   and stderr go to logPath; stdin is /dev/null.
// ---------------------------------------------------------------------------
jint startImpl(JNIEnv *jni, jstring argv_line, jstring log_path) {
    const std::vector<std::string> parts = splitArgs(jni, argv_line);
    if (parts.empty()) {
        return -1;
    }
    const std::string log_file = utf(jni, log_path, "");

    std::vector<char *> argv;
    argv.reserve(parts.size() + 1);
    for (const std::string &a : parts) {
        argv.push_back(strdup(a.c_str()));
    }
    argv.push_back(nullptr);

    const pid_t child = fork();
    if (child < 0) {
        return -1;
    }
    if (child == 0) {
        // Die if the app dies, so a server cannot outlive the process that owns the
        // model. NOTE: PR_SET_PDEATHSIG is delivered when the parent *thread* exits,
        // not merely the parent process, so the caller must fork from a thread that
        // lives as long as the app (LlamaServer's spawner thread). Forking from a
        // short-lived worker kills the child the moment that worker returns.
        prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0);

        const int null_fd = open("/dev/null", O_RDONLY);
        if (null_fd >= 0) {
            dup2(null_fd, 0);
            close(null_fd);
        }
        if (!log_file.empty()) {
            const int fd = open(log_file.c_str(), O_CREAT | O_WRONLY | O_TRUNC, 0644);
            if (fd >= 0) {
                dup2(fd, 1);
                dup2(fd, 2);
                close(fd);
            }
        }
        execv(argv[0], argv.data());
        // execv only returns on failure. Say why: without this the caller just sees an
        // empty log, which is how "the binary is in the app data dir and SELinux forbids
        // executing it" looked like "it crashed for no reason".
        dprintf(2, "execv(%s) failed: %s\n", argv[0], strerror(errno));
        _exit(127);
    }
    return static_cast<jint>(child);
}

// ---------------------------------------------------------------------------
// stop(pid): Unit -- SIGTERM, then SIGKILL if it is still there. Reaps the child
// so the app does not accumulate zombies.
// ---------------------------------------------------------------------------
void stopImpl(jint pid) {
    if (pid <= 0) {
        return;
    }
    kill(static_cast<pid_t>(pid), SIGTERM);
    for (int i = 0; i < 50; ++i) {
        const pid_t r = waitpid(static_cast<pid_t>(pid), nullptr, WNOHANG);
        if (r != 0) {
            return;  // exited (or already reaped)
        }
        usleep(20 * 1000);
    }
    kill(static_cast<pid_t>(pid), SIGKILL);
    waitpid(static_cast<pid_t>(pid), nullptr, 0);
}

// ---------------------------------------------------------------------------
// alive(pid): Boolean -- signal 0 probes the pid without touching it, but it also
// succeeds for a zombie, which made a crashed server look ready. Reap with
// WNOHANG first: if the child has exited this collects it and reports false.
// ---------------------------------------------------------------------------
jboolean aliveImpl(jint pid) {
    if (pid <= 0) {
        return JNI_FALSE;
    }
    int status = 0;
    const pid_t reaped = waitpid(static_cast<pid_t>(pid), &status, WNOHANG);
    if (reaped == static_cast<pid_t>(pid)) {
        return JNI_FALSE;
    }
    return kill(static_cast<pid_t>(pid), 0) == 0 ? JNI_TRUE : JNI_FALSE;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL SYM(NativeRun, nativeStart)(JNIEnv *jni, jobject, jstring a, jstring l) {
    return startImpl(jni, a, l);
}
JNIEXPORT void JNICALL SYM(NativeRun, nativeStop)(JNIEnv *jni, jobject, jint pid) {
    stopImpl(pid);
}
JNIEXPORT jboolean JNICALL SYM(NativeRun, nativeAlive)(JNIEnv *jni, jobject, jint pid) {
    return aliveImpl(pid);
}

JNIEXPORT jint JNICALL SYM_ALT(NativeRun, nativeStart)(JNIEnv *jni, jobject, jstring a, jstring l) {
    return startImpl(jni, a, l);
}
JNIEXPORT void JNICALL SYM_ALT(NativeRun, nativeStop)(JNIEnv *jni, jobject, jint pid) {
    stopImpl(pid);
}
JNIEXPORT jboolean JNICALL SYM_ALT(NativeRun, nativeAlive)(JNIEnv *jni, jobject, jint pid) {
    return aliveImpl(pid);
}

}  // extern "C"
