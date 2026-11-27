#!/usr/bin/env bash
# Scratch-only diagnostics. Read-only: prints APK size, permissions, merged manifest and the resolved dependency graph.
set -u
label="$1"
echo "DIAG-LABEL $label"
APK=app/build/outputs/apk/release/app-release.apk
ls -l "$APK"
echo "DIAG-APK-BYTES $label $(stat -c %s "$APK")"
echo "DIAG-APK-SHA256 $label $(sha256sum "$APK" | cut -d' ' -f1)"
AAPT2="$(ls -d "$ANDROID_HOME"/build-tools/*/aapt2 | sort -V | tail -1)"
"$AAPT2" dump badging "$APK" | grep -E "^(package|sdkVersion|minSdkVersion|targetSdkVersion|uses-permission)" | sed "s/^/BADGING-$label /"
python3 scratch/diag.py | sed "s/^/D-$label /"
for cfg in releaseRuntimeClasspath debugUnitTestRuntimeClasspath; do
  echo "DEPTREE-BEGIN $label $cfg"
  ./gradlew --no-daemon --console=plain -q :app:dependencies --configuration "$cfg"
  echo "DEPTREE-END $label $cfg"
done
for dep in okhttp okio kotlin-stdlib androidx.startup androidx.tracing; do
  echo "INSIGHT-BEGIN $label $dep"
  ./gradlew --no-daemon --console=plain -q :app:dependencyInsight --configuration releaseRuntimeClasspath --dependency "$dep" || true
  echo "INSIGHT-END $label $dep"
done
echo "RESOLVED-FILES-BEGIN $label"
find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1" \( -path '*okhttp*' -o -path '*com.squareup.okio*' \) -type f \( -name '*.aar' -o -name '*.jar' \) 2>/dev/null | sed 's#.*/files-2.1/##' | sort
echo "RESOLVED-FILES-END $label"
