package com.senzaproject.studio.audio.engine

/**
 * Kind of audio output, which is also the key of the per-device profiles
 * (speaker, wired headphones, Bluetooth, USB DAC, external).
 */
enum class DeviceType(val displayName: String) {
    BUILTIN_SPEAKER("Built-in speaker"),
    EARPIECE("Earpiece"),
    WIRED_HEADPHONES("Wired headphones"),
    BLUETOOTH("Bluetooth"),
    USB("USB audio"),
    HDMI("HDMI / external display"),
    OTHER("Other output");

    companion object {
        /**
         * Maps android.media.AudioDeviceInfo.TYPE_* values. The numbers are the stable platform
         * constants; they are listed here (instead of referencing Android classes) so the mapping
         * stays in the pure-Kotlin module and is unit-tested.
         */
        fun fromAndroidType(type: Int): DeviceType = when (type) {
            2, 24 -> BUILTIN_SPEAKER            // BUILTIN_SPEAKER, BUILTIN_SPEAKER_SAFE
            1 -> EARPIECE                        // BUILTIN_EARPIECE
            3, 4, 5, 19 -> WIRED_HEADPHONES      // WIRED_HEADSET, WIRED_HEADPHONES, LINE_ANALOG, AUX_LINE
            7, 8, 26, 27, 30 -> BLUETOOTH        // BT_SCO, BT_A2DP, BLE_HEADSET, BLE_SPEAKER, BLE_BROADCAST
            11, 12, 22 -> USB                    // USB_DEVICE, USB_ACCESSORY, USB_HEADSET
            9, 10, 29 -> HDMI                    // HDMI, HDMI_ARC, HDMI_EARC
            else -> OTHER
        }
    }
}

/** One audio output as reported by the platform. */
data class OutputDevice(
    val id: Int,
    val type: DeviceType,
    val name: String,
    val sampleRates: List<Int> = emptyList(),
    val channelCounts: List<Int> = emptyList(),
)

/**
 * The route being used. [confirmed] is true only when the platform told us the device of one of
 * OUR OWN streams; for the system mix Android does not expose the route, so it is a best guess.
 */
data class ActiveRoute(val device: OutputDevice?, val confirmed: Boolean)

object ProfileResolver {
    /** Android normally prefers a connected external output over the built-in speaker. */
    private val priority = listOf(
        DeviceType.USB,
        DeviceType.BLUETOOTH,
        DeviceType.WIRED_HEADPHONES,
        DeviceType.HDMI,
        DeviceType.BUILTIN_SPEAKER,
        DeviceType.EARPIECE,
        DeviceType.OTHER, // unknown/internal buses (e.g. a phone's own "model name" output) rank last
    )

    fun pickActive(devices: List<OutputDevice>, routedDeviceId: Int?): ActiveRoute {
        val routed = routedDeviceId?.let { id -> devices.firstOrNull { it.id == id } }
        if (routed != null) return ActiveRoute(routed, confirmed = true)
        val guess = priority.firstNotNullOfOrNull { type -> devices.firstOrNull { it.type == type } }
        return ActiveRoute(guess, confirmed = false)
    }
}

/**
 * Remembers one parameter set per [DeviceType] (in memory; persistence arrives with presets).
 * When auto-switching is on and the active device type changes, the parameters of the old type are
 * saved and the saved ones of the new type are returned (or null if there are none yet, in which
 * case the current parameters simply carry over).
 */
class DeviceProfiles(var autoSwitch: Boolean = true) {
    private val saved = HashMap<DeviceType, EngineParameters>()
    private var active: DeviceType? = null

    val activeType: DeviceType? get() = active

    fun hasProfile(type: DeviceType): Boolean = saved.containsKey(type)

    /** Copy of all saved profiles, e.g. for persisting them. */
    fun snapshot(): Map<DeviceType, EngineParameters> = HashMap(saved)

    /** Replaces the saved profiles (e.g. after reading them from storage); does not change the active type. */
    fun restore(profiles: Map<DeviceType, EngineParameters>) {
        saved.clear()
        for ((type, p) in profiles) saved[type] = p.sanitized()
    }

    fun save(type: DeviceType, parameters: EngineParameters) {
        saved[type] = parameters.sanitized()
    }

    /** Returns parameters to apply, or null when nothing should change. */
    fun onRouteChanged(newType: DeviceType?, current: EngineParameters): EngineParameters? {
        val old = active
        if (newType == old) return null
        if (old != null) saved[old] = current.sanitized()
        active = newType
        if (!autoSwitch || newType == null) return null
        return saved[newType]
    }
}
