# Feature status (phase 1-3)

Nothing has been compiled or run on a device yet. This environment had no Android SDK or network, so the code
was written and checked by reading only. The build is verified by GitHub Actions on first push.
"Tested" below will only be set after a green CI run and a manual check on a phone.

| Feature | Status | Notes | Min Android |
|---|---|---|---|
| Splash, onboarding, permission setup | Implemented, untested | From phase 2 | 10 |
| Home dashboard | Partial | Real battery, RAM, storage, cores, game count, 3 most recently launched games. No gaming profile or active mode yet | 10 |
| Device monitor | Partial | CPU load, GPU, thermal: phase 4 | 10 |
| Game detection | Implemented, untested | PackageManager launcher query. Finds apps that declare themselves as games (category or legacy flag). Games that do not declare it must be added manually | 10 |
| Game library | Implemented, untested | Search, sort (4), filter (4), rescan, manual add, remove without uninstalling, launch, real icons | 10 |
| Game launch | Implemented, untested | Uses the app's launch intent. Failure is reported in a snackbar. Count and time recorded only on success | 10 |
| Game details | Implemented, untested | Real version and install dates from PackageManager, launch count, profile summary | 10 |
| Per-game settings | Partial | Profile is stored in Room and editable. **Not applied to games yet**: applying it needs session detection (phase 5) | 10 |
| Room database | Implemented, untested | `games` and `game_profiles`; schema exported to `app/schemas` on first build | 10 |
| Settings, About, EN/ID language | Implemented, untested | | 10 |
| Unit tests | Written, not run | `MetricsTest`, `LibrarySyncTest`, `GameQueryTest` | n/a |
| Instrumented DB tests | Written, not run | `GameDaoTest` (upsert keeps profile, cascade delete, hidden filtering, recent ordering) | 10 |
| FPS monitor, network assistant | Not built | Phase 4 | |
| Game mode, toolbox, floating panel, statistics | Not built | Phase 5 | |
| Root integration | Not built | Phase 6 | |

## Known limitations

- Android does not let ordinary apps set CPU or GPU frequency; Game Master will not claim to.
- "Recently launched" and launch counts only include launches made through Game Master. Real play time needs Usage access and arrives in phase 5.
- Auto-detection depends on the app or its installer declaring the game category. Undeclared games are added manually.
- The app sees launchable apps through a `<queries>` entry. It does not request QUERY_ALL_PACKAGES.
- Targets SDK 35. Raise when a newer stable SDK is available to the build.
- No Gradle wrapper jar is included; use Gradle 8.9 or Android Studio.
