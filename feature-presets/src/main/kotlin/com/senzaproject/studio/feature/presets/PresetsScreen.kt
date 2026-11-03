package com.senzaproject.studio.feature.presets

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.senzaproject.studio.core.design.SectionLabel
import com.senzaproject.studio.core.design.SenzaCard
import com.senzaproject.studio.core.design.SenzaColors
import com.senzaproject.studio.core.design.SenzaScreen

/**
 * Phase 1: no presets yet. Presets must store real DSP parameters, so factory
 * presets ship together with the DSP modules they configure.
 */
@Composable
fun PresetsScreen(schemaVersion: Int) {
    SenzaScreen(title = "PRESETS", subtitle = "Factory and custom profiles") {
        SenzaCard {
            SectionLabel("Library")
            Text("No presets yet", style = MaterialTheme.typography.titleMedium, color = SenzaColors.TextPrimary)
            Text(
                "Factory presets arrive with the DSP phases so each one stores real, versioned parameters. " +
                    "Current parameter schema: v$schemaVersion.",
                style = MaterialTheme.typography.bodyMedium,
                color = SenzaColors.TextMuted,
            )
        }
    }
}
