#!/usr/bin/env bash
# Tests for check-signing-hygiene.sh using throwaway git repositories. Prints PASS/FAIL per case.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
CHECK="$HERE/check-signing-hygiene.sh"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
fails=0; n=0

new_repo() { local d="$TMP/r$n"; mkdir -p "$d"; (cd "$d" && git init -q && git config user.email t@t && git config user.name t) ; echo "$d"; }
expect() { # name expected_exit repo
  n=$((n+1))
  out="$(bash "$CHECK" "$3" 2>&1)"; code=$?
  if [ "$code" -eq "$2" ]; then echo "PASS  $1"; else echo "FAIL  $1 (exit $code, wanted $2)"; echo "$out" | sed 's/^/      /'; fails=$((fails+1)); fi
}
commit() { (cd "$1" && git add -A -f && git commit -q -m x); }

d=$(new_repo); echo 'plain' > "$d/a.txt"; commit "$d"; expect "clean repo passes" 0 "$d"
d=$(new_repo); echo x > "$d/release.jks"; commit "$d"; expect "tracked .jks fails" 1 "$d"
d=$(new_repo); mkdir "$d/signing"; echo x > "$d/signing/a.p12"; commit "$d"; expect "tracked .p12 fails" 1 "$d"
d=$(new_repo); echo x > "$d/key.pem"; commit "$d"; expect "tracked .pem fails" 1 "$d"
d=$(new_repo); echo 'storePassword=hunter22' > "$d/keystore.properties"; commit "$d"; expect "tracked keystore.properties fails" 1 "$d"
d=$(new_repo); printf 'signingConfigs { create("x") { storePassword = "abc123" } }\n' > "$d/build.gradle.kts"; commit "$d"; expect "literal storePassword in gradle fails" 1 "$d"
d=$(new_repo); printf 'keyPassword: "abc123"\n' > "$d/ci.yml"; commit "$d"; expect "literal keyPassword in yml fails" 1 "$d"
d=$(new_repo); printf 'storePassword = System.getenv("JARVIS_KEYSTORE_PASSWORD")\nkeyPassword = System.getenv("JARVIS_KEY_PASSWORD")\n' > "$d/build.gradle.kts"; commit "$d"; expect "env-based credentials pass" 0 "$d"
d=$(new_repo); printf 'export JARVIS_KEYSTORE_PASSWORD="$KEY1_PW"\n' > "$d/ci.sh"; commit "$d"; expect "quoted shell variable reference passes" 0 "$d"
d=$(new_repo); printf 'export JARVIS_KEYSTORE_PASSWORD="hunter22"\n' > "$d/ci.sh"; commit "$d"; expect "quoted literal in shell script fails" 1 "$d"
d=$(new_repo); printf -- '-----BEGIN %s PRIVATE KEY-----\nabc\n' RSA > "$d/notes.txt"; commit "$d"; expect "private key block fails" 1 "$d"
d=$(new_repo); printf 'x\n' > "$d/untracked_ok.jks"; echo y > "$d/a.txt"; (cd "$d" && git add a.txt && git commit -q -m x); expect "untracked .jks is not a failure (ignored by git add)" 0 "$d"
d=$(new_repo); mkdir -p "$d/.github/workflows"; printf 'jobs:\n  a:\n    steps:\n      - uses: actions/checkout@v4\n' > "$d/.github/workflows/w.yml"; commit "$d"; expect "action pinned to tag fails" 1 "$d"
d=$(new_repo); mkdir -p "$d/.github/workflows"; printf 'jobs:\n  a:\n    steps:\n      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4\n      - uses: ./local-action\n' > "$d/.github/workflows/w.yml"; commit "$d"; expect "action pinned to SHA passes" 0 "$d"
d=$(new_repo); mkdir -p "$d/.github/workflows"; printf 'jobs:\n  a:\n    steps:\n      - uses: owner/repo@main\n' > "$d/.github/workflows/w.yml"; commit "$d"; expect "action on branch fails" 1 "$d"

echo; echo "cases: $n, failures: $fails"
exit "$fails"
