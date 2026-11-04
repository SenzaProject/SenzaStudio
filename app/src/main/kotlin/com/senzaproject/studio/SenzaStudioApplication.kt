package com.senzaproject.studio

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.senzaproject.studio.audio.android.DeviceMonitor
import com.senzaproject.studio.audio.android.LiveAudioEngine
import com.senzaproject.studio.audio.android.PlatformProbe
import com.senzaproject.studio.audio.android.SessionEffectController
import com.senzaproject.studio.audio.engine.CompatibilityEvaluator
import com.senzaproject.studio.audio.engine.CompatibilityReport
import com.senzaproject.studio.audio.engine.DeviceProfiles
import com.senzaproject.studio.audio.engine.PlatformFacts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Manual dependency container (no DI framework: fewer dependencies, easier to audit). */
class AppContainer(private val context: Context) {
    val deviceMonitor = DeviceMonitor(context)
    val sessionEffects = SessionEffectController(context)
    val profiles = DeviceProfiles()

    private var facts = PlatformFacts(sdkInt = Build.VERSION.SDK_INT, devices = deviceMonitor.devices.value)
    private val _report = MutableStateFlow(CompatibilityEvaluator.evaluate(facts))
    val report: StateFlow<CompatibilityReport> = _report.asStateFlow()

    val engine = LiveAudioEngine(context, _report.value, deviceMonitor.devices)

    /** Runs the real platform probes (creates and releases a test effect); call off the main thread. */
    fun probePlatform() {
        facts = PlatformProbe.collect(context, deviceMonitor.devices.value)
        publish()
    }

    /** Re-evaluates the report when the connected devices change. */
    fun onDevicesChanged() {
        facts = facts.copy(devices = deviceMonitor.devices.value)
        publish()
    }

    private fun publish() {
        val r = CompatibilityEvaluator.evaluate(facts)
        _report.value = r
        engine.updateReport(r)
    }

    fun displayName(uri: Uri): String {
        var name: String? = null
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        } catch (_: Exception) {
            // Fall back to the last path segment below.
        }
        return name ?: uri.lastPathSegment ?: "Audio file"
    }
}

class SenzaStudioApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
