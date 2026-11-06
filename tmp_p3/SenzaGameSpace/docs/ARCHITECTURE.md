# Architecture

Kotlin, Jetpack Compose, Material 3, MVVM, Hilt, Coroutines/Flow, DataStore. Room is used from phase 3. WorkManager arrives with session tracking.

## Layout

One Gradle module (`:app`) with packages that mirror the planned modules:

```
core/common        pure Kotlin helpers (unit-tested)
core/database      Room entities, DAOs, database and Hilt module
core/gaming        game detection, library sync, query, launcher, repository, icon loader
core/designsystem  theme and reusable components
core/monitoring    device readings from documented Android APIs
core/permissions   permission state and settings intents
core/settings      DataStore-backed preferences
feature/*          one package per screen group
navigation         routes, feature catalog, root scaffold
```

The packages are split by responsibility so they can be promoted to separate Gradle modules later.
Splitting now would add build complexity without any current benefit.

## Rules

- No simulated data. A missing reading is `null` and the UI says "not available".
- `FeatureCatalog` is the single source of truth for what is built. Unbuilt screens show a plain status page.
- Sampling flows use `WhileSubscribed(5s)` so monitoring stops when the screen is not visible.
- Only permissions used by the Permission Setup screen are declared.

## Phase 3 data flow

```
PackageManager -> InstalledAppSource -> LibrarySync.plan (pure) -> Room (games)
Room Flow -> GameRepository -> ViewModel (applyQuery, pure) -> Compose
```

- `LibrarySync` and `applyQuery` have no Android dependencies and are unit-tested on the JVM.
- Games use `@Upsert`, never REPLACE, because REPLACE deletes the row and cascades to its profile.
- "Remove from library" sets `hidden = true`, so a rescan does not bring the game back and its profile survives.
- An empty scan result is treated as a failed scan and never deletes games.
- There is no destructive migration fallback. A schema change needs a real migration.
