package com.senzaproject.studio.audio.engine

/**
 * Parameter model of the Senza audio engine (schema v4).
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
    /** Side-signal scale of the spatial stage: 1 = unchanged stereo width, 0 = mono, 2 = double width. */
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

/**
 * Dynamic loudness management (slow automatic gain control towards a K-weighted loudness target).
 * It is not a limiter: it never guarantees a peak ceiling, the always-on limiter does.
 */
data class LoudnessParameters(
    val enabled: Boolean = false,
    /** Target loudness in LUFS (ITU-R BS.1770 K-weighting). */
    val targetLufs: Float = -16f,
    /** Largest gain the normalizer may add. */
    val maxBoostDb: Float = 6f,
    /** Largest gain the normalizer may remove. */
    val maxCutDb: Float = 12f,
    /** Time constant of upward gain moves; downward moves are four times faster. */
    val responseSeconds: Float = 5f,
) {
    fun sanitized(): LoudnessParameters = copy(
        targetLufs = targetLufs.finiteOr(-16f).coerceIn(-30f, -8f),
        maxBoostDb = maxBoostDb.finiteOr(6f).coerceIn(0f, 12f),
        maxCutDb = maxCutDb.finiteOr(12f).coerceIn(0f, 24f),
        responseSeconds = responseSeconds.finiteOr(5f).coerceIn(1f, 30f),
    )
}

data class DynamicsParameters(
    val compressor: CompressorParameters = CompressorParameters(),
    val limiter: LimiterParameters = LimiterParameters(),
    val loudness: LoudnessParameters = LoudnessParameters(),
) {
    fun sanitized(): DynamicsParameters = copy(
        compressor = compressor.sanitized(),
        limiter = limiter.sanitized(),
        loudness = loudness.sanitized(),
    )
}

/** Bass module: sub-bass shelf, psychoacoustic harmonic enhancement and low-band punch. */
data class BassParameters(
    val enabled: Boolean = false,
    /** Gain of a 60 Hz low shelf (deep bass / sub-bass control). */
    val subBassDb: Float = 0f,
    /** Amount of generated harmonics of the low band, 0 = none, 1 = maximum. */
    val harmonics: Float = 0f,
    /** Emphasis of low-band transients (kick attacks), 0 = none, 1 = maximum. */
    val punch: Float = 0f,
    /** Upper edge of the low band that feeds harmonics and punch. */
    val crossoverHz: Float = 90f,
) {
    fun sanitized(): BassParameters = copy(
        subBassDb = subBassDb.finiteOr(0f).coerceIn(-MAX_SUB_BASS_DB, MAX_SUB_BASS_DB),
        harmonics = harmonics.finiteOr(0f).coerceIn(0f, 1f),
        punch = punch.finiteOr(0f).coerceIn(0f, 1f),
        crossoverHz = crossoverHz.finiteOr(90f).coerceIn(MIN_CROSSOVER_HZ, MAX_CROSSOVER_HZ),
    )

    companion object {
        const val MAX_SUB_BASS_DB = 12f
        const val MIN_CROSSOVER_HZ = 40f
        const val MAX_CROSSOVER_HZ = 200f
    }
}

/** Stereo imaging: mid/side gains, channel balance and mono-compatible bass. */
data class ImagingParameters(
    val enabled: Boolean = false,
    val midGainDb: Float = 0f,
    val sideGainDb: Float = 0f,
    /** -1 = left only, 0 = centre, +1 = right only. Only the opposite channel is attenuated. */
    val balance: Float = 0f,
    /** Content below this frequency is made mono (side removed). 0 = off. */
    val monoBelowHz: Float = 0f,
) {
    fun sanitized(): ImagingParameters {
        val mono = monoBelowHz.finiteOr(0f)
        return copy(
            midGainDb = midGainDb.finiteOr(0f).coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB),
            sideGainDb = sideGainDb.finiteOr(0f).coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB),
            balance = balance.finiteOr(0f).coerceIn(-1f, 1f),
            monoBelowHz = if (mono < MIN_MONO_BELOW_HZ) 0f else mono.coerceAtMost(MAX_MONO_BELOW_HZ),
        )
    }

    companion object {
        const val MAX_GAIN_DB = 12f
        const val MIN_MONO_BELOW_HZ = 20f
        const val MAX_MONO_BELOW_HZ = 300f
    }
}

data class EngineParameters(
    val schemaVersion: Int = SCHEMA_VERSION,
    /** Output gain; capped at +6 dB so the safe-output rule holds even before the limiter exists. */
    val masterGainDb: Float = 0f,
    val eq: EqParameters = EqParameters(),
    val spatial: SpatialParameters = SpatialParameters(),
    val dynamics: DynamicsParameters = DynamicsParameters(),
    val bass: BassParameters = BassParameters(),
    val imaging: ImagingParameters = ImagingParameters(),
    /** A/B switch: skips every module except the always-on limiter (safe output). */
    val bypass: Boolean = false,
) {
    fun sanitized(): EngineParameters = copy(
        schemaVersion = SCHEMA_VERSION,
        masterGainDb = masterGainDb.finiteOr(0f).coerceIn(MIN_MASTER_GAIN_DB, MAX_MASTER_GAIN_DB),
        eq = eq.sanitized(),
        spatial = spatial.sanitized(),
        dynamics = dynamics.sanitized(),
        bass = bass.sanitized(),
        imaging = imaging.sanitized(),
    )

    companion object {
        const val SCHEMA_VERSION = 4
        const val MIN_MASTER_GAIN_DB = -24f
        const val MAX_MASTER_GAIN_DB = 6f
    }
}

/** A UI edit: receives a function that maps the current parameters to the new ones (the engine sanitizes the result). */
typealias ParameterEdit = ((EngineParameters) -> EngineParameters) -> Unit

internal fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback
