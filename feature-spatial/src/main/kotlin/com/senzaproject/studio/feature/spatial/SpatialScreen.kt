package com.senzaproject.studio.feature.spatial

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.senzaproject.studio.audio.engine.ModuleCapability
import com.senzaproject.studio.audio.engine.SpatialMode
import com.senzaproject.studio.audio.engine.SpatialParameters
import com.senzaproject.studio.core.design.CapabilityRow
import com.senzaproject.studio.core.design.SectionLabel
import com.senzaproject.studio.core.design.SenzaCard
import com.senzaproject.studio.core.design.SenzaColors
import com.senzaproject.studio.core.design.SenzaScreen

/** Phase 1: read-only view; spatial DSP and its controls arrive in Phases 3 and 5. */
@Composable
fun SpatialScreen(spatial: SpatialParameters, capability: ModuleCapability) {
    SenzaScreen(title = "SPATIAL", subtitle = "Soundstage, width and scenes") {
        SenzaCard { CapabilityRow(capability) }
        SenzaCard {
            SectionLabel("Audio scenes")
            SpatialMode.entries.forEach { mode ->
                val selected = mode == spatial.mode
                Text(
                    text = if (selected) "${mode.displayName}  (default)" else mode.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selected) SenzaColors.Accent else SenzaColors.TextPrimary,
                )
            }
        }
    }
}
