package com.senzaproject.studio.audio.engine

enum class PlayerPhase { IDLE, LOADING, PLAYING, PAUSED, ENDED, ERROR }

/** State of the built-in player (the only audio path processed in every build). */
data class PlayerState(
    val phase: PlayerPhase = PlayerPhase.IDLE,
    val title: String? = null,
    val durationMs: Long? = null,
    val positionMs: Long = 0,
    val error: String? = null,
)

/** Live measurements of the running pipeline, published a few times per second. */
data class EngineDiagnostics(
    val route: ActiveRoute = ActiveRoute(null, false),
    val sampleRateHz: Int = 0,
    val bufferFrames: Int = 0,
    /** Output buffer + DSP look-ahead only. Hardware, driver and Bluetooth latency are NOT included. */
    val estimatedLatencyMs: Double = 0.0,
    val underruns: Int = 0,
    val dspFailures: Int = 0,
    val clippedBlocks: Int = 0,
    val outputPeakDb: Double = -120.0,
    val momentaryLufs: Double = -120.0,
    val phaseCorrelation: Double = 0.0,
)

/** Number of log-spaced spectrum bands published to the UI (20 Hz .. 20 kHz). */
const val SPECTRUM_BANDS = 32

/**
 * Live meters of the OUTPUT of the processing chain, published about 25 times per second while
 * playing. Levels are in dBFS (-120 = silence), loudness in LUFS.
 */
data class MeterSnapshot(
    val peakDbLeft: Double = -120.0,
    val peakDbRight: Double = -120.0,
    val rmsDbLeft: Double = -120.0,
    val rmsDbRight: Double = -120.0,
    val momentaryLufs: Double = -120.0,
    val shortTermLufs: Double = -120.0,
    val truePeakDb: Double = -120.0,
    val phaseCorrelation: Double = 0.0,
    /** Compressor gain reduction in dB (0 or negative). */
    val gainReductionDb: Double = 0.0,
    val normalizerGainDb: Double = 0.0,
    /** [SPECTRUM_BANDS] levels in dBFS, low to high frequency; all -120 when silent. */
    val spectrumDb: FloatArray = FloatArray(SPECTRUM_BANDS) { -120f },
) {
    companion object {
        val SILENT = MeterSnapshot()
    }
}
