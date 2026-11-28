#!/usr/bin/env bash
# Same idempotent platform-37 step as build.yml (H1a-2a), kept in one place for the scratch workflow.
set -eu
PKG="platforms;android-37.0"
SM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
test -x "$SM"
installed() { "$SM" --list_installed 2>/dev/null | awk -F'|' -v p="$PKG" '{ gsub(/ /, "", $1); if ($1 == p) f = 1 } END { exit f ? 0 : 1 }'; }
if installed; then echo "SDK-PLATFORM: $PKG already installed"; else yes | "$SM" --install "$PKG"; installed; fi
test -f "$ANDROID_HOME/platforms/android-37.0/android.jar"
