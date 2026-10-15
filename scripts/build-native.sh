#!/bin/sh
# Build the app's native pieces with the Android NDK:
#
#   libproc_jni.so       - fork/exec bridge so the app can start llama-server itself
#   libllamaserver.so    - llama.cpp's HTTP server, built for Android. The app runs
#                          one instance warm for a whole dictation session, so no
#                          model is reloaded per turn. It is an executable shipped
#                          under a lib*.so name - see the staging comment below.
#   libtranscribe_jni.so - transcribe.cpp behind stt/Transcriber.kt
#
#   scripts/build-native.sh              # build + stage into app/src/main/jniLibs
#   scripts/build-native.sh --install    # also assemble, install and grant
#   scripts/build-native.sh --no-stt     # skip speech recognition
#
# Needs two checkouts, neither of which is vendored here:
#
#   git clone https://github.com/ggml-org/llama.cpp            ../llama.cpp
#   git clone https://github.com/handy-computer/transcribe.cpp ../transcribe.cpp
#
# and a build toolchain, which is NOT all apt packages:
#
#   apt install cmake ninja-build
#   sdkmanager --install "ndk-bundle"       # the NDK is not in apt
#
# Defaults assume the checkouts sit next to this one; NDK=, LLAMA_ROOT= and
# TRANSCRIBE_ROOT= override that.

set -e

ROOT=$(cd "$(dirname "$0")/.." && pwd)
APP_CPP="$ROOT/app/src/main/cpp"
OUT=${OUT:-$ROOT/native-build}

# Defaults assume both upstream checkouts sit next to this one.
NDK=${NDK:-${ANDROID_NDK:-$HOME/android-sdk/ndk-bundle}}
LLAMA_ROOT=${LLAMA_ROOT:-$ROOT/../llama.cpp}
TRANSCRIBE_ROOT=${TRANSCRIBE_ROOT:-$ROOT/../transcribe.cpp}
ABI=${ABI:-arm64-v8a}
PLATFORM=${PLATFORM:-android-26}
INSTALL=0
STT=1

for arg in "$@"; do
    case "$arg" in
        --install) INSTALL=1 ;;
        --no-stt) STT=0 ;;
        *) echo "ignoring argument: $arg" ;;
    esac
done

CMAKE=${CMAKE:-$(command -v cmake || true)}
if [ -z "$CMAKE" ]; then
    echo "cmake is required:  apt install cmake"
    exit 1
fi
NINJA=${NINJA:-$(command -v ninja || true)}
if [ -z "$NINJA" ]; then
    echo "ninja is required:  apt install ninja-build"
    exit 1
fi

if [ ! -f "$NDK/build/cmake/android.toolchain.cmake" ]; then
    echo "No NDK at '$NDK'."
    echo "  sdkmanager --install \"ndk-bundle\""
    echo "or point at yours:  NDK=/path/to/android-ndk-rXXc $0"
    exit 1
fi
if [ ! -f "$LLAMA_ROOT/CMakeLists.txt" ]; then
    echo "No llama.cpp at '$LLAMA_ROOT'."
    echo "  git clone https://github.com/ggml-org/llama.cpp \"$LLAMA_ROOT\""
    echo "or point at yours with LLAMA_ROOT=."
    exit 1
fi

echo "toolchain: $NDK"
echo "cmake:     $CMAKE"
echo "ninja:     $NINJA"
echo "llama.cpp: $LLAMA_ROOT"
echo "abi:       $ABI ($PLATFORM)"

# -DBUILD_SHARED_LIBS=OFF  : static llama/ggml, so llama-server needs no .so beside it
# -DGGML_OPENMP=OFF        : the NDK's libomp.so is not on the app's linker path
# -DLLAMA_OPENSSL=OFF      : plain HTTP on localhost only
# -DLLAMA_BUILD_UI=OFF     : no embedded Web UI to build or ship
# max-page-size=16384      : the device uses 16 KB pages (Android 15+ requirement)
CMAKE_ARGS="
    -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake
    -DANDROID_NDK=$NDK
    -DANDROID_ABI=$ABI
    -DANDROID_PLATFORM=$PLATFORM
    -DANDROID_TOOLCHAIN=clang
    -DANDROID_STL=c++_static
    -DCMAKE_BUILD_TYPE=Release
    -GNinja -DCMAKE_MAKE_PROGRAM=$NINJA
    -DBUILD_SHARED_LIBS=OFF
    -DGGML_OPENMP=OFF
    # Baseline armv8-a has no dotprod/i8mm, which leaves ggml on slow scalar
    # kernels for quantised matmul (prompt processing was ~17 tok/s).
    -DGGML_CPU_ARM_ARCH=armv8.6-a+dotprod+i8mm
    -DLLAMA_OPENSSL=OFF
    -DLLAMA_BUILD_UI=OFF
    -DLLAMA_BUILD_TOOLS=ON
    -DLLAMA_BUILD_SERVER=ON
    -DLLAMA_BUILD_COMMON=ON
    -DLLAMA_BUILD_TESTS=OFF
    -DLLAMA_BUILD_EXAMPLES=OFF
    -DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384
    -DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384
    -DLLAMA_ROOT=$LLAMA_ROOT
"
# shellcheck disable=SC2086
rm -f "$OUT/CMakeCache.txt"
"$CMAKE" -B "$OUT" -S "$APP_CPP" $CMAKE_ARGS

for t in llama-server proc_jni; do
    "$CMAKE" --build "$OUT" --target "$t" -j "$(nproc)"
done

# transcribe.cpp is built as its own project (see app/src/main/cpp/stt/CMakeLists.txt):
# it vendors a second copy of ggml, whose targets collide with llama.cpp's.
if [ "$STT" = 1 ] && [ ! -f "$TRANSCRIBE_ROOT/CMakeLists.txt" ]; then
    echo
    echo "No transcribe.cpp at '$TRANSCRIBE_ROOT' - skipping speech recognition."
    echo "  git clone https://github.com/handy-computer/transcribe.cpp \"$TRANSCRIBE_ROOT\""
    echo "Without it there is no libtranscribe_jni.so, so the app can only use"
    echo "FakeSttEngine. Re-run with TRANSCRIBE_ROOT= once it is cloned."
    echo
    STT=0
fi
if [ "$STT" = 1 ]; then
    echo "--- speech to text: $TRANSCRIBE_ROOT"
    # transcribe.cpp is configured as the top-level project: it builds its public header
    # path from CMAKE_SOURCE_DIR, so it cannot be add_subdirectory'd from here.
    STT_OUT="$ROOT/native-build-stt"
    # shellcheck disable=SC2086
    rm -f "$STT_OUT/CMakeCache.txt"
    "$CMAKE" -B "$STT_OUT" -S "$TRANSCRIBE_ROOT" $CMAKE_ARGS \
        -DTRANSCRIBE_BUILD_TESTS=OFF \
        -DTRANSCRIBE_BUILD_EXAMPLES=OFF \
        -DTRANSCRIBE_BUILD_TOOLS=OFF
    "$CMAKE" --build "$STT_OUT" --target transcribe -j "$(nproc)"

    # then the JNI wrapper, linked against the archives that produced.
    STT_JNI_OUT="$ROOT/native-build-stt-jni"
    # shellcheck disable=SC2086
    rm -f "$STT_JNI_OUT/CMakeCache.txt"
    "$CMAKE" -B "$STT_JNI_OUT" -S "$APP_CPP/stt" $CMAKE_ARGS \
        -DTRANSCRIBE_ROOT="$TRANSCRIBE_ROOT" \
        -DTRANSCRIBE_BUILD_DIR="$STT_OUT"
    "$CMAKE" --build "$STT_JNI_OUT" --target transcribe_jni -j "$(nproc)"
fi

mkdir -p "$OUT/bin"

# Stage everything through AGP's jniLibs path: <abi>/ -> lib/<abi>/ in the APK, stored
# uncompressed, page-aligned, and extracted to a directory the app may execute from.
#
# llama-server ships as "libllamaserver.so" on purpose. Two reasons:
#   1. SELinux forbids an app from executing a file in its own data directory, so
#      unpacking the program there and exec'ing it fails; the extracted native
#      library directory is executable, which is the documented workaround.
#   2. Only lib*.so entries are packaged as native libraries by AGP.
# The file is an ordinary executable, not a shared object - nothing dlopens it.
SERVER="$OUT/bin/llama-server"
if [ -f "$SERVER" ]; then
    # Use the NDK's own strip: a host strip may not understand the target, and
    # hard-coding one on this machine is no use to anyone else.
    if [ -z "${STRIP:-}" ]; then
        STRIP=$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip 2>/dev/null | head -1)
    fi
    if [ -n "${STRIP:-}" ] && [ -x "$STRIP" ]; then
        "$STRIP" --strip-all "$SERVER"
    else
        echo "note: no llvm-strip found; llama-server left unstripped"
    fi
fi

JNILIBS="$ROOT/app/src/main/jniLibs/$ABI"
rm -rf "$ROOT/app/src/main/jniLibs"
mkdir -p "$JNILIBS"
for so in "$OUT"/libproc_jni.so "$ROOT/native-build-stt-jni"/libtranscribe_jni.so; do
    [ -f "$so" ] && cp "$so" "$JNILIBS/"
done
if [ -f "$SERVER" ]; then
    cp "$SERVER" "$JNILIBS/libllamaserver.so"
    chmod 755 "$JNILIBS/libllamaserver.so"
fi
# The program used to be an APK asset; drop the stale copy.
rm -f "$ROOT/app/src/main/assets/llama-server"
ls -la "$JNILIBS"

if [ "$INSTALL" = 1 ]; then
    # Building and installing the APK, and granting what only adb can grant, is
    # install.sh's job - that way one script does it however you got here.
    "$ROOT/scripts/install.sh"
fi

echo "done."
