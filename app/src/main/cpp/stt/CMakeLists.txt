# Builds libtranscribe_jni.so against handy-computer/transcribe.cpp.
#
# Two things force the odd shape of this file:
#
#  1. transcribe.cpp resolves its public headers through ${CMAKE_SOURCE_DIR}/include,
#     so it has to be configured as the top-level project, not add_subdirectory'd.
#     scripts/build-native.sh therefore builds it separately and points
#     TRANSCRIBE_BUILD_DIR at the result.
#  2. It vendors its own copy of ggml, whose targets clash with llama.cpp's, which is
#     the other reason the two live in different CMake runs.
#
# The archives are linked as a group so the order between transcribe/ggml/ggml-cpu
# does not matter.

cmake_minimum_required(VERSION 3.24)
project(speechbridge_stt LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)

if (NOT TRANSCRIBE_ROOT OR NOT TRANSCRIBE_BUILD_DIR)
    message(FATAL_ERROR "Set -DTRANSCRIBE_ROOT=/path/to/transcribe.cpp and -DTRANSCRIBE_BUILD_DIR=<its build dir>")
endif()

add_library(transcribe_jni SHARED
    ${CMAKE_CURRENT_SOURCE_DIR}/../transcribe_jni.cpp)

target_include_directories(transcribe_jni PRIVATE ${TRANSCRIBE_ROOT}/include)

file(GLOB_RECURSE TRANSCRIBE_ARCHIVES "${TRANSCRIBE_BUILD_DIR}/*.a")
if (NOT TRANSCRIBE_ARCHIVES)
    message(FATAL_ERROR "No static archives under ${TRANSCRIBE_BUILD_DIR}; build transcribe.cpp first")
endif()

target_link_libraries(transcribe_jni PRIVATE
    -Wl,--start-group ${TRANSCRIBE_ARCHIVES} -Wl,--end-group)

set_property(TARGET transcribe_jni PROPERTY VISIBILITY_HIDDEN TRUE)
target_compile_options(transcribe_jni PRIVATE -fvisibility=hidden -O2)
