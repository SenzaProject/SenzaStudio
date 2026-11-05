package com.senzaproject.studio.audio.engine

/** A named set of engine parameters. Factory presets are read-only; user presets can be deleted. */
data class Preset(
    val id: String,
    val name: String,
    val parameters: EngineParameters,
    val factory: Boolean,
)

/** Presets shipped with the app. Each one is a real parameter set for modules that exist in the DSP. */
object FactoryPresets {
    private fun eq(vararg gains: Float): EqParameters {
        require(gains.size == EqParameters.GRAPHIC_FREQUENCIES_HZ.size)
        return EqParameters(
            enabled = true,
            bands = EqParameters.GRAPHIC_FREQUENCIES_HZ.mapIndexed { i, f -> EqBand(f, gains[i]) },
        )
    }

    private fun preset(id: String, name: String, p: EngineParameters) =
        Preset("factory.$id", name, p.sanitized(), factory = true)

    val all: List<Preset> = listOf(
        preset("flat", "Flat (reference)", EngineParameters()),
        preset(
            "bass_boost", "Bass Boost",
            EngineParameters(
                masterGainDb = -3f,
                eq = eq(5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
                bass = BassParameters(enabled = true, subBassDb = 4f, harmonics = 0.4f, punch = 0.3f),
            ),
        ),
        preset(
            "vocal_clarity", "Vocal Clarity",
            EngineParameters(
                masterGainDb = -2f,
                eq = eq(-3f, -2f, -1f, -1f, 0f, 2f, 3f, 3f, 1f, 0f),
                dynamics = DynamicsParameters(compressor = CompressorParameters(enabled = true, thresholdDb = -20f, ratio = 2f, makeupDb = 2f)),
            ),
        ),
        preset(
            "cinema", "Cinema",
            EngineParameters(
                masterGainDb = -3f,
                eq = eq(3f, 2f, 0f, 0f, 0f, 0f, 1f, 2f, 2f, 1f),
                bass = BassParameters(enabled = true, subBassDb = 3f, harmonics = 0.2f, punch = 0.2f),
                spatial = SpatialParameters(enabled = true, mode = SpatialMode.CINEMA, intensity = 0.6f, stereoWidth = 1.2f),
            ),
        ),
        preset(
            "concert_hall", "Concert Hall",
            EngineParameters(
                masterGainDb = -2f,
                spatial = SpatialParameters(enabled = true, mode = SpatialMode.CONCERT_HALL, intensity = 0.6f, stereoWidth = 1.1f),
            ),
        ),
        preset(
            "headphone_natural", "Headphone Natural",
            EngineParameters(
                spatial = SpatialParameters(enabled = true, mode = SpatialMode.STUDIO, intensity = 0.6f, stereoWidth = 1f),
                imaging = ImagingParameters(enabled = true, monoBelowHz = 120f),
            ),
        ),
        preset(
            "gaming", "Gaming",
            EngineParameters(
                masterGainDb = -3f,
                eq = eq(0f, 0f, 0f, 0f, 0f, 1f, 2f, 3f, 2f, 0f),
                spatial = SpatialParameters(enabled = true, mode = SpatialMode.GAMING, intensity = 0.7f, stereoWidth = 1.3f),
            ),
        ),
        preset(
            "night", "Night Mode",
            EngineParameters(
                masterGainDb = -3f,
                eq = eq(-3f, -2f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
                dynamics = DynamicsParameters(
                    compressor = CompressorParameters(enabled = true, thresholdDb = -30f, ratio = 4f, makeupDb = 6f),
                    loudness = LoudnessParameters(enabled = true, targetLufs = -20f, maxBoostDb = 9f, maxCutDb = 12f),
                ),
            ),
        ),
        preset(
            "loudness_match", "Loudness Match",
            EngineParameters(
                dynamics = DynamicsParameters(loudness = LoudnessParameters(enabled = true, targetLufs = -16f)),
            ),
        ),
    )

    fun byId(id: String): Preset? = all.firstOrNull { it.id == id }
}
