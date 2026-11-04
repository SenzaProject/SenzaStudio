package com.senzaproject.studio.audio.engine

/** The ways audio could be processed on Android, from most to least widely available. */
enum class ProcessingPath(val displayName: String) {
    APP_PLAYER("Built-in player"),
    SESSION_EFFECT("Effects on other apps' sessions"),
    GLOBAL_MIX("Global output mix (session 0)"),
    SYSTEM_PRIVILEGED("System-wide (OEM / root)"),
}

/** Result of trying to create a platform feature on this device. */
sealed interface ProbeResult {
    data object Supported : ProbeResult
    data object NotTried : ProbeResult
    data class Failed(val reason: String) : ProbeResult
}

/** Everything the evaluator needs, gathered by the Android layer. Pure data: easy to test. */
data class PlatformFacts(
    val sdkInt: Int,
    val manufacturer: String = "",
    val model: String = "",
    val lowLatencyFeature: Boolean = false,
    val proAudioFeature: Boolean = false,
    val nativeSampleRateHz: Int? = null,
    val nativeFramesPerBuffer: Int? = null,
    val dynamicsProcessing: ProbeResult = ProbeResult.NotTried,
    val devices: List<OutputDevice> = emptyList(),
)

data class PathCapability(val path: ProcessingPath, val availability: Availability)

data class CompatibilityReport(
    val facts: PlatformFacts,
    val paths: List<PathCapability>,
    /** Plain-language caveats that apply to this device right now. */
    val notes: List<String>,
) {
    fun of(path: ProcessingPath): Availability =
        paths.firstOrNull { it.path == path }?.availability ?: Availability.Unavailable("Not evaluated")
}

object CompatibilityEvaluator {
    private const val QA_NOTE =
        "Applies to audio played by Senza Studio's own player. Not yet validated across devices (QA is Phase 6/7)."

    fun evaluate(facts: PlatformFacts): CompatibilityReport {
        val session: Availability = when (val probe = facts.dynamicsProcessing) {
            ProbeResult.Supported -> Availability.Experimental(
                "Only works with players that announce their audio session to effect apps, and only while this app " +
                    "is running. Limited to an EQ approximation, input gain and a limiter.",
            )
            ProbeResult.NotTried -> Availability.Unavailable("Not probed yet")
            is ProbeResult.Failed -> Availability.Unavailable("Device rejected the effect: ${probe.reason}")
        }
        val paths = listOf(
            PathCapability(ProcessingPath.APP_PLAYER, Availability.Experimental(QA_NOTE)),
            PathCapability(ProcessingPath.SESSION_EFFECT, session),
            PathCapability(
                ProcessingPath.GLOBAL_MIX,
                Availability.Unavailable("Effects on the global mix (session 0) are deprecated and ignored by modern Android"),
            ),
            PathCapability(
                ProcessingPath.SYSTEM_PRIVILEGED,
                Availability.Unavailable("Needs OEM/system privileges or root; not part of the standard app"),
            ),
        )

        val notes = ArrayList<String>()
        if (!facts.lowLatencyFeature) notes += "This device does not advertise low-latency audio; expect larger buffers."
        if (facts.devices.any { it.type == DeviceType.BLUETOOTH }) {
            notes += "Bluetooth adds codec and transport latency that the app cannot measure."
        }
        if (facts.devices.isEmpty()) notes += "No output device was reported by Android."
        return CompatibilityReport(facts, paths, notes)
    }

    /** Engine capabilities derived from the report; never claims more than the report supports. */
    fun capabilities(report: CompatibilityReport): EngineCapabilities {
        val live = report.of(ProcessingPath.APP_PLAYER) !is Availability.Unavailable
        val note = (report.of(ProcessingPath.APP_PLAYER) as? Availability.Experimental)?.note ?: QA_NOTE
        return EngineCapabilities(
            scope = if (live) ProcessingScope.APP_LOCAL else ProcessingScope.NONE,
            modules = DspModule.entries.map { module ->
                val availability: Availability = when {
                    module == DspModule.CLARITY -> Availability.Unavailable("Not implemented yet")
                    module == DspModule.SPECTRUM -> Availability.Unavailable("Live spectrum arrives in Phase 5")
                    live -> Availability.Experimental(note)
                    else -> Availability.Unavailable("The built-in player is unavailable")
                }
                ModuleCapability(module, availability)
            },
        )
    }
}
