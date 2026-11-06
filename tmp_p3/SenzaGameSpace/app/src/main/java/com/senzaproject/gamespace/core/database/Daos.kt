package com.senzaproject.gamespace.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDao {
    @Query("SELECT * FROM games WHERE hidden = 0")
    fun observeVisible(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE packageName = :packageName AND hidden = 0")
    fun observeVisibleGame(packageName: String): Flow<GameEntity?>

    @Query("SELECT COUNT(*) FROM games WHERE hidden = 0")
    fun observeVisibleCount(): Flow<Int>

    @Query(
        "SELECT * FROM games WHERE hidden = 0 AND lastLaunchedAt IS NOT NULL " +
            "ORDER BY lastLaunchedAt DESC LIMIT :limit",
    )
    fun observeRecent(limit: Int): Flow<List<GameEntity>>

    @Query("SELECT * FROM games")
    suspend fun getAll(): List<GameEntity>

    @Query("SELECT * FROM games WHERE packageName = :packageName")
    suspend fun get(packageName: String): GameEntity?

    // @Upsert updates in place. REPLACE would delete the row and cascade-delete its profile.
    @Upsert
    suspend fun upsertAll(games: List<GameEntity>)

    @Upsert
    suspend fun upsertOne(game: GameEntity)

    @Query("DELETE FROM games WHERE packageName IN (:packageNames)")
    suspend fun deleteAll(packageNames: List<String>)

    @Query("UPDATE games SET hidden = 1 WHERE packageName = :packageName")
    suspend fun hide(packageName: String)

    @Query("UPDATE games SET lastLaunchedAt = :time, launchCount = launchCount + 1 WHERE packageName = :packageName")
    suspend fun recordLaunch(packageName: String, time: Long)
}

@Dao
interface GameProfileDao {
    @Query("SELECT * FROM game_profiles WHERE packageName = :packageName")
    fun observe(packageName: String): Flow<GameProfileEntity?>

    @Upsert
    suspend fun upsert(profile: GameProfileEntity)

    @Query("DELETE FROM game_profiles WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}
