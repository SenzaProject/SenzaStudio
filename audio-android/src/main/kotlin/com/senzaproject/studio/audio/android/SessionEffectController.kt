package com.senzaproject.studio.audio.android

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import com.senzaproject.studio.audio.engine.EngineParameters
import com.senzaproject.studio.audio.engine.SessionEffectPlan
import com.senzaproject.studio.audio.engine.SessionEffectPlanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * EXPERIMENTAL. Attaches the platform's DynamicsProcessing effect to audio sessions that other
 * players announce with ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION. It only sees players that send
 * that broadcast, only works while this app process is alive, and can apply a reduced plan
 * (see [SessionEffectPlanner]): graphic EQ approximation, input gain and a limiter. Bass, spatial,
 * imaging, compressor and loudness are NOT applied here, and the UI says so.
 */
class SessionEffectController(context: Context) {
    data class Session(val sessionId: Int, val packageName: String)

    private class Entry(val effect: DynamicsProcessing, val bandCount: Int, val packageName: String)

    private val appContext = context.applicationContext
    private val lock = Any()
    private val entries = HashMap<Int, Entry>()
    private var plan: SessionEffectPlan = SessionEffectPlanner.plan(EngineParameters())
    private var registered = false

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _plan = MutableStateFlow(plan)
    /** The plan currently applied; its [SessionEffectPlan.ignored] list is what the UI must disclose. */
    val currentPlan: StateFlow<SessionEffectPlan> = _plan.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val i = intent ?: return
            val id = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR_BAD_VALUE)
            val pkg = i.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME).orEmpty()
            if (id <= 0) return
            when (i.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> attach(id, pkg)
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> detach(id)
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun setEnabled(on: Boolean) {
        if (on == _enabled.value) return
        if (on) {
            val filter = IntentFilter().apply {
                addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
            registered = true
            _error.value = null
        } else {
            if (registered) {
                try { appContext.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) { }
                registered = false
            }
            releaseAll()
        }
        _enabled.value = on
    }

    fun applyParameters(parameters: EngineParameters) {
        val newPlan = SessionEffectPlanner.plan(parameters)
        synchronized(lock) {
            plan = newPlan
            val snapshot = entries.toMap()
            for ((id, entry) in snapshot) {
                if (entry.bandCount != newPlan.eqBands.size) {
                    release(id)
                    attachLocked(id, entry.packageName)
                } else {
                    try { apply(entry.effect, newPlan) } catch (e: Exception) { _error.value = describe(e) }
                }
            }
        }
        _plan.value = newPlan
    }

    private fun attach(sessionId: Int, packageName: String) = synchronized(lock) { attachLocked(sessionId, packageName) }

    private fun attachLocked(sessionId: Int, packageName: String) {
        if (entries.containsKey(sessionId)) return
        try {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2, true, plan.eqBands.size, false, 0, false, 0, true,
            ).build()
            val effect = DynamicsProcessing(0, sessionId, config)
            apply(effect, plan)
            effect.setEnabled(true)
            entries[sessionId] = Entry(effect, plan.eqBands.size, packageName)
            publishSessions()
        } catch (e: Exception) {
            _error.value = "Could not attach to $packageName: ${describe(e)}"
        }
    }

    private fun detach(sessionId: Int) = synchronized(lock) {
        release(sessionId)
        publishSessions()
    }

    private fun release(sessionId: Int) {
        val entry = entries.remove(sessionId) ?: return
        try { entry.effect.release() } catch (_: Exception) { }
    }

    private fun releaseAll() = synchronized(lock) {
        for (id in entries.keys.toList()) release(id)
        publishSessions()
    }

    private fun publishSessions() {
        _sessions.value = entries.map { (id, e) -> Session(id, e.packageName) }.sortedBy { it.sessionId }
    }

    private fun apply(effect: DynamicsProcessing, p: SessionEffectPlan) {
        for ((i, band) in p.eqBands.withIndex()) {
            effect.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, band.cutoffHz, band.gainDb))
        }
        effect.setInputGainAllChannelsTo(p.inputGainDb)
        effect.setLimiterAllChannelsTo(
            DynamicsProcessing.Limiter(
                true, true, 0, LIMITER_ATTACK_MS, p.limiterReleaseMs, LIMITER_RATIO, p.limiterThresholdDb, 0f,
            ),
        )
    }

    private fun describe(e: Exception) = e.message ?: e.javaClass.simpleName

    private companion object {
        const val LIMITER_ATTACK_MS = 1f
        const val LIMITER_RATIO = 10f
    }
}
