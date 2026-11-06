package com.senzaproject.gamespace.core.gaming

/** Game modes from the product spec. Phase 3 only stores the preference; applying a mode is phase 5. */
enum class GameMode { BALANCED, PERFORMANCE, BATTERY_SAVER, CUSTOM }

/** How a game entered the library. */
enum class GameSource { AUTO, MANUAL }

/** Preferred layout of the in-game monitor (used by the overlay in phase 5). */
enum class MonitorLayout { MINIMAL, COMPACT, DETAILED }

/** A launchable app reported by PackageManager. [isGame] comes from the app's own category declaration. */
data class InstalledApp(val packageName: String, val label: String, val isGame: Boolean)

/** Real package information read from PackageManager. */
data class AppDetails(val versionName: String?, val firstInstallTime: Long, val lastUpdateTime: Long)
