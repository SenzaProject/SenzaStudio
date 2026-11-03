package com.senzaproject.studio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.senzaproject.studio.audio.engine.AudioEngine

/** Bridges the engine to the UI. The UI only reads state and sends parameter updates. */
class EngineViewModel(private val engine: AudioEngine) : ViewModel() {
    val capabilities = engine.capabilities
    val engineState = engine.state
    val parameters = engine.parameters

    init {
        engine.start()
    }

    override fun onCleared() {
        engine.stop()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as SenzaStudioApplication
                EngineViewModel(app.container.engine)
            }
        }
    }
}
