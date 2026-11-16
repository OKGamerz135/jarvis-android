#!/usr/bin/env bash
# Fails the build when signing material or literal credentials are tracked, or when a workflow
# uses a third-party action that is not pinned to a full commit SHA.
# Usage: scripts/check-signing-hygiene.sh [repo_dir]   (default: current directory)
set -u
ROOT="${1:-.}"
cd "$ROOT" || { echo "cannot enter $ROOT"; exit 2; }
fail=0
note() { echo "FAIL: $*"; fail=1; }

# 1. Tracked key / keystore files.
while IFS= read -r f; do
  note "tracked signing/key file: $f"
done < <(git ls-files | grep -E -i '(\.(jks|keystore|p12|pfx|pem|der|p8|key)$|(^|/)(keystore|signing)\.properties$)')

# 2. Literal credentials in tracked text files. Reading from the environment is fine, quoted literals are not.
#    The test script is excluded because it contains deliberate bad fixtures.
# A quoted value that starts with $ is a shell/workflow variable reference, not a literal.
cred='(storePassword|keyPassword|store_?password|key_?password|keystore_?password)[[:space:]]*[=:][[:space:]]*["'"'"'][^"'"'"'$][^"'"'"']*["'"'"']'
while IFS= read -r f; do
  [ -f "$f" ] || continue
  if grep -E -i -n "$cred" "$f" >/dev/null 2>&1; then
    note "literal signing credential in $f (line numbers only, value not shown): $(grep -E -i -n "$cred" "$f" | cut -d: -f1 | tr '\n' ' ')"
  fi
done < <(git ls-files -- '*.gradle' '*.kts' '*.properties' '*.yml' '*.yaml' '*.sh' '*.kt' '*.xml' '*.json' ':(exclude)scripts/test-signing-hygiene.sh')

# 3. Private key blocks anywhere in tracked files.
while IFS= read -r f; do
  [ -f "$f" ] || continue
  if grep -E -q -e '-----BEGIN [A-Z ]*PRIVATE KEY-----' "$f"; then note "private key block in $f"; fi
done < <(git ls-files -- . ':(exclude)scripts/test-signing-hygiene.sh')

# 4. Every external action must be pinned to a 40-character commit SHA.
if [ -d .github/workflows ]; then
  while IFS= read -r line; do
    ref=$(echo "$line" | sed -E 's/^[^:]*:[0-9]+:[[:space:]-]*uses:[[:space:]]*//; s/[[:space:]]*#.*$//')
    case "$ref" in ./*|docker://*) continue;; esac
    if ! echo "$ref" | grep -E -q '^[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+@[0-9a-f]{40}$'; then
      note "action not pinned to a full commit SHA: $ref"
    fi
  done < <(grep -n -E '^[[:space:]-]*uses:' .github/workflows/*.yml .github/workflows/*.yaml 2>/dev/null)
fi

if [ "$fail" -eq 0 ]; then echo "signing hygiene OK"; fi
exit "$fail"
