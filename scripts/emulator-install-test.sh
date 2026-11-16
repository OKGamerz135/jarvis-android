#!/usr/bin/env bash
# Runs on a booted emulator (adb available). Checks real install / update / refusal behaviour.
# Usage: emulator-install-test.sh <build40_k0.apk> <A.apk> <B_update.apk> <C_other_signer.apk>
#   A = new build, key1.  B = same key1, higher versionCode.  C = B re-signed with a DIFFERENT key.
# Only throwaway keys are involved. Prints "RESULT: <case> PASS|FAIL" lines and exits non-zero on any FAIL.
set -u
PKG=dev.jarvis.assistant
OLD="$1"; A="$2"; B="$3"; C="$4"
fails=0
res() { echo "RESULT: $1 $2"; [ "$2" = PASS ] || fails=$((fails+1)); }
inst() { adb install "$@" 2>&1 | tr '\r\n' '  '; }
info() { adb shell dumpsys package $PKG; }
field() { info | grep -m1 "$1" | sed 's/^ *//'; }

echo "== device: $(adb shell getprop ro.build.version.release) / API $(adb shell getprop ro.build.version.sdk)"
adb uninstall $PKG >/dev/null 2>&1

# 1. The released Build 40 (old key K0) installs on this Android version.
out=$(inst "$OLD"); echo "$out"; case "$out" in *Success*) res "install-build40-k0" PASS;; *) res "install-build40-k0" FAIL;; esac

# 2. A new-key build must NOT install over the old-key build (this is why the migration is clean-install only).
out=$(inst -r "$A"); echo "$out"
case "$out" in *INSTALL_FAILED_UPDATE_INCOMPATIBLE*|*signatures*) res "new-key-over-old-key-refused" PASS;; *) res "new-key-over-old-key-refused" FAIL;; esac
# The old install must be untouched.
info | grep -q 'versionCode=40' && res "old-install-untouched-after-refusal" PASS || res "old-install-untouched-after-refusal" FAIL

# 3. Clean install: uninstall old, install new.
adb uninstall $PKG >/dev/null 2>&1
out=$(inst "$A"); echo "$out"; case "$out" in *Success*) res "clean-install-new-key" PASS;; *) res "clean-install-new-key" FAIL;; esac
first1=$(field firstInstallTime); vc1=$(field versionCode); echo "after A: $first1 | $vc1"

# 4. The app starts and does not crash.
adb logcat -c
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 8
if adb shell pidof $PKG >/dev/null 2>&1 && [ -n "$(adb shell pidof $PKG | tr -d '\r\n ')" ] && ! adb logcat -d | grep -q "FATAL EXCEPTION"; then res "app-starts-no-crash" PASS; else res "app-starts-no-crash" FAIL; adb logcat -d | grep -A12 "FATAL EXCEPTION" | head -40; fi

# 5. Same-key update with a higher versionCode installs in place (firstInstallTime unchanged = not a reinstall).
out=$(inst -r "$B"); echo "$out"; case "$out" in *Success*) res "same-key-update-accepted" PASS;; *) res "same-key-update-accepted" FAIL;; esac
first2=$(field firstInstallTime); vc2=$(field versionCode); echo "after B: $first2 | $vc2"
[ "$first1" = "$first2" ] && [ "$vc1" != "$vc2" ] && res "update-in-place-preserves-install-record" PASS || res "update-in-place-preserves-install-record" FAIL

# 6. Same version, DIFFERENT signer must be refused and the installed app must stay as it was.
out=$(inst -r "$C"); echo "$out"
case "$out" in *INSTALL_FAILED_UPDATE_INCOMPATIBLE*|*signatures*) res "other-signer-update-refused" PASS;; *) res "other-signer-update-refused" FAIL;; esac
[ "$(field versionCode)" = "$vc2" ] && res "installed-app-unchanged-after-other-signer" PASS || res "installed-app-unchanged-after-other-signer" FAIL

# 7. Downgrade (A has a lower versionCode than B) must be refused.
out=$(inst -r "$A"); echo "$out"
case "$out" in *INSTALL_FAILED_VERSION_DOWNGRADE*|*downgrade*|*Downgrade*) res "downgrade-refused" PASS;; *) res "downgrade-refused" FAIL;; esac

# 8. Old key K0 build cannot replace the new install either.
out=$(inst -r "$OLD"); echo "$out"
case "$out" in *INSTALL_FAILED_UPDATE_INCOMPATIBLE*|*signatures*|*VERSION_DOWNGRADE*) res "old-key-build-over-new-refused" PASS;; *) res "old-key-build-over-new-refused" FAIL;; esac

echo; echo "failures: $fails"
exit "$fails"
