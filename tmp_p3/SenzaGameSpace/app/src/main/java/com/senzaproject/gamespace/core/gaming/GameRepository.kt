package com.senzaproject.gamespace.core.gaming

import androidx.room.withTransaction
import com.senzaproject.gamespace.core.database.GameDao
import com.senzaproject.gamespace.core.database.GameEntity
import com.senzaproject.gamespace.core.database.GameMasterDatabase
import com.senzaproject.gamespace.core.database.GameProfileDao
import com.senzaproject.gamespace.core.database.GameProfileEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ScanOutcome {
    data class Success(val added: Int, val removed: Int) : ScanOutcome
    data class Failed(val message: String?) : ScanOutcome
}

@Singleton
class GameRepository @Inject constructor(
    private val db: GameMasterDatabase,
    private val gameDao: GameDao,
    private val profileDao: GameProfileDao,
    private val appSource: InstalledAppSource,
    private val launcher: GameLauncher,
) {
    private val scanLock = Mutex()

    val visibleGames: Flow<List<GameEntity>> = gameDao.observeVisible()
    val visibleCount: Flow<Int> = gameDao.observeVisibleCount()

    fun recentGames(limit: Int = 5): Flow<List<GameEntity>> = gameDao.observeRecent(limit)
    fun observeGame(packageName: String): Flow<GameEntity?> = gameDao.observeVisibleGame(packageName)
    fun observeProfile(packageName: String): Flow<GameProfileEntity> =
        profileDao.observe(packageName).map { it ?: GameProfileEntity(packageName) }

    suspend fun appDetails(packageName: String): AppDetails? = appSource.details(packageName)

    /** Detects installed games and merges them into the library. Concurrent calls are serialized. */
    suspend fun rescan(): ScanOutcome = scanLock.withLock {
        try {
            val installed = appSource.launchableApps()
            val plan = db.withTransaction {
                val plan = LibrarySync.plan(gameDao.getAll(), installed, System.currentTimeMillis())
                if (plan.upserts.isNotEmpty()) gameDao.upsertAll(plan.upserts)
                plan.deletes.chunked(DELETE_CHUNK).forEach { gameDao.deleteAll(it) }
                plan
            }
            ScanOutcome.Success(plan.added, plan.removed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ScanOutcome.Failed(e.message)
        }
    }

    /** Launchable apps that are not currently visible in the library, for the manual "add game" picker. */
    suspend fun candidatesToAdd(): List<InstalledApp> {
        val visible = gameDao.getAll().filter { !it.hidden }.map { it.packageName }.toSet()
        return appSource.launchableApps()
            .filter { it.packageName !in visible }
            .sortedBy { it.label.lowercase() }
    }

    suspend fun addManually(app: InstalledApp) {
        db.withTransaction {
            val existing = gameDao.get(app.packageName)
            if (existing == null) {
                gameDao.upsertOne(
                    GameEntity(app.packageName, app.label, GameSource.MANUAL, hidden = false, addedAt = System.currentTimeMillis()),
                )
            } else {
                gameDao.upsertOne(existing.copy(hidden = false))
            }
        }
    }

    /** Removes the game from the library without uninstalling it. Its profile is kept. */
    suspend fun hide(packageName: String) = gameDao.hide(packageName)

    /** Launches the game and records the launch only if Android accepted the intent. */
    suspend fun launch(packageName: String): LaunchResult {
        val result = launcher.launch(packageName)
        if (result is LaunchResult.Success) gameDao.recordLaunch(packageName, System.currentTimeMillis())
        return result
    }

    /** Returns false if the game is not in the database (the profile has a foreign key to it). */
    suspend fun saveProfile(profile: GameProfileEntity): Boolean = db.withTransaction {
        if (gameDao.get(profile.packageName) == null) {
            false
        } else {
            profileDao.upsert(profile.copy(brightnessPercent = profile.brightnessPercent?.coerceIn(1, 100)))
            true
        }
    }

    suspend fun resetProfile(packageName: String) = profileDao.delete(packageName)

    private companion object {
        const val DELETE_CHUNK = 500
    }
}
