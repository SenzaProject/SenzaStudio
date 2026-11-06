# Contributing

- Code and comments in English. User-facing text goes in `strings.xml` for both English and Indonesian.
- Never add simulated data or buttons that do nothing. Unbuilt features go in `FeatureCatalog` as `PLANNED`.
- Every new reading needs a documented Android data source. Return `null` when it is unavailable.
- Run `gradle testDebugUnitTest assembleDebug` before opening a pull request.
