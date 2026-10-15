// JNI glue for libtranscribe_jni.so - speech to text via handy-computer/transcribe.cpp.
//
// Kotlin side: com.example.speechbridge.stt.NativeTranscriber (see stt/Transcriber.kt).
// Built against the real include/transcribe.h; the happy path is the five calls the
// library's own hello example documents:
//
//     transcribe_open -> transcribe_run -> transcribe_full_text -> ... -> transcribe_close
//
// One session (which owns its model) is resident for the life of the process. The Kotlin
// wrapper serialises transcribe() calls, because a session cannot compute concurrently.

#include <jni.h>

#include <string>
#include <vector>

#include "transcribe.h"

#define JNI_NAME(method) Java_com_example_speechbridge_stt_NativeTranscriber_##method

namespace {

// One model + session for the whole process; transcribe_open hands back a session that
// owns the model, so there is no separate model handle to track.
struct transcribe_session * g_session = nullptr;

// Copies a jstring into a std::string (NUL becomes an empty string).
std::string to_string(JNIEnv * env, jstring s) {
    if (s == nullptr) {
        return std::string();
    }
    const char * raw = env->GetStringUTFChars(s, nullptr);
    std::string out = raw != nullptr ? std::string(raw) : std::string();
    if (raw != nullptr) {
        env->ReleaseStringUTFChars(s, raw);
    }
    return out;
}

// The Kotlin side joins the vocabulary with newlines.
std::vector<std::string> split_lines(const std::string & text) {
    std::vector<std::string> out;
    size_t pos = 0;
    while (pos < text.size()) {
        size_t nl = text.find('\n', pos);
        if (nl == std::string::npos) {
            nl = text.size();
        }
        if (nl != pos) {
            out.push_back(text.substr(pos, nl - pos));
        }
        pos = nl + 1;
    }
    return out;
}

}  // namespace

extern "C" {

// ---------------------------------------------------------------------------
// nativeInitBackends(): Boolean
// ---------------------------------------------------------------------------
JNIEXPORT jboolean JNICALL JNI_NAME(nativeInitBackends)(JNIEnv *, jobject) {
    return transcribe_init_backends_default() == TRANSCRIBE_OK ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------------------
// nativeLoad(path): Int -- 0 on success, otherwise a transcribe_status code
// ---------------------------------------------------------------------------
JNIEXPORT jint JNICALL JNI_NAME(nativeLoad)(JNIEnv * env, jobject, jstring path) {
    if (g_session != nullptr) {
        transcribe_session_free(g_session);
        g_session = nullptr;
    }
    const std::string model_path = to_string(env, path);
    if (model_path.empty()) {
        return TRANSCRIBE_ERR_INVALID_ARG;
    }

    // NULL load/session params == library defaults (backend AUTO, CPU included).
    struct transcribe_session * session = nullptr;
    const transcribe_status st = transcribe_open(model_path.c_str(), nullptr, nullptr, &session);
    if (st != TRANSCRIBE_OK || session == nullptr) {
        return static_cast<jint>(st);
    }
    g_session = session;
    return 0;
}

// ---------------------------------------------------------------------------
// nativeTranscribe(pcm, language, vocabulary): String
//   pcm is 16 kHz mono float32 in -1..1, which is what the library requires and
//   what the Kotlin capture path produces.
// ---------------------------------------------------------------------------
JNIEXPORT jstring JNICALL JNI_NAME(nativeTranscribe)(
    JNIEnv * env, jobject, jfloatArray pcm, jstring language, jstring vocabulary) {
    if (g_session == nullptr) {
        return env->NewStringUTF("");
    }
    const jsize n_samples = env->GetArrayLength(pcm);
    if (n_samples <= 0) {
        return env->NewStringUTF("");
    }
    jfloat * samples = env->GetFloatArrayElements(pcm, nullptr);
    if (samples == nullptr) {
        return env->NewStringUTF("");
    }

    struct transcribe_run_params params;
    transcribe_run_params_init(&params);
    params.task = TRANSCRIBE_TASK_TRANSCRIBE;

    // "auto" is the app's word for "no hint", not a language code.
    const std::string lang = to_string(env, language);
    if (!lang.empty() && lang != "auto") {
        params.language = lang.c_str();
    }

    // Spellings the recognizer tends to mishear. Passed as a vocabulary list when the
    // model advertises one, otherwise folded into an initial prompt.
    const std::vector<std::string> words = split_lines(to_string(env, vocabulary));
    std::vector<const char *> word_ptrs;
    word_ptrs.reserve(words.size());
    for (const std::string & w : words) {
        word_ptrs.push_back(w.c_str());
    }
    std::string joined;
    const struct transcribe_model * model = nullptr;
    if (!word_ptrs.empty()) {
        model = transcribe_get_model(g_session);
        if (model != nullptr && transcribe_model_supports(model, TRANSCRIBE_FEATURE_VOCABULARY)) {
            params.vocabulary = word_ptrs.data();
            params.n_vocabulary = static_cast<int32_t>(word_ptrs.size());
        } else if (model != nullptr && transcribe_model_supports(model, TRANSCRIBE_FEATURE_CONTEXT_PROMPT)) {
            for (size_t i = 0; i < words.size(); ++i) {
                if (i != 0) {
                    joined += ", ";
                }
                joined += words[i];
            }
            params.prompt = joined.c_str();
        }
    }

    const transcribe_status st = transcribe_run(g_session, samples, static_cast<int>(n_samples), &params);
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);

    // ABORTED and TRUNCATED still leave readable text on the session; anything else
    // is a hard failure and the caller gets an empty string instead of stale text.
    if (st != TRANSCRIBE_OK && st != TRANSCRIBE_ERR_ABORTED && st != TRANSCRIBE_ERR_OUTPUT_TRUNCATED) {
        return env->NewStringUTF("");
    }
    const char * text = transcribe_full_text(g_session);
    return env->NewStringUTF(text != nullptr ? text : "");
}

// ---------------------------------------------------------------------------
// nativeUnload(): Unit
// ---------------------------------------------------------------------------
JNIEXPORT void JNICALL JNI_NAME(nativeUnload)(JNIEnv *, jobject) {
    if (g_session != nullptr) {
        transcribe_session_free(g_session);
        g_session = nullptr;
    }
}

}  // extern "C"
