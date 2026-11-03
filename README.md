# Senza Studio

Professional Android audio enhancement platform. Status: **P2 Core DSP** (P1 Foundation included).

- Min SDK 29, target/compile SDK 36, Kotlin + Jetpack Compose, JDK 17.
- Build: `./gradlew assembleDebug` - tests: `./gradlew testDebugUnitTest :audio-engine:test :audio-dsp:test`.
- CI: GitHub Actions workflow "Build APK" builds the debug APK on every push to `main`.
- See `docs/ARCHITECTURE.md`. Logo sources are in `branding/`.
