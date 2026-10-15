# Native side of SpeechBridge.
#
#   libproc_jni.so        -> fork/exec bridge (Kotlin has no way to spawn a process)
#   llama-server          -> llama.cpp's HTTP server, built for Android and packaged
#                            into jniLibs as libllamaserver.so so the app may execute
#                            it from its native library directory
#
# scripts/build-native.sh drives this with the NDK toolchain; Gradle then packages
# whatever lands in app/src/main/jniLibs.
#
# libtranscribe_jni.so is NOT built here: transcribe.cpp vendors its own ggml, which
# collides with llama.cpp's under a single project. It has its own CMakeLists in stt/.
#
# The LLM is deliberately NOT linked into the app: llama-server runs as a child
# process so the GGUF is loaded once and stays warm across utterances.

cmake_minimum_required(VERSION 3.24)
project(speechbridge_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_CONFIGS 17)

# ---------------------------------------------------------------------------
# llama.cpp: the `llama-server` program target, which build-native.sh builds and
# stages. Nothing here links llama into a shared library.
# ---------------------------------------------------------------------------
if (LLAMA_ROOT)
    add_subdirectory(${LLAMA_ROOT} speechbridge_llama_build)
endif()

# ---------------------------------------------------------------------------
# Android specifics: the NDK toolchain supplies the sysroot; keep the JNI
# symbols visible and everything else hidden.
# ---------------------------------------------------------------------------
add_library(proc_jni SHARED
    ${CMAKE_CURRENT_SOURCE_DIR}/proc_jni.cpp)

foreach(_lib proc_jni)
    if (TARGET ${_lib})
        set_property(TARGET ${_lib} PROPERTY VISIBILITY_HIDDEN TRUE)
        target_compile_options(${_lib} PRIVATE
            -fvisibility=hidden
            -O2)
    endif()
endforeach()
