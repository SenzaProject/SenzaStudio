package com.senzaproject.studio

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.senzaproject.studio.audio.dsp.EqResponse
import com.senzaproject.studio.audio.engine.ActiveRoute
import com.senzaproject.studio.audio.engine.DeviceType
import com.senzaproject.studio.audio.engine.EngineParameters
import com.senzaproject.studio.audio.engine.EngineStatus
import com.senzaproject.studio.audio.engine.FactoryPresets
import com.senzaproject.studio.audio.engine.PlayerPhase
import com.senzaproject.studio.audio.engine.Preset
import com.senzaproject.studio.audio.engine.PresetStoreException
import com.senzaproject.studio.audio.engine.ProfileResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A short message for the user; errors stay until dismissed, confirmations fade out. */
data class UiMessage(val text: String, val isError: Boolean)

/** Bridges the engine and the platform layer to the UI. The UI only reads state and sends intents. */
class EngineViewModel(private val container: AppContainer) : ViewModel() {
    private val engine = container.engine
    private val store = container.presetStore

    val capabilities = engine.capabilitiesFlow
    val engineState = engine.state
    val parameters = engine.parameters
    val playerState = engine.playerState
    val diagnostics = engine.diagnostics
    val meters = engine.meters
    val devices = container.deviceMonitor.devices
    val report = container.report
    val sessionEffects = container.sessionEffects

    /** Confirmed route while our own stream plays, otherwise Android's likely route (flagged as a guess). */
    val route: StateFlow<ActiveRoute> = combine(devices, engine.routedDeviceId) { list, routed ->
        ProfileResolver.pickActive(list, routed)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ActiveRoute(null, false))

    /** Equalizer response for the graph, recomputed only when the EQ settings change. */
    val eqCurve: StateFlow<FloatArray> = parameters.map { it.eq }.distinctUntilChanged()
        .map { EqResponse.curveDb(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, EqResponse.curveDb(parameters.value.eq))

    private val _autoSwitch = MutableStateFlow(container.profiles.autoSwitch)
    val autoSwitchProfiles: StateFlow<Boolean> = _autoSwitch.asStateFlow()

    private val _activeProfile = MutableStateFlow<DeviceType?>(null)
    val activeProfile: StateFlow<DeviceType?> = _activeProfile.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    private val _userPresets = MutableStateFlow<List<Preset>>(emptyList())
    val userPresets: StateFlow<List<Preset>> = _userPresets.asStateFlow()

    private val _presetProblems = MutableStateFlow<List<String>>(emptyList())
    val presetProblems: StateFlow<List<String>> = _presetProblems.asStateFlow()

    val factoryPresets: List<Preset> = FactoryPresets.all

    /** The preset whose parameters equal the current settings (so the UI can mark it active), if any. */
    val appliedPresetId: StateFlow<String?> = combine(parameters, _userPresets) { current, user ->
        (factoryPresets + user).firstOrNull { it.parameters == current.sanitized() }?.id
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        container.deviceMonitor.start()
        engine.start()
        viewModelScope.launch(Dispatchers.Default) { container.probePlatform() }
        viewModelScope.launch { devices.collect { container.onDevicesChanged() } }
        viewModelScope.launch { engine.parameters.collect { container.sessionEffects.applyParameters(it) } }
        viewModelScope.launch {
            playerState.collect { s ->
                if (s.phase == PlayerPhase.ERROR) post("Playback problem: ${s.error ?: "unknown error"}", error = true)
            }
        }
        viewModelScope.launch {
            engineState.collect { s ->
                if (s.status == EngineStatus.FAILED) post(s.message ?: "The audio engine failed", error = true)
            }
        }
        viewModelScope.launch {
            container.sessionEffects.error.collect { e -> if (e != null) post(e, error = true) }
        }
        viewModelScope.launch {
            restoreSavedState()
            // These start only after the restore, so defaults can never overwrite what was saved.
            launch { followRoute() }
            launch { autosave() }
        }
    }

    // ---- state restore / autosave ----

    private class Restored(
        val current: EngineParameters?,
        val profiles: Map<DeviceType, EngineParameters>,
        val presets: List<Preset>,
        val problems: List<String>,
        val error: String?,
    )

    private suspend fun restoreSavedState() {
        val restored = withContext(Dispatchers.IO) {
            var error: String? = null
            val current = try {
                store.loadCurrent()
            } catch (e: PresetStoreException) {
                error = "Could not restore your last settings (${e.message})"
                null
            }
            val listing = store.listUser()
            Restored(current, store.loadProfiles(), listing.presets, listing.problems, error)
        }
        if (restored.current != null) engine.updateParameters { restored.current }
        container.profiles.restore(restored.profiles)
        _userPresets.value = restored.presets
        _presetProblems.value = restored.problems
        restored.error?.let { post(it, error = true) }
    }

    private suspend fun followRoute() {
        route.collect { r ->
            val next = container.profiles.onRouteChanged(r.device?.type, engine.parameters.value)
            _activeProfile.value = container.profiles.activeType
            if (next != null) engine.updateParameters { next }
        }
    }

    private suspend fun autosave() {
        engine.parameters.collectLatest { p ->
            delay(AUTOSAVE_DELAY_MS)
            val active = container.profiles.activeType
            if (container.profiles.autoSwitch && active != null) container.profiles.save(active, p)
            val profiles = container.profiles.snapshot()
            val failure = withContext(Dispatchers.IO) {
                try {
                    store.saveCurrent(p)
                    store.saveProfiles(profiles)
                    null
                } catch (e: PresetStoreException) {
                    e.message
                }
            }
            if (failure != null) post("Settings could not be saved: $failure", error = true)
        }
    }

    // ---- intents ----

    fun update(transform: (EngineParameters) -> EngineParameters) = engine.updateParameters(transform)

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

    fun resetAll() {
        engine.updateParameters { EngineParameters() }
        post("All settings were reset", error = false)
    }

    fun applyPreset(preset: Preset) {
        engine.updateParameters { preset.parameters }
        post("Applied \"${preset.name}\"", error = false)
    }

    fun savePreset(name: String) {
        val snapshot = engine.parameters.value
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { store.saveUser(name, snapshot) }
                refreshPresets()
                post("Saved \"${saved.name}\"", error = false)
            } catch (e: PresetStoreException) {
                post(e.message ?: "Could not save the preset", error = true)
            }
        }
    }

    fun deletePreset(preset: Preset) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.deleteUser(preset.id) }
                refreshPresets()
                post("Deleted \"${preset.name}\"", error = false)
            } catch (e: PresetStoreException) {
                post(e.message ?: "Could not delete the preset", error = true)
            }
        }
    }

    fun dismissMessage() {
        _message.value = null
    }

    private suspend fun refreshPresets() {
        val listing = withContext(Dispatchers.IO) { store.listUser() }
        _userPresets.value = listing.presets
        _presetProblems.value = listing.problems
    }

    private fun post(text: String, error: Boolean) {
        _message.value = UiMessage(text, error)
    }

    override fun onCleared() {
        container.sessionEffects.setEnabled(false)
        container.deviceMonitor.stop()
        engine.stop()
    }

    companion object {
        private const val AUTOSAVE_DELAY_MS = 500L

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as SenzaStudioApplication
                EngineViewModel(app.container)
            }
        }
    }
}
