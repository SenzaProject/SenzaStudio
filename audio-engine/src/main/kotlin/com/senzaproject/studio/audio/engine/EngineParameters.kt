package com.senzaproject.studio.audio.engine

/**
 * Parameter model of the Senza audio engine (schema v1).
 *
 * Every value is range-checked by [sanitized]; the engine never receives NaN,
 * infinity or out-of-range values, whatever the UI or a preset import sends.
 */
enum class FilterType { PEAKING, LOW_SHELF, HIGH_SHELF, HIGH_PASS, LOW_PASS }

/** Audio scenes from the product brief. */
enum class SpatialMode(val displayName: String) {
    NATURAL("Natural"),
    STUDIO("Studio"),
    CINEMA("Cinema"),
    CONCERT_HALL("Concert Hall"),
    GAMING("Gaming"),
    IMMERSIVE("Immersive"),
}

data class EqBand(
    val frequencyHz: Float,
    val gainDb: Float = 0f,
    val q: Float = 1f,
    val type: FilterType = FilterType.PEAKING,
) {
    fun sanitized(): EqBand = copy(
        frequencyHz = frequencyHz.finiteOr(1_000f).coerceIn(MIN_FREQUENCY_HZ, MAX_FREQUENCY_HZ),
        gainDb = gainDb.finiteOr(0f).coerceIn(-MAX_BAND_GAIN_DB, MAX_BAND_GAIN_DB),
        q = q.finiteOr(1f).coerceIn(MIN_Q, MAX_Q),
    )

    companion object {
        const val MIN_FREQUENCY_HZ = 20f
        const val MAX_FREQUENCY_HZ = 20_000f
        const val MAX_BAND_GAIN_DB = 15f
        const val MIN_Q = 0.1f
        const val MAX_Q = 18f
    }
}

data class EqParameters(
    val enabled: Boolean = false,
    val bands: List<EqBand> = defaultBands(),
) {
    fun sanitized(): EqParameters = copy(bands = bands.take(MAX_BANDS).map { it.sanitized() })

    companion object {
        const val MAX_BANDS = 16

        /** 10-band graphic layout (octave-spaced ISO centre frequencies), flat. */
        val GRAPHIC_FREQUENCIES_HZ = floatArrayOf(31.25f, 62.5f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f)

        fun defaultBands(): List<EqBand> = GRAPHIC_FREQUENCIES_HZ.map { EqBand(frequencyHz = it) }
    }
}

data class SpatialParameters(
    val enabled: Boolean = false,
    val mode: SpatialMode = SpatialMode.NATURAL,
    /** 0 = none, 1 = maximum. */
    val intensity: Float = 0.5f,
    /** 1 = unchanged stereo width, 0 = mono, 2 = double width. */
    val stereoWidth: Float = 1f,
) {
    fun sanitized(): SpatialParameters = copy(
        intensity = intensity.finiteOr(0.5f).coerceIn(0f, 1f),
        stereoWidth = stereoWidth.finiteOr(1f).coerceIn(0f, 2f),
    )
}

data class CompressorParameters(
    val enabled: Boolean = false,
    val thresholdDb: Float = -18f,
    val ratio: Float = 2f,
    val attackMs: Float = 10f,
    val releaseMs: Float = 120f,
    val kneeDb: Float = 6f,
    val makeupDb: Float = 0f,
) {
    fun sanitized(): CompressorParameters = copy(
        thresholdDb = thresholdDb.finiteOr(-18f).coerceIn(-60f, 0f),
        ratio = ratio.finiteOr(2f).coerceIn(1f, 20f),
        attackMs = attackMs.finiteOr(10f).coerceIn(0.1f, 200f),
        releaseMs = releaseMs.finiteOr(120f).coerceIn(5f, 2_000f),
        kneeDb = kneeDb.finiteOr(6f).coerceIn(0f, 24f),
        makeupDb = makeupDb.finiteOr(0f).coerceIn(0f, 24f),
    )
}

/** The limiter is always in the signal path (safe output); only its ceiling/release are adjustable. */
data class LimiterParameters(
    val ceilingDb: Float = -0.3f,
    val releaseMs: Float = 80f,
) {
    fun sanitized(): LimiterParameters = copy(
        ceilingDb = ceilingDb.finiteOr(-0.3f).coerceIn(-12f, 0f),
        releaseMs = releaseMs.finiteOr(80f).coerceIn(10f, 1_000f),
    )
}

data class DynamicsParameters(
    val compressor: CompressorParameters = CompressorParameters(),
    val limiter: LimiterParameters = LimiterParameters(),
) {
    fun sanitized(): DynamicsParameters = copy(compressor = compressor.sanitized(), limiter = limiter.sanitized())
}

data class EngineParameters(
    val schemaVersion: Int = SCHEMA_VERSION,
    /** Output gain; capped at +6 dB so the safe-output rule holds even before the limiter exists. */
    val masterGainDb: Float = 0f,
    val eq: EqParameters = EqParameters(),
    val spatial: SpatialParameters = SpatialParameters(),
    val dynamics: DynamicsParameters = DynamicsParameters(),
) {
    fun sanitized(): EngineParameters = copy(
        schemaVersion = SCHEMA_VERSION,
        masterGainDb = masterGainDb.finiteOr(0f).coerceIn(MIN_MASTER_GAIN_DB, MAX_MASTER_GAIN_DB),
        eq = eq.sanitized(),
        spatial = spatial.sanitized(),
        dynamics = dynamics.sanitized(),
    )

    companion object {
        const val SCHEMA_VERSION = 2
        const val MIN_MASTER_GAIN_DB = -24f
        const val MAX_MASTER_GAIN_DB = 6f
    }
}

internal fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback
