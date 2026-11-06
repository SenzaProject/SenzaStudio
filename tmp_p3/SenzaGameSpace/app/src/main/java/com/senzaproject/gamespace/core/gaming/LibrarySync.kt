package com.senzaproject.gamespace.core.gaming

import com.senzaproject.gamespace.core.database.GameEntity

data class SyncPlan(
    val upserts: List<GameEntity>,
    val deletes: List<String>,
    val added: Int,
) {
    val removed: Int get() = deletes.size
}

/** Pure merge of a fresh scan into the stored library. No Android dependencies, so it is unit-tested on the JVM. */
object LibrarySync {
    fun plan(existing: List<GameEntity>, installed: List<InstalledApp>, now: Long): SyncPlan {
        // An empty scan is treated as a failed scan, never as "everything was uninstalled".
        if (installed.isEmpty()) return SyncPlan(emptyList(), emptyList(), 0)

        val installedByPackage = installed.associateBy { it.packageName }
        val existingByPackage = existing.associateBy { it.packageName }

        val newGames = installed
            .filter { it.isGame && it.packageName !in existingByPackage }
            .map { GameEntity(it.packageName, it.label, GameSource.AUTO, hidden = false, addedAt = now) }

        val relabelled = existing.mapNotNull { game ->
            val app = installedByPackage[game.packageName]
            if (app != null && app.label != game.label) game.copy(label = app.label) else null
        }

        val gone = existing.filter { it.packageName !in installedByPackage }.map { it.packageName }
        return SyncPlan(upserts = newGames + relabelled, deletes = gone, added = newGames.size)
    }
}
