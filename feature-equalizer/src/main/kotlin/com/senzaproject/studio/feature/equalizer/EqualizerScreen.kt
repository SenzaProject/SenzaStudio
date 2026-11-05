package com.senzaproject.studio.feature.equalizer

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.senzaproject.studio.audio.engine.BassParameters
import com.senzaproject.studio.audio.engine.DspModule
import com.senzaproject.studio.audio.engine.EngineCapabilities
import com.senzaproject.studio.audio.engine.EngineParameters
import com.senzaproject.studio.audio.engine.EqBand
import com.senzaproject.studio.audio.engine.EqParameters
import com.senzaproject.studio.audio.engine.ParameterEdit
import com.senzaproject.studio.core.design.EqGraph
import com.senzaproject.studio.core.design.ModuleToggle
import com.senzaproject.studio.core.design.ParamSlider
import com.senzaproject.studio.core.design.SectionLabel
import com.senzaproject.studio.core.design.SenzaCard
import com.senzaproject.studio.core.design.SenzaColors
import com.senzaproject.studio.core.design.SenzaScreen
import java.util.Locale

private const val BAND_RANGE_DB = 12f

/** Tone shaping: 10-band equalizer with live response graph, plus the bass module. */
@Composable
fun EqualizerScreen(
    parameters: EngineParameters,
    curveDb: FloatArray,
    capabilities: EngineCapabilities,
    onEdit: ParameterEdit,
) {
    val eq = parameters.eq
    val bass = parameters.bass
    SenzaScreen(title = "EQUALIZER", subtitle = "10-band tone control and bass") {
        SenzaCard {
            ModuleToggle("Equalizer", capabilities.of(DspModule.EQUALIZER), eq.enabled) { on ->
                onEdit { it.copy(eq = it.eq.copy(enabled = on)) }
            }
            EqGraph(curveDb, eq.bands.map { it.frequencyHz to if (eq.enabled) it.gainDb else 0f })
            OutlinedButton(
                onClick = { onEdit { it.copy(eq = it.eq.copy(bands = EqParameters.defaultBands())) } },
                enabled = eq.bands.any { it.gainDb != 0f },
            ) { Text("Reset to flat", maxLines = 1, softWrap = false) }
        }
        SenzaCard {
            SectionLabel("Bands")
            eq.bands.forEachIndexed { index, band -> BandSlider(index, band, eq.enabled, onEdit) }
        }
        SenzaCard {
            ModuleToggle("Bass", capabilities.of(DspModule.BASS), bass.enabled) { on ->
                onEdit { it.copy(bass = it.bass.copy(enabled = on)) }
            }
            ParamSlider(
                "Sub-bass (60 Hz shelf)", bass.subBassDb, -BassParameters.MAX_SUB_BASS_DB..BassParameters.MAX_SUB_BASS_DB,
                String.format(Locale.US, "%+.1f dB", bass.subBassDb), { v -> onEdit { it.copy(bass = it.bass.copy(subBassDb = v)) } },
                enabled = bass.enabled,
            )
            ParamSlider(
                "Harmonics", bass.harmonics, 0f..1f, percent(bass.harmonics),
                { v -> onEdit { it.copy(bass = it.bass.copy(harmonics = v)) } }, enabled = bass.enabled,
            )
            ParamSlider(
                "Punch", bass.punch, 0f..1f, percent(bass.punch),
                { v -> onEdit { it.copy(bass = it.bass.copy(punch = v)) } }, enabled = bass.enabled,
            )
            ParamSlider(
                "Low band edge", bass.crossoverHz, BassParameters.MIN_CROSSOVER_HZ..BassParameters.MAX_CROSSOVER_HZ,
                "${bass.crossoverHz.toInt()} Hz", { v -> onEdit { it.copy(bass = it.bass.copy(crossoverHz = v)) } },
                enabled = bass.enabled,
            )
            Text(
                "Harmonics make low notes audible on small speakers; punch emphasises kick attacks. " +
                    "Heavy bass settings can raise the level, so the master gain may need to go down.",
                style = MaterialTheme.typography.bodyMedium,
                color = SenzaColors.TextMuted,
            )
        }
    }
}

@Composable
private fun BandSlider(index: Int, band: EqBand, enabled: Boolean, onEdit: ParameterEdit) {
    ParamSlider(
        label = formatHz(band.frequencyHz),
        value = band.gainDb,
        range = -BAND_RANGE_DB..BAND_RANGE_DB,
        valueText = String.format(Locale.US, "%+.1f dB", band.gainDb),
        onChange = { gain ->
            onEdit { p ->
                p.copy(eq = p.eq.copy(bands = p.eq.bands.mapIndexed { i, b -> if (i == index) b.copy(gainDb = gain) else b }))
            }
        },
        enabled = enabled,
    )
}

private fun percent(v: Float) = "${(v * 100).toInt()} %"

private fun trimmed(v: Float): String = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

private fun formatHz(hz: Float): String =
    if (hz >= 1_000f) "${trimmed(hz / 1_000f)} kHz" else "${trimmed(hz)} Hz"
