package com.senzaproject.studio.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.senzaproject.studio.audio.engine.Availability
import com.senzaproject.studio.audio.engine.CompatibilityReport
import com.senzaproject.studio.audio.engine.DeviceType
import com.senzaproject.studio.audio.engine.EngineCapabilities
import com.senzaproject.studio.audio.engine.OutputDevice
import com.senzaproject.studio.audio.engine.SessionEffectPlan
import com.senzaproject.studio.core.design.SectionLabel
import com.senzaproject.studio.core.design.SenzaCard
import com.senzaproject.studio.core.design.SenzaColors
import com.senzaproject.studio.core.design.SenzaScreen
import com.senzaproject.studio.core.design.StatusChip
import com.senzaproject.studio.core.design.StatusTone

data class AppInfo(val versionName: String, val androidApi: Int)

/** State of the experimental "effects on other apps' sessions" path. */
data class SessionEffectsUi(
    val enabled: Boolean,
    val attached: List<String>,
    val plan: SessionEffectPlan,
    val error: String?,
)

data class SettingsUi(
    val info: AppInfo,
    val report: CompatibilityReport,
    val capabilities: EngineCapabilities,
    val devices: List<OutputDevice>,
    val sessionEffects: SessionEffectsUi,
    val autoSwitchProfiles: Boolean,
    val activeProfile: DeviceType?,
)

@Composable
fun SettingsScreen(
    ui: SettingsUi,
    onSessionEffectsChange: (Boolean) -> Unit,
    onAutoSwitchChange: (Boolean) -> Unit,
) {
    SenzaScreen(title = "SETTINGS", subtitle = "Compatibility and diagnostics") {
        SenzaCard {
            SectionLabel("Processing paths on this device")
            ui.report.paths.forEach { path ->
                val (label, tone, detail) = when (val a = path.availability) {
                    is Availability.Available -> Triple("Available", StatusTone.Positive, null)
                    is Availability.Experimental -> Triple("Experimental", StatusTone.Caution, a.note)
                    is Availability.Unavailable -> Triple("Not available", StatusTone.Muted, a.reason)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(path.path.displayName, style = MaterialTheme.typography.titleMedium, color = SenzaColors.TextPrimary)
                    StatusChip(label, tone)
                }
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = SenzaColors.TextMuted)
            }
            Line("Engine scope", ui.capabilities.scope.description)
            Text(
                "Senza Studio never claims system-wide processing unless it is implemented and tested on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = SenzaColors.TextMuted,
            )
        }

        SenzaCard {
            SectionLabel("Device facts")
            val f = ui.report.facts
            Line("Device", listOf(f.manufacturer, f.model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "unknown" })
            Line("Low-latency audio feature", yesNo(f.lowLatencyFeature))
            Line("Pro audio feature", yesNo(f.proAudioFeature))
            Line("Native output rate", f.nativeSampleRateHz?.let { "$it Hz" } ?: "unknown")
            Line("Native buffer", f.nativeFramesPerBuffer?.let { "$it frames" } ?: "unknown")
            ui.report.notes.forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = SenzaColors.TextMuted)
            }
        }

        SenzaCard {
            SectionLabel("Output devices")
            if (ui.devices.isEmpty()) {
                Text("None reported", style = MaterialTheme.typography.bodyMedium, color = SenzaColors.TextMuted)
            }
            ui.devices.forEach { d ->
                val rates = if (d.sampleRates.isEmpty()) "any rate" else d.sampleRates.joinToString("/") { "${it / 1000.0} kHz" }
                Line(d.name, "${d.type.displayName}, $rates")
            }
        }

        SenzaCard {
            SectionLabel("Device profiles")
            SwitchRow("Switch settings per output type", ui.autoSwitchProfiles, onAutoSwitchChange)
            Line("Active profile", ui.activeProfile?.displayName ?: "none")
            Text(
                "Each output type (speaker, wired, Bluetooth, USB, HDMI) remembers its own settings for this session. " +
                    "Saving them across restarts arrives with presets.",
                style = MaterialTheme.typography.bodyMedium,
                color = SenzaColors.TextMuted,
            )
        }

        SenzaCard {
            SectionLabel("Experimental: other apps' audio")
            SwitchRow("Apply effects to apps that announce their session", ui.sessionEffects.enabled, onSessionEffectsChange)
            Text(
                "Works only with players that send the audio-effect session broadcast, only while Senza Studio is running, " +
                    "and only for a reduced chain: EQ approximation, input gain and a limiter.",
                style = MaterialTheme.typography.bodyMedium,
                color = SenzaColors.TextMuted,
            )
            if (ui.sessionEffects.enabled) {
                Line(
                    "Attached sessions",
                    if (ui.sessionEffects.attached.isEmpty()) "none yet" else ui.sessionEffects.attached.joinToString(),
                )
                if (ui.sessionEffects.plan.ignored.isNotEmpty()) {
                    Text(
                        "Enabled but NOT applied here: " + ui.sessionEffects.plan.ignored.joinToString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SenzaColors.Warning,
                    )
                }
            }
            ui.sessionEffects.error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = SenzaColors.Danger)
            }
        }

        SenzaCard {
            SectionLabel("About")
            Line("Version", ui.info.versionName)
            Line("Android API", ui.info.androidApi.toString())
            Line("Theme", "Dark graphite")
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = SenzaColors.TextPrimary, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun yesNo(v: Boolean) = if (v) "yes" else "no"

@Composable
private fun Line(label: String, value: String) {
    Text("$label: $value", style = MaterialTheme.typography.bodyMedium, color = SenzaColors.TextPrimary)
}
