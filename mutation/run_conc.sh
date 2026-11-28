#!/usr/bin/env bash
# run_conc.sh ID METHOD : 30 baseline repetitions, then (only if 30/30 pass) the mutant for 30 repetitions.
# Every repetition is its own --no-daemon Gradle invocation (fresh JVM) with cleanTest; XML kept per repetition.
set -u
ID="$1"; METHOD="$2"; OUT="$GITHUB_WORKSPACE/results/$ID"; mkdir -p "$OUT"
FILTER="dev.jarvis.assistant.net.GuardCtxTest.$METHOD"
rep() {  # label n
  local label="$1" n; n=$(printf %02d "$2"); local d="$OUT/$label/rep$n"; mkdir -p "$d"
  timeout 900 ./gradlew --no-daemon cleanTestDebugUnitTest testDebugUnitTest --tests "$FILTER" >"$d/run.log" 2>&1
  echo $? >"$d/exit"
  rm -rf "$d/xml"; cp -r app/build/test-results/testDebugUnitTest "$d/xml" 2>/dev/null || mkdir -p "$d/xml"
  python3 mutation/collect.py "$d/xml" "$d/result.json" >/dev/null
  python3 mutation/emit.py rep "$ID" "$label" "$2" "$d"
}
git rev-parse HEAD >"$OUT/head.sha"
pass=0
for n in $(seq 1 30); do
  rep baseline "$n"
  python3 - "$OUT/baseline/rep$(printf %02d "$n")" <<'PY' && pass=$((pass+1))
import json, sys
d = sys.argv[1]; r = json.load(open(d + '/result.json')); t = r.get('totals') or {}
sys.exit(0 if open(d + '/exit').read().strip() == '0' and t.get('tests') == 1 and t.get('failures') == 0 and t.get('errors') == 0 else 1)
PY
  echo "baseline rep $n done, passes so far: $pass"
done
echo "baseline_pass=$pass/30" >"$OUT/baseline.txt"
if [ "$pass" -ne 30 ]; then echo "BASELINE-FAIL $pass/30" >"$OUT/status"; python3 mutation/emit.py conc "$ID" "$OUT"; exit 1; fi
bash mutation/apply_patch.sh "$ID" "$OUT"; rc=$?
if [ $rc -ne 0 ]; then echo "PATCH-FAIL rc=$rc" >"$OUT/status"; python3 mutation/emit.py conc "$ID" "$OUT"; exit 1; fi
for n in $(seq 1 30); do rep mutant "$n"; echo "mutant rep $n done"; done
echo "DONE" >"$OUT/status"
python3 mutation/emit.py conc "$ID" "$OUT"
exit 0
