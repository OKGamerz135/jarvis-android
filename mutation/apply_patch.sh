#!/usr/bin/env bash
# apply_patch.sh ID OUTDIR : verify the patch hash against registry.json, git apply --check, apply, record the diff
set -u
ID="$1"; OUT="$2"
python3 - "$ID" "$OUT" <<'PY' || exit 11
import hashlib, json, sys
i, out = sys.argv[1], sys.argv[2]
r = json.load(open('mutation/registry.json'))['executable'][i]
h = hashlib.sha256(open(r['patch'], 'rb').read()).hexdigest()
open(f'{out}/patch.sha256', 'w').write(h + '\n')
sys.exit(0 if h == r['sha256'] else 11)
PY
git apply --check "mutation/patches/$ID.patch" || exit 12
git apply "mutation/patches/$ID.patch" || exit 13
git diff --numstat > "$OUT/diff.numstat"; git diff --stat > "$OUT/diff.stat"
test "$(git diff --name-only | wc -l)" = "1" || exit 14
test "$(git diff -U0 | grep -c '^@@')" = "1" || exit 15
exit 0
