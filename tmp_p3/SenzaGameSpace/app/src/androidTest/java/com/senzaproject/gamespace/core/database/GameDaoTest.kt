package com.senzaproject.gamespace.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.senzaproject.gamespace.core.gaming.GameMode
import com.senzaproject.gamespace.core.gaming.GameSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a device or emulator (`./gradlew connectedDebugAndroidTest`). Uses a real in-memory Room database. */
@RunWith(AndroidJUnit4::class)
class GameDaoTest {
    private lateinit var db: GameMasterDatabase
    private val games get() = db.gameDao()
    private val profiles get() = db.profileDao()

    private fun game(pkg: String) = GameEntity(pkg, pkg, GameSource.AUTO, addedAt = 1L)

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GameMasterDatabase::class.java).build()
    }

    @After fun tearDown() = db.close()

    @Test fun upsertOfExistingGameKeepsItsProfile() = runBlocking {
        games.upsertOne(game("a"))
        profiles.upsert(GameProfileEntity("a", preferredMode = GameMode.PERFORMANCE))
        games.upsertOne(game("a").copy(label = "Renamed"))
        assertEquals(GameMode.PERFORMANCE, profiles.observe("a").first()?.preferredMode)
    }

    @Test fun deletingGameCascadesToProfile() = runBlocking {
        games.upsertOne(game("a"))
        profiles.upsert(GameProfileEntity("a"))
        games.deleteAll(listOf("a"))
        assertNull(profiles.observe("a").first())
    }

    @Test fun hiddenGamesAreExcludedFromVisibleQueries() = runBlocking {
        games.upsertAll(listOf(game("a"), game("b")))
        games.hide("a")
        assertEquals(listOf("b"), games.observeVisible().first().map { it.packageName })
        assertEquals(1, games.observeVisibleCount().first())
        assertNull(games.observeVisibleGame("a").first())
        assertNotNull(games.get("a"))
    }

    @Test fun recordLaunchUpdatesCountAndRecentOrdering() = runBlocking {
        games.upsertAll(listOf(game("a"), game("b")))
        games.recordLaunch("a", 100L)
        games.recordLaunch("b", 200L)
        games.recordLaunch("a", 300L)
        val recent = games.observeRecent(5).first()
        assertEquals(listOf("a", "b"), recent.map { it.packageName })
        assertEquals(2, recent.first().launchCount)
    }
}
