package com.senzaproject.studio.audio.engine

/** Processing modules of the product brief. */
enum class DspModule(val displayName: String) {
    EQUALIZER("Equalizer"),
    SPATIAL("Spatial 360"),
    DYNAMICS("Dynamic Master"),
    BASS("Bass"),
    CLARITY("Clarity"),
    STEREO_IMAGING("Stereo Imaging"),
    PROTECTION("Output Protection"),
    SPECTRUM("Spectrum Lab"),
}

/** Honest availability of a module on this build/device. */
sealed interface Availability {
    data object Available : Availability
    data class Experimental(val note: String) : Availability
    data class Unavailable(val reason: String) : Availability
}

data class ModuleCapability(val module: DspModule, val availability: Availability)

/** Where processing can actually take effect. Never claim more than is tested. */
enum class ProcessingScope(val description: String) {
    NONE("No audio is processed"),
    APP_LOCAL("Audio sessions owned by this app only"),
    SYSTEM_WIDE("System-wide (requires OEM/root integration)"),
}

data class EngineCapabilities(
    val scope: ProcessingScope,
    val modules: List<ModuleCapability>,
) {
    fun of(module: DspModule): ModuleCapability =
        modules.firstOrNull { it.module == module }
            ?: ModuleCapability(module, Availability.Unavailable("Not reported by engine"))
}
