#!/usr/bin/env bash
# Build, install and grant. This is the one command to run after a code change.
#
# Two of the things the app needs cannot be granted by the app itself, because
# Android reserves them for adb or the system:
#
#   * enabling an accessibility service - the field bridge that inserts the
#     edited text into other apps' text fields
#   * WRITE_SECURE_SETTINGS - so the app may name itself the system speech
#     recogniser
#
# Doing both here means the app never has to tell the user to go and run a
# command on a computer.
#
# Note: reinstalling an APK whose accessibility service changed clears the
# enabled-services setting, so it is re-asserted on every run rather than only
# when it looks missing.
#
#   scripts/install.sh                # build, install, grant
#   SKIP_BUILD=1 scripts/install.sh   # install an APK you already built
#   ADB=/path/to/adb scripts/install.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG=com.example.speechbridge
A11Y="$PKG/$PKG.field.BridgeAccessibilityService"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"

# First match on PATH wins; the extra candidates cover a normal SDK install.
find_tool() {
    local name=$1 found
    shift
    found=$(command -v "$name" 2>/dev/null || true)
    if [ -z "$found" ]; then
        for candidate in "$@"; do
            if [ -x "$candidate" ]; then found=$candidate; break; fi
        done
    fi
    printf '%s' "$found"
}

ADB=${ADB:-$(find_tool adb "$HOME/android-sdk/platform-tools/adb" "${ANDROID_HOME:-}/platform-tools/adb")}
GRADLE=${GRADLE:-$(find_tool gradle "$HOME/.sdkman/candidates/gradle/current/bin/gradle")}

if [ -z "$ADB" ]; then
    echo "No adb found. Install Android platform-tools, or set ADB=."
    echo "  sdkmanager --install platform-tools"
    exit 1
fi

if [ "${SKIP_BUILD:-0}" != 1 ]; then
    if [ -z "$GRADLE" ]; then
        echo "No gradle found. Install it, set GRADLE=, or build the APK yourself"
        echo "and re-run with SKIP_BUILD=1."
        exit 1
    fi
    # AGP 8.7.3's lint crashes on JDK 25 (it cannot parse the version string
    # "25.0.3"). Java 21 produces the same bytecode and keeps lint working, so
    # prefer a 21 when one is installed and JAVA_HOME was not given.
    if [ -z "${JAVA_HOME:-}" ]; then
        for candidate in "$HOME/.sdkman/candidates/java/21"* "$HOME/.sdkman/candidates/java/current"; do
            if [ -x "$candidate/bin/java" ]; then JAVA_HOME=$candidate; break; fi
        done
    fi
    echo "--- building the APK${JAVA_HOME:+ (JAVA_HOME=$JAVA_HOME)}"
    ( cd "$ROOT" && JAVA_HOME="${JAVA_HOME:-}" "$GRADLE" :app:assemble )
fi

if [ ! -f "$APK" ]; then
    echo "No APK at $APK - build it first (drop SKIP_BUILD=1)."
    exit 1
fi

"$ADB" install -r "$APK"

"$ADB" shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || \
    echo "note: WRITE_SECURE_SETTINGS not granted (the in-app button will report that)"

"$ADB" shell settings put secure enabled_accessibility_services "$A11Y"
"$ADB" shell settings put secure accessibility_enabled 1

# Push the checked-out config so a change to it takes effect. This overwrites
# the device's copy, which is the point during development, and means edits made
# on the device are replaced by the repo's version.
CFG="$ROOT/app/src/main/assets/speechbridge.config.json"
if [ -f "$CFG" ]; then
    "$ADB" push "$CFG" /data/local/tmp/speechbridge.config.json >/dev/null
    "$ADB" shell "run-as $PKG cp /data/local/tmp/speechbridge.config.json files/speechbridge.config.json"
fi

# Restart so the config and any newly granted permission are picked up.
"$ADB" shell am force-stop "$PKG"
"$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1 || true

echo "installed: bridge enabled, WRITE_SECURE_SETTINGS granted, config in place"
