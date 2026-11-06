package com.senzaproject.gamespace.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.senzaproject.gamespace.core.gaming.GameMode
import com.senzaproject.gamespace.core.gaming.GameSource
import com.senzaproject.gamespace.core.gaming.MonitorLayout

@Entity(tableName = "games")
data class GameEntity(
    @PrimaryKey val packageName: String,
    val label: String,
    val source: GameSource,
    /** Hidden games are "removed from the library" but not uninstalled. The row is kept so a rescan does not re-add it. */
    val hidden: Boolean = false,
    val addedAt: Long,
    /** Set only when the game is launched through Game Master. */
    val lastLaunchedAt: Long? = null,
    val launchCount: Int = 0,
)

/** One independent profile per game. A missing row means "all defaults". */
@Entity(
    tableName = "game_profiles",
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["packageName"],
            childColumns = ["packageName"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class GameProfileEntity(
    @PrimaryKey val packageName: String,
    /** null = use the global default mode. */
    val preferredMode: GameMode? = null,
    /** null = do not change brightness for this game. Valid range 1..100. */
    val brightnessPercent: Int? = null,
    val dndEnabled: Boolean = false,
    val overlayEnabled: Boolean = false,
    val monitorLayout: MonitorLayout = MonitorLayout.COMPACT,
    val trackSessions: Boolean = true,
)
