package com.senzaproject.studio.feature.spatial

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.senzaproject.studio.audio.engine.DspModule
import com.senzaproject.studio.audio.engine.EngineCapabilities
import com.senzaproject.studio.audio.engine.EngineParameters
import com.senzaproject.studio.audio.engine.ImagingParameters
import com.senzaproject.studio.audio.engine.ParameterEdit
import com.senzaproject.studio.audio.engine.SpatialMode
import com.senzaproject.studio.core.design.ChoiceChips
import com.senzaproject.studio.core.design.ModuleToggle
import com.senzaproject.studio.core.design.ParamSlider
import com.senzaproject.studio.core.design.SectionLabel
import com.senzaproject.studio.core.design.SenzaCard
import com.senzaproject.studio.core.design.SenzaColors
import com.senzaproject.studio.core.design.SenzaScreen
import java.util.Locale

private fun describe(mode: SpatialMode): String = when (mode) {
    SpatialMode.NATURAL -> "Light crossfeed and a little room. Closest to the original mix."
    SpatialMode.STUDIO -> "Dry, headphone-friendly monitoring: crossfeed only, no room."
    SpatialMode.CINEMA -> "Wider picture with a medium room for films."
    SpatialMode.CONCERT_HALL -> "Large room with long reflections."
    SpatialMode.GAMING -> "Wide and tight for positional cues."
    SpatialMode.IMMERSIVE -> "Widest scene with a generous room."
}

/** Soundstage: the Spatial 360 stage and the stereo imaging tools. */
@Composable
fun SpatialScreen(
    parameters: EngineParameters,
    capabilities: EngineCapabilities,
    onEdit: ParameterEdit,
) {
    val spatial = parameters.spatial
    val imaging = parameters.imaging
    SenzaScreen(title = "SPATIAL", subtitle = "Soundstage, width and stereo imaging") {
        SenzaCard {
            ModuleToggle("Spatial 360", capabilities.of(DspModule.SPATIAL), spatial.enabled) { on ->
                onEdit { it.copy(spatial = it.spatial.copy(enabled = on)) }
            }
            SectionLabel("Scene")
            ChoiceChips(
                options = SpatialMode.entries,
                selected = spatial.mode,
                label = { it.displayName },
                onSelect = { mode -> onEdit { it.copy(spatial = it.spatial.copy(mode = mode)) } },
                enabled = spatial.enabled,
            )
            Text(describe(spatial.mode), style = MaterialTheme.typography.bodyMedium, color = SenzaColors.TextMuted)
            ParamSlider(
                "Intensity", spatial.intensity, 0f..1f, "${(spatial.intensity * 100).toInt()} %",
                { v -> onEdit { it.copy(spatial = it.spatial.copy(intensity = v)) } }, enabled = spatial.enabled,
            )
            ParamSlider(
                "Stereo width", spatial.stereoWidth, 0f..2f, "${(spatial.stereoWidth * 100).toInt()} %",
                { v -> onEdit { it.copy(spatial = it.spatial.copy(stereoWidth = v)) } }, enabled = spatial.enabled,
            )
            Text(
                "Spatial 360 works on stereo sound: it widens the image, adds a virtual room and, for headphones, " +
                    "crossfeed. It is not object-based or head-tracked 3D audio. Mono sound stays mono.",
                style = MaterialTheme.typography.bodyMedium,
                color = SenzaColors.TextMuted,
            )
        }
        SenzaCard {
            ModuleToggle("Stereo imaging", capabilities.of(DspModule.STEREO_IMAGING), imaging.enabled) { on ->
                onEdit { it.copy(imaging = it.imaging.copy(enabled = on)) }
            }
            ParamSlider(
                "Mid (centre) gain", imaging.midGainDb, -ImagingParameters.MAX_GAIN_DB..ImagingParameters.MAX_GAIN_DB,
                String.format(Locale.US, "%+.1f dB", imaging.midGainDb),
                { v -> onEdit { it.copy(imaging = it.imaging.copy(midGainDb = v)) } }, enabled = imaging.enabled,
            )
            ParamSlider(
                "Side (width) gain", imaging.sideGainDb, -ImagingParameters.MAX_GAIN_DB..ImagingParameters.MAX_GAIN_DB,
                String.format(Locale.US, "%+.1f dB", imaging.sideGainDb),
                { v -> onEdit { it.copy(imaging = it.imaging.copy(sideGainDb = v)) } }, enabled = imaging.enabled,
            )
            ParamSlider(
                "Balance", imaging.balance, -1f..1f, balanceText(imaging.balance),
                { v -> onEdit { it.copy(imaging = it.imaging.copy(balance = v)) } }, enabled = imaging.enabled,
            )
            ParamSlider(
                "Mono below", imaging.monoBelowHz, 0f..ImagingParameters.MAX_MONO_BELOW_HZ,
                if (imaging.monoBelowHz < ImagingParameters.MIN_MONO_BELOW_HZ) "Off" else "${imaging.monoBelowHz.toInt()} Hz",
                { v -> onEdit { it.copy(imaging = it.imaging.copy(monoBelowHz = v)) } }, enabled = imaging.enabled,
            )
            Text(
                "Mono below keeps low frequencies in the centre, which is safer for speakers and mono playback. " +
                    "Watch the phase correlation on the Studio screen: values near -1 cancel in mono.",
                style = MaterialTheme.typography.bodyMedium,
                color = SenzaColors.TextMuted,
            )
        }
    }
}

private fun balanceText(b: Float): String = when {
    b < -0.005f -> "L ${(-b * 100).toInt()} %"
    b > 0.005f -> "R ${(b * 100).toInt()} %"
    else -> "Centre"
}
