package com.senzaproject.studio

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.senzaproject.studio.audio.engine.ActiveRoute
import com.senzaproject.studio.audio.engine.DeviceType
import com.senzaproject.studio.audio.engine.PlayerPhase
import com.senzaproject.studio.audio.engine.ProfileResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Bridges the engine and the platform layer to the UI. The UI only reads state and sends intents. */
class EngineViewModel(private val container: AppContainer) : ViewModel() {
    private val engine = container.engine

    val capabilities = engine.capabilitiesFlow
    val engineState = engine.state
    val parameters = engine.parameters
    val playerState = engine.playerState
    val diagnostics = engine.diagnostics
    val devices = container.deviceMonitor.devices
    val report = container.report
    val sessionEffects = container.sessionEffects

    /** Confirmed route while our own stream plays, otherwise Android's likely route (flagged as a guess). */
    val route: StateFlow<ActiveRoute> = combine(devices, engine.routedDeviceId) { list, routed ->
        ProfileResolver.pickActive(list, routed)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ActiveRoute(null, false))

    private val _autoSwitch = MutableStateFlow(container.profiles.autoSwitch)
    val autoSwitchProfiles: StateFlow<Boolean> = _autoSwitch.asStateFlow()

    private val _activeProfile = MutableStateFlow<DeviceType?>(null)
    val activeProfile: StateFlow<DeviceType?> = _activeProfile.asStateFlow()

    init {
        container.deviceMonitor.start()
        engine.start()
        viewModelScope.launch(Dispatchers.Default) { container.probePlatform() }
        viewModelScope.launch { devices.collect { container.onDevicesChanged() } }
        viewModelScope.launch {
            route.collect { r ->
                val next = container.profiles.onRouteChanged(r.device?.type, engine.parameters.value)
                _activeProfile.value = container.profiles.activeType
                if (next != null) engine.updateParameters { next }
            }
        }
        viewModelScope.launch { engine.parameters.collect { container.sessionEffects.applyParameters(it) } }
    }

    fun open(uri: Uri) = engine.open(uri, container.displayName(uri))

    fun togglePlayPause() {
        when (playerState.value.phase) {
            PlayerPhase.PLAYING -> engine.pause()
            PlayerPhase.PAUSED -> engine.resume()
            else -> Unit
        }
    }

    fun stopPlayback() = engine.stopPlayback()

    fun setSessionEffects(on: Boolean) {
        container.sessionEffects.setEnabled(on)
        if (on) container.sessionEffects.applyParameters(engine.parameters.value)
    }

    fun setAutoSwitch(on: Boolean) {
        container.profiles.autoSwitch = on
        _autoSwitch.value = on
    }

    override fun onCleared() {
        container.sessionEffects.setEnabled(false)
        container.deviceMonitor.stop()
        engine.stop()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as SenzaStudioApplication
                EngineViewModel(app.container)
            }
        }
    }
}
