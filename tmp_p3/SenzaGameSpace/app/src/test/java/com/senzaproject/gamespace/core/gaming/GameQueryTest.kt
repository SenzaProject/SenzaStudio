package com.senzaproject.gamespace.core.gaming

import com.senzaproject.gamespace.core.database.GameEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class GameQueryTest {
    private val alpha = GameEntity("com.x.alpha", "alpha", GameSource.AUTO, addedAt = 10L, lastLaunchedAt = 500L, launchCount = 2)
    private val bravo = GameEntity("com.y.bravo", "Bravo", GameSource.MANUAL, addedAt = 30L, lastLaunchedAt = 900L, launchCount = 9)
    private val charlie = GameEntity("com.z.charlie", "Charlie", GameSource.AUTO, addedAt = 20L)
    private val all = listOf(charlie, bravo, alpha)

    private fun names(q: GameQuery) = all.applyQuery(q).map { it.label }

    @Test fun defaultSortIsNameIgnoringCase() {
        assertEquals(listOf("alpha", "Bravo", "Charlie"), names(GameQuery()))
    }

    @Test fun searchMatchesLabelAndPackage_ignoringCaseAndSpaces() {
        assertEquals(listOf("Bravo"), names(GameQuery(search = "  BRAV ")))
        assertEquals(listOf("Charlie"), names(GameQuery(search = "com.z")))
        assertEquals(emptyList<String>(), names(GameQuery(search = "nothing")))
    }

    @Test fun filtersWork() {
        assertEquals(listOf("Bravo"), names(GameQuery(filter = GameFilter.MANUAL)))
        assertEquals(listOf("alpha", "Charlie"), names(GameQuery(filter = GameFilter.AUTO_DETECTED)))
        assertEquals(listOf("alpha", "Bravo"), names(GameQuery(filter = GameFilter.PLAYED)))
    }

    @Test fun recentlyPlayedPutsNeverPlayedLast() {
        assertEquals(listOf("Bravo", "alpha", "Charlie"), names(GameQuery(sort = GameSort.RECENTLY_PLAYED)))
    }

    @Test fun mostLaunchedAndRecentlyAdded() {
        assertEquals(listOf("Bravo", "alpha", "Charlie"), names(GameQuery(sort = GameSort.MOST_LAUNCHED)))
        assertEquals(listOf("Bravo", "Charlie", "alpha"), names(GameQuery(sort = GameSort.RECENTLY_ADDED)))
    }

    @Test fun searchAndFilterCombine() {
        val query = GameQuery(search = "alp", filter = GameFilter.AUTO_DETECTED, sort = GameSort.RECENTLY_PLAYED)
        assertEquals(listOf("alpha"), names(query))
    }
}
