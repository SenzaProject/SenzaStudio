# Game Master

A gaming assistant for Android by SenzaProject. Package `com.senzaproject.gamespace`.

Game Master shows real device readings and will manage games, modes, overlays and statistics.
It never shows simulated data: anything Android does not expose is labelled "not available",
and anything not built yet is labelled with its phase.

## Status

This is the **phase 1-3 build**: architecture, design system, navigation, and the game library with per-game profiles.
See [docs/FEATURE_STATUS.md](docs/FEATURE_STATUS.md) for the honest per-feature report.

## Requirements

- Android 10 (API 29) or newer
- To build: JDK 17 and Gradle 8.9 (or Android Studio Ladybug or newer)

## Build

```bash
gradle testDebugUnitTest assembleDebug
# APK: app/build/outputs/apk/debug/
```

GitHub Actions (`.github/workflows/android-build.yml`) runs the same on every push and pull request,
uploads the APKs and a SHA-256 checksum file, and builds a signed release APK when these secrets exist:
`SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.
Never commit keystores or passwords.

## Publishing to GitHub (from Termux)

```bash
git add -A
git commit -m "Phase 3: game library, Room database, per-game profiles"
git push
```

Sign in with a GitHub personal access token or `gh auth login`. Never commit tokens or keystores.

## Releases

Attach the signed APK and `SHA256SUMS.txt` from the workflow artifacts to a GitHub Release, with the version,
release notes, supported Android versions (10+) and the known limitations from the About screen.

## Languages

English and Indonesian. The app follows the system language.

## Architecture

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
