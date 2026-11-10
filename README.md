# jarvis-android

Personal voice-first AI assistant for Android. Kotlin + Jetpack Compose, strict zero-cost budget.

## Getting builds

Every push to `main` is built by GitHub Actions and published as a release with a `jarvis.apk` file.
The first install is manual (download `jarvis.apk` from the latest release). After that, use
**Settings > App updates > Check for updates** inside the app.

## Notes

- `app/jarvis.jks` is a committed signing key. It exists so new builds can install over old ones.
  Personal sideloading only; never use it for anything distributed.
