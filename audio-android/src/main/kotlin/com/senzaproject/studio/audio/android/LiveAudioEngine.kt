package com.senzaproject.studio.audio.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import com.senzaproject.studio.audio.dsp.DspChain
import com.senzaproject.studio.audio.engine.AudioEngine
import com.senzaproject.studio.audio.engine.CompatibilityEvaluator
import com.senzaproject.studio.audio.engine.CompatibilityReport
import com.senzaproject.studio.audio.engine.EngineCapabilities
import com.senzaproject.studio.audio.engine.EngineDiagnostics
import com.senzaproject.studio.audio.engine.EngineParameters
import com.senzaproject.studio.audio.engine.EngineState
import com.senzaproject.studio.audio.engine.EngineStatus
import com.senzaproject.studio.audio.engine.OutputDevice
import com.senzaproject.studio.audio.engine.ParameterStore
import com.senzaproject.studio.audio.engine.PlayerPhase
import com.senzaproject.studio.audio.engine.PlayerState
import com.senzaproject.studio.audio.engine.ProfileResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The live engine: the built-in [LocalPlayer] feeding [DspChain] into an AudioTrack, plus the audio
 * session strategy of a well-behaved music app:
 *  - requests audio focus before playing; pauses on a transient loss, ducks when asked to, stops
 *    on a permanent loss and resumes after a transient loss only if it was playing;
 *  - pauses when headphones are unplugged or the Bluetooth output disappears (BECOMING_NOISY);
 *  - reports capabilities from the real platform probe, never more.
 * Parameters are shared with the DSP thread through the lock-free [DspChain.configure] hand-off.
 */
class LiveAudioEngine(
    context: Context,
    report: CompatibilityReport,
    private val devices: StateFlow<List<OutputDevice>>,
    private val store: ParameterStore = ParameterStore(),
) : AudioEngine {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _capabilities = MutableStateFlow(CompatibilityEvaluator.capabilities(report))
    val capabilitiesFlow: StateFlow<EngineCapabilities> = _capabilities.asStateFlow()
    override val capabilities: EngineCapabilities get() = _capabilities.value

    private val _state = MutableStateFlow(EngineState(EngineStatus.STOPPED, false))
    override val state: StateFlow<EngineState> = _state.asStateFlow()
    override val parameters: StateFlow<EngineParameters> = store.state

    private val _player = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _player.asStateFlow()

    private val _diagnostics = MutableStateFlow(EngineDiagnostics())
    val diagnostics: StateFlow<EngineDiagnostics> = _diagnostics.asStateFlow()

    private val _routedDeviceId = MutableStateFlow<Int?>(null)
    /** Device id the engine's own stream is routed to (confirmed by the platform), or null when not playing. */
    val routedDeviceId: StateFlow<Int?> = _routedDeviceId.asStateFlow()

    private val playerListener = object : LocalPlayer.Listener {
        override fun onPlayerState(state: PlayerState) = handlePlayerState(state)
        override fun onDiagnostics(diagnostics: EngineDiagnostics, routedDeviceId: Int?) =
            handleDiagnostics(diagnostics, routedDeviceId)
        override fun onChain(chain: DspChain?) = handleChain(chain)
    }

    private val player = LocalPlayer(appContext, { store.state.value }, playerListener)
    @Volatile private var chain: DspChain? = null
    @Volatile private var resumeOnFocusGain = false
    private var started = false

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change -> onFocusChange(change) }
        .build()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    // ---- AudioEngine ----

    override fun start() {
        if (started) return
        started = true
        appContext.registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        publishEngineState()
    }

    override fun stop() {
        stopPlayback()
        if (started) {
            started = false
            try { appContext.unregisterReceiver(noisyReceiver) } catch (_: IllegalArgumentException) { }
        }
        _state.value = EngineState(EngineStatus.STOPPED, false)
    }

    override fun updateParameters(transform: (EngineParameters) -> EngineParameters) {
        store.update(transform)
        chain?.configure(store.state.value)
    }

    fun updateReport(report: CompatibilityReport) {
        _capabilities.value = CompatibilityEvaluator.capabilities(report)
    }

    // ---- transport ----

    fun open(uri: Uri, title: String) {
        if (!started) start()
        resumeOnFocusGain = false
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            _player.value = PlayerState(PlayerPhase.ERROR, title, error = "Another app is holding audio focus; try again.")
            return
        }
        player.open(uri, title)
    }

    fun pause() {
        if (player.isActive) player.pause()
    }

    fun resume() {
        if (player.isActive && player.isPaused) {
            if (audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) player.resume()
        }
    }

    fun stopPlayback() {
        resumeOnFocusGain = false
        player.stop()
        audioManager.abandonAudioFocusRequest(focusRequest)
        _routedDeviceId.value = null
        publishEngineState()
    }

    // ---- audio focus ----

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                stopPlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (player.isActive && !player.isPaused) {
                    resumeOnFocusGain = true
                    player.pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player.setDucked(true)
            AudioManager.AUDIOFOCUS_GAIN -> {
                player.setDucked(false)
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    player.resume()
                }
            }
        }
    }

    // ---- player callbacks (called from the player thread) ----

    private fun handlePlayerState(state: PlayerState) {
        _player.value = state
        publishEngineState()
    }

    private fun handleDiagnostics(diagnostics: EngineDiagnostics, routedDeviceId: Int?) {
        _routedDeviceId.value = routedDeviceId
        _diagnostics.value = diagnostics.copy(route = ProfileResolver.pickActive(devices.value, routedDeviceId))
    }

    private fun handleChain(chain: DspChain?) {
        this.chain = chain
        if (chain == null) _routedDeviceId.value = null
    }

    private fun publishEngineState() {
        val p = _player.value
        val dspBypassed = _diagnostics.value.dspFailures > 0 && p.phase == PlayerPhase.PLAYING
        _state.update {
            when {
                !started -> EngineState(EngineStatus.STOPPED, false)
                dspBypassed -> EngineState(EngineStatus.FAILED, false, "DSP failed and was bypassed; audio plays unprocessed.")
                p.phase == PlayerPhase.PLAYING -> EngineState(EngineStatus.RUNNING, true, "Processing the built-in player's audio.")
                p.phase == PlayerPhase.ERROR -> EngineState(EngineStatus.RUNNING, false, p.error)
                else -> EngineState(EngineStatus.RUNNING, false, "Ready. Open a file to process audio with the built-in player.")
            }
        }
    }
}
