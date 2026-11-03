package com.senzaproject.studio.audio.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Thread-safe holder of [EngineParameters]. Updates are atomic (compare-and-set
 * loop) and always sanitized, so any thread may call [update] at any time.
 * Readers (UI or, later, the DSP thread) observe immutable snapshots.
 */
class ParameterStore(initial: EngineParameters = EngineParameters()) {
    private val _state = MutableStateFlow(initial.sanitized())
    val state: StateFlow<EngineParameters> = _state.asStateFlow()

    fun update(transform: (EngineParameters) -> EngineParameters) {
        _state.update { transform(it).sanitized() }
    }

    fun reset() {
        _state.value = EngineParameters()
    }
}
