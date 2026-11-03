package com.senzaproject.studio.feature.studio

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.senzaproject.studio.audio.engine.EngineCapabilities
import com.senzaproject.studio.audio.engine.EngineState
import com.senzaproject.studio.audio.engine.EngineStatus
import com.senzaproject.studio.core.design.CapabilityRow
import com.senzaproject.studio.core.design.SectionLabel
import com.senzaproject.studio.core.design.SenzaCard
import com.senzaproject.studio.core.design.SenzaColors
import com.senzaproject.studio.core.design.SenzaScreen
import com.senzaproject.studio.core.design.StatusChip
import com.senzaproject.studio.core.design.StatusTone

@Composable
fun StudioScreen(state: EngineState, capabilities: EngineCapabilities) {
    SenzaScreen(title = "STUDIO", subtitle = "Professional audio control") {
        SenzaCard {
            SectionLabel("Engine status")
            Text("Senza Audio Engine", style = MaterialTheme.typography.titleMedium, color = SenzaColors.TextPrimary)
            val (label, tone) = when {
                state.status == EngineStatus.FAILED -> "Failed" to StatusTone.Negative
                state.status == EngineStatus.RUNNING && state.isProcessingAudio -> "Processing" to StatusTone.Positive
                state.status == EngineStatus.RUNNING -> "Running - no DSP" to StatusTone.Caution
                else -> "Stopped" to StatusTone.Muted
            }
            StatusChip(label, tone)
            state.message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = SenzaColors.TextMuted)
            }
        }
        SenzaCard {
            SectionLabel("Output device")
            Text("Not detected yet", style = MaterialTheme.typography.titleMedium, color = SenzaColors.TextPrimary)
            Text(
                "Device routing and per-device profiles arrive in Phase 4.",
                style = MaterialTheme.typography.bodyMedium,
                color = SenzaColors.TextMuted,
            )
        }
        SenzaCard {
            SectionLabel("Signal processing")
            Text(
                "Processing scope: ${capabilities.scope.description}",
                style = MaterialTheme.typography.bodyMedium,
                color = SenzaColors.TextMuted,
            )
            capabilities.modules.forEach { CapabilityRow(it) }
        }
    }
}
