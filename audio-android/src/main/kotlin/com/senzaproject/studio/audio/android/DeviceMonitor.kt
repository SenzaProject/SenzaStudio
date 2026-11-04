package com.senzaproject.studio.audio.android

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.senzaproject.studio.audio.engine.OutputDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Publishes the connected audio OUTPUT devices and updates when one is plugged in or removed.
 * Android does not say which of them the system mix is currently using; see
 * [com.senzaproject.studio.audio.engine.ProfileResolver] for how that is reported honestly.
 */
class DeviceMonitor(context: Context) {
    private val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val _devices = MutableStateFlow(snapshot())
    val devices: StateFlow<List<OutputDevice>> = _devices.asStateFlow()
    private var registered = false

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
    }

    fun start() {
        if (registered) return
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        registered = true
        refresh()
    }

    fun stop() {
        if (!registered) return
        audio.unregisterAudioDeviceCallback(callback)
        registered = false
    }

    fun refresh() {
        _devices.value = snapshot()
    }

    private fun snapshot(): List<OutputDevice> =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }.map { it.toOutputDevice() }
}
