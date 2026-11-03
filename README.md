# Senza Studio

Professional Android audio enhancement platform. Status: **P2 Core DSP (native C++)** (P1 Foundation included).

- Min SDK 29, target/compile SDK 36, Kotlin + Jetpack Compose, JDK 17.
- Build: `./gradlew assembleDebug` - tests: `./gradlew testDebugUnitTest :audio-engine:test`.
- CI: GitHub Actions workflow "Build APK" builds the debug APK on every push to `main`.
- See `docs/ARCHITECTURE.md`. Logo sources are in `branding/`.

- Native DSP core: `native/senzafx` (header-only C++17). Test: `sh native/senzafx/build_and_test.sh`.
