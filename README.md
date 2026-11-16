# jarvis-android

Personal voice-first AI assistant for Android. Kotlin + Jetpack Compose, strict zero-cost budget.

## Getting builds

Every push to `main` is built by GitHub Actions and published as a release with a `jarvis.apk` file.
The first install is manual (download `jarvis.apk` from the latest release). After that, use
**Settings > App updates > Check for updates** inside the app.

## Notes

- The old signing key that used to live in this repository is public and **must not be trusted**. Builds from Build 41
  on are signed with a new key kept outside the repository. Moving to it needs a clean install: uninstall the old
  JARVIS first (its chats, memories and saved keys are deleted with it). See `docs/SIGNING.md`.
