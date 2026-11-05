package com.senzaproject.studio.feature.studio

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.senzaproject.studio.audio.engine.DspModule
import com.senzaproject.studio.audio.engine.EngineCapabilities
import com.senzaproject.studio.audio.engine.EngineParameters
import com.senzaproject.studio.audio.engine.ParameterEdit
import com.senzaproject.studio.core.design.ModuleToggle
import com.senzaproject.studio.core.design.ParamSlider
import com.senzaproject.studio.core.design.SectionLabel
import com.senzaproject.studio.core.design.SenzaCard
import com.senzaproject.studio.core.design.SenzaColors
import com.senzaproject.studio.core.design.SenzaSwitchRow
import java.util.Locale

private fun signed(v: Float, unit: String) = String.format(Locale.US, "%+.1f %s", v, unit)

/** Bypass (A/B), master gain, compressor, limiter ceiling and loudness normalizer. */
@Composable
fun MasterCard(p: EngineParameters, capabilities: EngineCapabilities, onEdit: ParameterEdit) {
    val c = p.dynamics.compressor
    val l = p.dynamics.loudness
    val dynamics = capabilities.of(DspModule.DYNAMICS)
    SenzaCard {
        SectionLabel("Master")
        SenzaSwitchRow("Bypass all processing (A/B compare)", p.bypass, { on -> onEdit { it.copy(bypass = on) } })
        if (p.bypass) {
            Text(
                "Bypass is on: only the output limiter is active.",
                style = MaterialTheme.typography.bodyMedium,
                color = SenzaColors.Warning,
            )
        }
        ParamSlider("Master gain", p.masterGainDb, -24f..6f, signed(p.masterGainDb, "dB"), { v -> onEdit { it.copy(masterGainDb = v) } })
        ParamSlider(
            "Limiter ceiling", p.dynamics.limiter.ceilingDb, -12f..0f, signed(p.dynamics.limiter.ceilingDb, "dB"),
            { v -> onEdit { it.copy(dynamics = it.dynamics.copy(limiter = it.dynamics.limiter.copy(ceilingDb = v))) } },
        )
        Text(
            "The limiter is always on to protect the output; only its ceiling can be changed.",
            style = MaterialTheme.typography.bodyMedium,
            color = SenzaColors.TextMuted,
        )
    }
    SenzaCard {
        SectionLabel("Dynamic master")
        ModuleToggle("Compressor", dynamics, c.enabled) { on ->
            onEdit { it.copy(dynamics = it.dynamics.copy(compressor = it.dynamics.compressor.copy(enabled = on))) }
        }
        ParamSlider(
            "Threshold", c.thresholdDb, -60f..0f, signed(c.thresholdDb, "dB"),
            { v -> onEdit { it.copy(dynamics = it.dynamics.copy(compressor = it.dynamics.compressor.copy(thresholdDb = v))) } },
            enabled = c.enabled,
        )
        ParamSlider(
            "Ratio", c.ratio, 1f..20f, String.format(Locale.US, "%.1f : 1", c.ratio),
            { v -> onEdit { it.copy(dynamics = it.dynamics.copy(compressor = it.dynamics.compressor.copy(ratio = v))) } },
            enabled = c.enabled,
        )
        ParamSlider(
            "Make-up gain", c.makeupDb, 0f..24f, signed(c.makeupDb, "dB"),
            { v -> onEdit { it.copy(dynamics = it.dynamics.copy(compressor = it.dynamics.compressor.copy(makeupDb = v))) } },
            enabled = c.enabled,
        )
        ModuleToggle("Loudness normalizer", dynamics, l.enabled) { on ->
            onEdit { it.copy(dynamics = it.dynamics.copy(loudness = it.dynamics.loudness.copy(enabled = on))) }
        }
        ParamSlider(
            "Target loudness", l.targetLufs, -30f..-8f, String.format(Locale.US, "%.0f LUFS", l.targetLufs),
            { v -> onEdit { it.copy(dynamics = it.dynamics.copy(loudness = it.dynamics.loudness.copy(targetLufs = v))) } },
            enabled = l.enabled,
        )
        ParamSlider(
            "Largest boost", l.maxBoostDb, 0f..12f, signed(l.maxBoostDb, "dB"),
            { v -> onEdit { it.copy(dynamics = it.dynamics.copy(loudness = it.dynamics.loudness.copy(maxBoostDb = v))) } },
            enabled = l.enabled,
        )
    }
}
