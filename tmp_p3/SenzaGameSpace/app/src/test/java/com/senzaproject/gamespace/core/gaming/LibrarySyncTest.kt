package com.senzaproject.gamespace.core.gaming

import com.senzaproject.gamespace.core.database.GameEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySyncTest {
    private fun stored(pkg: String, label: String = pkg, source: GameSource = GameSource.AUTO, hidden: Boolean = false) =
        GameEntity(pkg, label, source, hidden, addedAt = 1L)

    @Test fun newDeclaredGamesAreAdded_nonGamesAreNot() {
        val plan = LibrarySync.plan(
            existing = emptyList(),
            installed = listOf(InstalledApp("a.game", "Game", true), InstalledApp("a.tool", "Tool", false)),
            now = 100L,
        )
        assertEquals(listOf("a.game"), plan.upserts.map { it.packageName })
        assertEquals(GameSource.AUTO, plan.upserts.single().source)
        assertEquals(100L, plan.upserts.single().addedAt)
        assertEquals(1, plan.added)
    }

    @Test fun hiddenGameIsNotReAdded_andStaysHidden() {
        val plan = LibrarySync.plan(
            existing = listOf(stored("a.game", hidden = true)),
            installed = listOf(InstalledApp("a.game", "a.game", true)),
            now = 100L,
        )
        assertTrue(plan.upserts.isEmpty())
        assertTrue(plan.deletes.isEmpty())
    }

    @Test fun uninstalledGamesAreRemoved_includingManual() {
        val plan = LibrarySync.plan(
            existing = listOf(stored("a.gone"), stored("b.gone", source = GameSource.MANUAL), stored("c.here")),
            installed = listOf(InstalledApp("c.here", "c.here", true)),
            now = 100L,
        )
        assertEquals(setOf("a.gone", "b.gone"), plan.deletes.toSet())
        assertEquals(2, plan.removed)
    }

    @Test fun emptyScanNeverDeletesAnything() {
        val plan = LibrarySync.plan(existing = listOf(stored("a.game")), installed = emptyList(), now = 100L)
        assertTrue(plan.upserts.isEmpty())
        assertTrue(plan.deletes.isEmpty())
    }

    @Test fun changedLabelIsRefreshed_andOtherFieldsKept() {
        val old = stored("a.game", label = "Old").copy(launchCount = 7, lastLaunchedAt = 55L)
        val plan = LibrarySync.plan(listOf(old), listOf(InstalledApp("a.game", "New", true)), 100L)
        val updated = plan.upserts.single()
        assertEquals("New", updated.label)
        assertEquals(7, updated.launchCount)
        assertEquals(55L, updated.lastLaunchedAt)
        assertEquals(0, plan.added)
    }

    @Test fun manualGameThatIsNotDeclaredAsGameIsKept() {
        val plan = LibrarySync.plan(
            existing = listOf(stored("a.manual", source = GameSource.MANUAL)),
            installed = listOf(InstalledApp("a.manual", "a.manual", false)),
            now = 100L,
        )
        assertTrue(plan.deletes.isEmpty())
        assertTrue(plan.upserts.isEmpty())
    }
}
