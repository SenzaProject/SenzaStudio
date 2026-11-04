package com.senzaproject.studio.feature.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.senzaproject.studio.audio.engine.ActiveRoute
import com.senzaproject.studio.audio.engine.EngineCapabilities
import com.senzaproject.studio.audio.engine.EngineDiagnostics
import com.senzaproject.studio.audio.engine.EngineState
import com.senzaproject.studio.audio.engine.EngineStatus
import com.senzaproject.studio.audio.engine.OutputDevice
import com.senzaproject.studio.audio.engine.PlayerPhase
import com.senzaproject.studio.audio.engine.PlayerState
import com.senzaproject.studio.core.design.CapabilityRow
import com.senzaproject.studio.core.design.SectionLabel
import com.senzaproject.studio.core.design.SenzaCard
import com.senzaproject.studio.core.design.SenzaColors
import com.senzaproject.studio.core.design.SenzaScreen
import com.senzaproject.studio.core.design.StatusChip
import com.senzaproject.studio.core.design.StatusTone
import java.util.Locale

/** Everything the Studio screen shows, as plain data. */
data class StudioUi(
    val engine: EngineState,
    val capabilities: EngineCapabilities,
    val player: PlayerState,
    val route: ActiveRoute,
    val devices: List<OutputDevice>,
    val diagnostics: EngineDiagnostics,
)

@Composable
fun StudioScreen(ui: StudioUi, onOpenFile: () -> Unit, onPlayPause: () -> Unit, onStop: () -> Unit) {
    SenzaScreen(title = "STUDIO", subtitle = "Professional audio control") {
        EngineCard(ui.engine)
        PlayerCard(ui.player, onOpenFile, onPlayPause, onStop)
        OutputCard(ui.route, ui.devices)
        if (ui.player.phase == PlayerPhase.PLAYING || ui.player.phase == PlayerPhase.PAUSED) {
            DiagnosticsCard(ui.diagnostics)
        }
        SenzaCard {
            SectionLabel("Signal processing")
            Text(
                "Processing scope: ${ui.capabilities.scope.description}",
                style = MaterialTheme.typography.bodyMedium,
                color = SenzaColors.TextMuted,
            )
            ui.capabilities.modules.forEach { CapabilityRow(it) }
        }
    }
}

@Composable
private fun EngineCard(state: EngineState) {
    SenzaCard {
        SectionLabel("Engine status")
        Text("Senza Audio Engine", style = MaterialTheme.typography.titleMedium, color = SenzaColors.TextPrimary)
        val (label, tone) = when {
            state.status == EngineStatus.FAILED -> "Failed" to StatusTone.Negative
            state.status == EngineStatus.RUNNING && state.isProcessingAudio -> "Processing" to StatusTone.Positive
            state.status == EngineStatus.RUNNING -> "Idle - no audio processed" to StatusTone.Caution
            else -> "Stopped" to StatusTone.Muted
        }
        StatusChip(label, tone)
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = SenzaColors.TextMuted) }
    }
}

@Composable
private fun PlayerCard(player: PlayerState, onOpenFile: () -> Unit, onPlayPause: () -> Unit, onStop: () -> Unit) {
    SenzaCard {
        SectionLabel("Built-in player")
        Text(
            player.title ?: "No file loaded",
            style = MaterialTheme.typography.titleMedium,
            color = SenzaColors.TextPrimary,
        )
        val (label, tone) = when (player.phase) {
            PlayerPhase.IDLE -> "Stopped" to StatusTone.Muted
            PlayerPhase.LOADING -> "Loading" to StatusTone.Caution
            PlayerPhase.PLAYING -> "Playing" to StatusTone.Positive
            PlayerPhase.PAUSED -> "Paused" to StatusTone.Caution
            PlayerPhase.ENDED -> "Finished" to StatusTone.Muted
            PlayerPhase.ERROR -> "Error" to StatusTone.Negative
        }
        StatusChip(label, tone)
        if (player.phase == PlayerPhase.PLAYING || player.phase == PlayerPhase.PAUSED || player.phase == PlayerPhase.ENDED) {
            val total = player.durationMs?.let { " / ${clock(it)}" }.orEmpty()
            Text("${clock(player.positionMs)}$total", style = MaterialTheme.typography.bodyMedium, color = SenzaColors.TextMuted)
        }
        player.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = SenzaColors.Danger) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val canToggle = player.phase == PlayerPhase.PLAYING || player.phase == PlayerPhase.PAUSED
            val padding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
            Button(onClick = onOpenFile, modifier = Modifier.weight(1.3f), contentPadding = padding) {
                Text("Open file", maxLines = 1, softWrap = false)
            }
            OutlinedButton(onClick = onPlayPause, enabled = canToggle, modifier = Modifier.weight(1f), contentPadding = padding) {
                Text(if (player.phase == PlayerPhase.PAUSED) "Resume" else "Pause", maxLines = 1, softWrap = false)
            }
            OutlinedButton(onClick = onStop, enabled = canToggle, modifier = Modifier.weight(1f), contentPadding = padding) {
                Text("Stop", maxLines = 1, softWrap = false)
            }
        }
        Text(
            "Only audio played here is processed. Other apps' audio is not touched (see Settings for the experimental path).",
            style = MaterialTheme.typography.bodyMedium,
            color = SenzaColors.TextMuted,
        )
    }
}

@Composable
private fun OutputCard(route: ActiveRoute, devices: List<OutputDevice>) {
    SenzaCard {
        SectionLabel("Output device")
        val device = route.device
        Text(
            device?.let { "${it.name} (${it.type.displayName})" } ?: "No output reported",
            style = MaterialTheme.typography.titleMedium,
            color = SenzaColors.TextPrimary,
        )
        if (device != null) {
            if (route.confirmed) {
                StatusChip("Confirmed route of the player", StatusTone.Positive)
            } else {
                StatusChip("Likely route (Android does not report it)", StatusTone.Caution)
            }
        }
        if (devices.size > 1) {
            Text(
                "Connected: " + devices.joinToString { it.type.displayName },
                style = MaterialTheme.typography.bodyMedium,
                color = SenzaColors.TextMuted,
            )
        }
    }
}

@Composable
private fun DiagnosticsCard(d: EngineDiagnostics) {
    SenzaCard {
        SectionLabel("Live diagnostics")
        Line("Sample rate", "${d.sampleRateHz} Hz")
        Line("Output buffer", "${d.bufferFrames} frames")
        Line("Estimated latency", "${fmt(d.estimatedLatencyMs)} ms (buffer + DSP only)")
        Line("Underruns", d.underruns.toString())
        Line("Clipped blocks", d.clippedBlocks.toString())
        Line("DSP failures", d.dspFailures.toString())
        Line("Output peak", "${fmt(d.outputPeakDb)} dBFS")
        Line("Momentary loudness", "${fmt(d.momentaryLufs)} LUFS")
        Line("Phase correlation", fmt(d.phaseCorrelation))
        Text(
            "Latency excludes hardware, driver and Bluetooth delay, which Android does not expose.",
            style = MaterialTheme.typography.bodyMedium,
            color = SenzaColors.TextMuted,
        )
    }
}

@Composable
private fun Line(label: String, value: String) {
    Text("$label: $value", style = MaterialTheme.typography.bodyMedium, color = SenzaColors.TextPrimary)
}

private fun fmt(v: Double): String = String.format(Locale.US, "%.1f", v)

private fun clock(ms: Long): String {
    val s = ms / 1000
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}
