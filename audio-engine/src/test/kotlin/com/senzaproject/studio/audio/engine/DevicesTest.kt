package com.senzaproject.studio.audio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicesTest {
    private fun dev(id: Int, type: DeviceType) = OutputDevice(id, type, "dev$id")

    @Test
    fun androidTypesMapToTheFiveProfileGroups() {
        assertEquals(DeviceType.BUILTIN_SPEAKER, DeviceType.fromAndroidType(2))
        assertEquals(DeviceType.BUILTIN_SPEAKER, DeviceType.fromAndroidType(24))
        assertEquals(DeviceType.EARPIECE, DeviceType.fromAndroidType(1))
        for (t in listOf(3, 4, 5, 19)) assertEquals(DeviceType.WIRED_HEADPHONES, DeviceType.fromAndroidType(t))
        for (t in listOf(7, 8, 26, 27, 30)) assertEquals(DeviceType.BLUETOOTH, DeviceType.fromAndroidType(t))
        for (t in listOf(11, 12, 22)) assertEquals(DeviceType.USB, DeviceType.fromAndroidType(t))
        for (t in listOf(9, 10, 29)) assertEquals(DeviceType.HDMI, DeviceType.fromAndroidType(t))
        assertEquals(DeviceType.OTHER, DeviceType.fromAndroidType(9999))
    }

    @Test
    fun routedDeviceIsConfirmedWhenPresent() {
        val devices = listOf(dev(1, DeviceType.BUILTIN_SPEAKER), dev(2, DeviceType.BLUETOOTH))
        val route = ProfileResolver.pickActive(devices, routedDeviceId = 1)
        assertEquals(1, route.device?.id)
        assertTrue(route.confirmed)
    }

    @Test
    fun withoutAConfirmedRouteTheBestGuessIsFlaggedAsUnconfirmed() {
        val devices = listOf(dev(1, DeviceType.BUILTIN_SPEAKER), dev(2, DeviceType.BLUETOOTH), dev(3, DeviceType.USB))
        val route = ProfileResolver.pickActive(devices, routedDeviceId = null)
        assertEquals(DeviceType.USB, route.device?.type)
        assertFalse(route.confirmed)
        assertEquals(DeviceType.BLUETOOTH, ProfileResolver.pickActive(devices.take(2), null).device?.type)
        assertEquals(DeviceType.BUILTIN_SPEAKER, ProfileResolver.pickActive(devices.take(1), null).device?.type)
    }

    @Test
    fun staleRoutedIdFallsBackToAGuess() {
        val route = ProfileResolver.pickActive(listOf(dev(1, DeviceType.BUILTIN_SPEAKER)), routedDeviceId = 77)
        assertFalse(route.confirmed)
        assertEquals(1, route.device?.id)
    }

    @Test
    fun noDevicesMeansNoRoute() {
        val route = ProfileResolver.pickActive(emptyList(), null)
        assertNull(route.device)
        assertFalse(route.confirmed)
    }

    @Test
    fun profilesAreSavedPerDeviceTypeAndRestoredOnSwitch() {
        val profiles = DeviceProfiles()
        val speaker = EngineParameters(masterGainDb = -3f)
        val headphones = EngineParameters(masterGainDb = -6f)

        assertNull(profiles.onRouteChanged(DeviceType.BUILTIN_SPEAKER, speaker)) // first route: nothing saved yet
        assertNull(profiles.onRouteChanged(DeviceType.WIRED_HEADPHONES, speaker)) // speaker saved, no headphone profile
        val back = profiles.onRouteChanged(DeviceType.BUILTIN_SPEAKER, headphones)
        assertEquals(-3f, back?.masterGainDb)
        assertEquals(-6f, profiles.onRouteChanged(DeviceType.WIRED_HEADPHONES, speaker)?.masterGainDb)
    }

    @Test
    fun unchangedRouteAndDisabledAutoSwitchChangeNothing() {
        val profiles = DeviceProfiles()
        profiles.onRouteChanged(DeviceType.USB, EngineParameters())
        assertNull(profiles.onRouteChanged(DeviceType.USB, EngineParameters(masterGainDb = 2f)))

        val manual = DeviceProfiles(autoSwitch = false)
        manual.save(DeviceType.BLUETOOTH, EngineParameters(masterGainDb = -9f))
        manual.onRouteChanged(DeviceType.USB, EngineParameters())
        assertNull(manual.onRouteChanged(DeviceType.BLUETOOTH, EngineParameters()))
        assertTrue(manual.hasProfile(DeviceType.BLUETOOTH))
    }
}
