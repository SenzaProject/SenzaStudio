package com.senzaproject.studio.feature.equalizer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.senzaproject.studio.audio.engine.EqParameters
import com.senzaproject.studio.audio.engine.ModuleCapability
import com.senzaproject.studio.core.design.CapabilityRow
import com.senzaproject.studio.core.design.SectionLabel
import com.senzaproject.studio.core.design.SenzaCard
import com.senzaproject.studio.core.design.SenzaColors
import com.senzaproject.studio.core.design.SenzaScreen
import java.util.Locale

/**
 * Phase 1: read-only view of the EQ parameter model. Interactive controls are
 * added in Phase 5 once the filters exist (no controls that don't process audio).
 */
@Composable
fun EqualizerScreen(eq: EqParameters, capability: ModuleCapability) {
    SenzaScreen(title = "EQUALIZER", subtitle = "10-band graphic and parametric") {
        SenzaCard { CapabilityRow(capability) }
        SenzaCard {
            SectionLabel("Band layout (read-only)")
            eq.bands.forEach { band ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatHz(band.frequencyHz), style = MaterialTheme.typography.bodyMedium, color = SenzaColors.TextPrimary)
                    Text(
                        String.format(Locale.US, "%+.1f dB  Q %.2f", band.gainDb, band.q),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SenzaColors.TextMuted,
                    )
                }
            }
        }
    }
}

private fun formatHz(hz: Float): String =
    if (hz >= 1_000f) String.format(Locale.US, "%g kHz", hz / 1_000f) else String.format(Locale.US, "%g Hz", hz)
