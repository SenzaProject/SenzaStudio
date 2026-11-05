package com.senzaproject.studio.audio.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PresetStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = FilePresetStore(tmp.root)
    private val sample = EngineParameters(masterGainDb = -5f, bass = BassParameters(enabled = true, subBassDb = 3f))

    @Test
    fun currentSettingsRoundTripAndAreNullWhenMissing() {
        assertNull(store().loadCurrent())
        store().saveCurrent(sample)
        assertEquals(sample.sanitized(), store().loadCurrent())
    }

    @Test
    fun userPresetsAreSavedListedAndDeleted() {
        val a = store().saveUser("Zeta", sample, now = 100)
        val b = store().saveUser("alpha", EngineParameters(), now = 200)
        val listing = store().listUser()
        assertEquals(listOf("alpha", "Zeta"), listing.presets.map { it.name })
        assertEquals(sample.sanitized(), listing.presets.first { it.id == a.id }.parameters)
        assertTrue(listing.problems.isEmpty())
        store().deleteUser(b.id)
        assertEquals(listOf("Zeta"), store().listUser().presets.map { it.name })
    }

    @Test
    fun sameTimestampDoesNotOverwrite() {
        val a = store().saveUser("One", sample, now = 5)
        val b = store().saveUser("Two", sample, now = 5)
        assertTrue(a.id != b.id)
        assertEquals(2, store().listUser().presets.size)
    }

    @Test
    fun namesAreValidated() {
        assertThrows(PresetStoreException::class.java) { store().saveUser("   ", sample) }
        assertThrows(PresetStoreException::class.java) { store().saveUser("x".repeat(41), sample) }
    }

    @Test
    fun deleteRejectsPathsAndFactoryIds() {
        assertThrows(PresetStoreException::class.java) { store().deleteUser("../current") }
        assertThrows(PresetStoreException::class.java) { store().deleteUser("factory.flat") }
        assertTrue(File(tmp.root, "current.senza").parentFile.exists())
    }

    @Test
    fun corruptFilesAreReportedNotHidden() {
        store().saveUser("Good", sample, now = 1)
        File(tmp.root, "presets").also { it.mkdirs() }
        File(tmp.root, "presets/user.2.senza").writeText("garbage")
        val listing = store().listUser()
        assertEquals(1, listing.presets.size)
        assertEquals(1, listing.problems.size)
        assertTrue(listing.problems[0].contains("user.2.senza"))
    }

    @Test
    fun corruptCurrentFileRaisesAReadableError() {
        File(tmp.root, "current.senza").writeText("nope")
        val e = assertThrows(PresetStoreException::class.java) { store().loadCurrent() }
        assertTrue(e.message!!.contains("current.senza"))
    }

    @Test
    fun profilesRoundTripPerDeviceType() {
        store().saveProfiles(mapOf(DeviceType.BLUETOOTH to sample, DeviceType.USB to EngineParameters(masterGainDb = -1f)))
        val back = store().loadProfiles()
        assertEquals(setOf(DeviceType.BLUETOOTH, DeviceType.USB), back.keys)
        assertEquals(sample.sanitized(), back[DeviceType.BLUETOOTH])
    }

    @Test
    fun profilesRestoreIntoDeviceProfiles() {
        val profiles = DeviceProfiles()
        profiles.restore(mapOf(DeviceType.USB to sample))
        assertEquals(sample.sanitized(), profiles.onRouteChanged(DeviceType.USB, EngineParameters()))
        assertEquals(1, profiles.snapshot().size)
    }
}
