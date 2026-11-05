# Senza Studio

Professional Android audio enhancement platform. Status: **P5 Live UI, meters and presets** (P1-P4 included).

- Min SDK 29, target/compile SDK 36, Kotlin + Jetpack Compose, JDK 17.
- Build: `./gradlew assembleDebug` - tests: `./gradlew testDebugUnitTest :audio-engine:test :audio-dsp:test`.
- CI: GitHub Actions workflow "Build APK" builds the debug APK on every push to `main`.
- See `docs/ARCHITECTURE.md`, `docs/DSP_P3.md`, `docs/ANDROID_INTEGRATION.md`, `docs/UI_P5.md` and `docs/DEVICE_TEST_CHECKLIST.md`. Logo sources are in `branding/`.
