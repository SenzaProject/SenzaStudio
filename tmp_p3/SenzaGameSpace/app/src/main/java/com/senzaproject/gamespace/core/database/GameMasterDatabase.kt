package com.senzaproject.gamespace.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(entities = [GameEntity::class, GameProfileEntity::class], version = 1, exportSchema = true)
abstract class GameMasterDatabase : RoomDatabase() {
    abstract fun gameDao(): GameDao
    abstract fun profileDao(): GameProfileDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    // No destructive fallback: a missing migration must fail loudly instead of wiping user data.
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): GameMasterDatabase =
        Room.databaseBuilder(context, GameMasterDatabase::class.java, "game_master.db").build()

    @Provides fun gameDao(db: GameMasterDatabase): GameDao = db.gameDao()
    @Provides fun profileDao(db: GameMasterDatabase): GameProfileDao = db.profileDao()
}
