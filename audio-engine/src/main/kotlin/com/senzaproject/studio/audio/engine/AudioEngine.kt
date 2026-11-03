package com.senzaproject.studio.audio.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class EngineStatus { STOPPED, RUNNING, FAILED }

data class EngineState(
    val status: EngineStatus,
    /** True only when audio is really being modified by a DSP module. */
    val isProcessingAudio: Boolean,
    val message: String? = null,
)

/**
 * The only surface the UI may use to talk to audio processing.
 * Implementations must keep the audio callback free of allocation and locks;
 * parameters reach it through [parameters] snapshots.
 */
interface AudioEngine {
    val capabilities: EngineCapabilities
    val state: StateFlow<EngineState>
    val parameters: StateFlow<EngineParameters>

    fun start()
    fun stop()
    fun updateParameters(transform: (EngineParameters) -> EngineParameters)
}

/**
 * Phase 1 engine: owns lifecycle and parameter state but contains NO DSP and
 * touches no audio. It reports exactly that, so the UI cannot pretend otherwise.
 * Replaced by the real engine in Phase 2 (Core DSP).
 */
class NoOpAudioEngine(
    private val store: ParameterStore = ParameterStore(),
) : AudioEngine {

    override val capabilities: EngineCapabilities = EngineCapabilities(
        scope = ProcessingScope.NONE,
        modules = DspModule.entries.map {
            ModuleCapability(it, Availability.Unavailable(reasonFor(it)))
        },
    )

    private val _state = MutableStateFlow(EngineState(EngineStatus.STOPPED, false))
    override val state: StateFlow<EngineState> = _state.asStateFlow()
    override val parameters: StateFlow<EngineParameters> = store.state

    override fun start() {
        _state.update {
            EngineState(EngineStatus.RUNNING, false, "No DSP modules are implemented yet; audio is untouched.")
        }
    }

    override fun stop() {
        _state.update { EngineState(EngineStatus.STOPPED, false) }
    }

    override fun updateParameters(transform: (EngineParameters) -> EngineParameters) = store.update(transform)

    private fun reasonFor(module: DspModule): String = when (module) {
        DspModule.EQUALIZER, DspModule.DYNAMICS, DspModule.PROTECTION ->
            "DSP exists in :audio-dsp but is not attached to a live audio path until Phase 4"
        DspModule.BASS, DspModule.STEREO_IMAGING, DspModule.SPATIAL, DspModule.CLARITY -> "Planned for Phase 3 (Advanced DSP)"
        DspModule.SPECTRUM -> "Planned for Phase 5 (Premium UI)"
    }
}
