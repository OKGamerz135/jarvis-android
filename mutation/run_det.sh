#!/usr/bin/env bash
# run_det.sh ID : unmutated control, then the single-hunk mutant, over the dev.jarvis.assistant.net.* tests
set -u
ID="$1"; OUT="$GITHUB_WORKSPACE/results/$ID"; mkdir -p "$OUT"
run_tests() {
  local label="$1"
  ./gradlew --no-daemon cleanTestDebugUnitTest testDebugUnitTest --tests 'dev.jarvis.assistant.net.*' >"$OUT/$label.log" 2>&1
  echo $? >"$OUT/$label.exit"
  rm -rf "$OUT/$label-xml"; cp -r app/build/test-results/testDebugUnitTest "$OUT/$label-xml" 2>/dev/null || mkdir -p "$OUT/$label-xml"
  python3 mutation/collect.py "$OUT/$label-xml" "$OUT/$label.json"
}
git rev-parse HEAD >"$OUT/head.sha"
run_tests control
python3 - "$OUT" <<'PY' || { echo CONTROL-FAIL >"$OUT/status"; exit 1; }
import json, sys
c = json.load(open(sys.argv[1] + '/control.json')); t = c.get('totals') or {}
ok = t.get('tests', 0) >= 47 and t.get('failures', 1) == 0 and t.get('errors', 1) == 0
sys.exit(0 if ok else 1)
PY
bash mutation/apply_patch.sh "$ID" "$OUT"; rc=$?
if [ $rc -ne 0 ]; then echo "PATCH-FAIL rc=$rc" >"$OUT/status"; exit 1; fi
run_tests mutated
{ echo "mutated_exit=$(cat "$OUT/mutated.exit")"; echo "compile_error=$(grep -c '^e: ' "$OUT/mutated.log" || true)"; } >"$OUT/status"
exit 0
