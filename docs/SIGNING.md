# Signing JARVIS releases (production key "K1")

The production signing key is created by the project owner on their own trusted computer. It is **never** stored in
this repository, never pasted into chat, and never handed to any assistant or tool. CI receives it only through
GitHub Secrets, only in the `release-build` job.

The old key (K0) was committed to this repository and is public. It is no longer used for anything. Because Android
refuses to install an app over one signed with a different key, moving to K1 is a **clean install**: uninstall the old
JARVIS first (its chats, memories and saved API keys are removed with it), then install the new build.

## 1. Create K1 (on your own computer, offline is best)

Requires a JDK (`keytool` is part of it).

```
keytool -genkeypair -v -keystore jarvis-release.jks -storetype PKCS12 \
  -alias jarvis -keyalg RSA -keysize 4096 -validity 9125 \
  -dname "CN=JARVIS release, O=<your name>"
```

- Choose a long random password (a password manager's generator). With PKCS12 the store password and the key
  password are the same; you will be asked once or twice. Remember: **never reuse the old `jarvis-personal` password.**
- Alias is `jarvis` above; use whatever you like, then use the same value for `JARVIS_KEY_ALIAS`.
- Validity 9125 days is 25 years.

Print the certificate fingerprint. You will publish this value (it is public information):

```
keytool -list -v -keystore jarvis-release.jks -alias jarvis | grep "SHA256:"
```

## 2. Back it up before anything else

If K1 is lost, nobody can ever update an installed JARVIS again (everyone must uninstall and reinstall). GitHub
Secrets cannot be read back. Keep at least **two encrypted copies in two places** (for example an encrypted archive on
two separate drives, plus the password in your password manager). Never email the key, never commit it.

## 3. Put it into GitHub (Settings of this repository)

1. **Settings → Environments → New environment** named exactly `production`.
   - Turn on **Required reviewers** and add yourself. Every release then waits for your click.
   - Under **Deployment branches**, allow **only `main`**.
2. In that environment add four **secrets**:

   | Secret | Value |
   |---|---|
   | `JARVIS_KEYSTORE_BASE64` | the keystore file, base64-encoded (below) |
   | `JARVIS_KEYSTORE_PASSWORD` | the keystore password |
   | `JARVIS_KEY_ALIAS` | the alias (for example `jarvis`) |
   | `JARVIS_KEY_PASSWORD` | the key password (same as the keystore password for PKCS12) |

3. In the same environment (or in the repository variables) add a **variable** (not a secret):
   `JARVIS_CERT_SHA256` = the SHA-256 fingerprint from step 1 (colons or no colons, any case).

Base64-encode the file on your computer:

- Linux: `base64 -w0 jarvis-release.jks`
- macOS: `base64 -i jarvis-release.jks`
- Windows PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("jarvis-release.jks"))`

With the GitHub CLI you can also do `gh secret set JARVIS_KEYSTORE_BASE64 --env production < jarvis-release.b64`
(it reads the value from the file, so it does not appear in your shell history). Delete the `.b64` file afterwards.

## 4. What CI does with it

- Pushing to `main` runs the tests, then `release-build` (the only job that can read the secrets; it has a read-only
  token). It signs with K1, runs `apksigner verify`, and **fails unless the signer's SHA-256 equals
  `JARVIS_CERT_SHA256`** and there is exactly one signer.
- `publish` (the only job with a write token; it sees no secrets and runs no Gradle) checks that the file is
  byte-identical to what was built, then creates the GitHub release.
- If any secret or the variable is missing, `release-build` fails and **nothing is published**.
- Branch builds (manual runs) never see K1. They are signed with throwaway keys created inside the run; each run's key
  is different, so uninstall the previous test build before installing the next.

## 5. Building locally with K1 (optional)

```
export JARVIS_KEYSTORE_FILE=/path/to/jarvis-release.jks
export JARVIS_KEYSTORE_PASSWORD=...   JARVIS_KEY_ALIAS=jarvis   JARVIS_KEY_PASSWORD=...
./gradlew assembleRelease
```

Without these variables the release APK is **unsigned** (it cannot be installed). Setting only some of them is an
error. Do not put these values in any file in the repository; `scripts/check-signing-hygiene.sh` (run by CI) fails the
build if a keystore, a literal password or a private key block is committed.

## 6. Installing on a phone

1. Uninstall the existing JARVIS (Settings → Apps → JARVIS → Uninstall). Everything stored in it is deleted.
2. Install the new APK.
3. Check the signer if you want to be sure: `apksigner verify --print-certs jarvis.apk` must show the K1 fingerprint
   you published. Share that fingerprint with testers through a channel other than the release page.
4. Later updates from the in-app updater install over it normally, because they are signed by the same key.

## 7. If K1 is ever lost or exposed

Create a new key (K2) and ship another clean install; installed copies cannot be moved from one key to another
without APK signing-key rotation, which this project deliberately does not rely on. Revoke the exposed secrets in
GitHub first.
