package com.senzaproject.studio.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.senzaproject.studio.audio.engine.Availability
import com.senzaproject.studio.audio.engine.EngineCapabilities
import com.senzaproject.studio.core.design.SectionLabel
import com.senzaproject.studio.core.design.SenzaCard
import com.senzaproject.studio.core.design.SenzaColors
import com.senzaproject.studio.core.design.SenzaScreen

data class AppInfo(val versionName: String, val androidApi: Int)

@Composable
fun SettingsScreen(info: AppInfo, capabilities: EngineCapabilities) {
    SenzaScreen(title = "SETTINGS", subtitle = "Compatibility and diagnostics") {
        SenzaCard {
            SectionLabel("Compatibility report")
            Line("Processing scope", capabilities.scope.description)
            val usable = capabilities.modules.count { it.availability is Availability.Available }
            Line("Usable modules", "$usable of ${capabilities.modules.size}")
            Text(
                "Senza Studio never claims system-wide processing unless it is implemented and tested on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = SenzaColors.TextMuted,
            )
        }
        SenzaCard {
            SectionLabel("About")
            Line("Version", info.versionName)
            Line("Android API", info.androidApi.toString())
            Line("Theme", "Dark graphite")
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Text("$label: $value", style = MaterialTheme.typography.bodyMedium, color = SenzaColors.TextPrimary)
}
