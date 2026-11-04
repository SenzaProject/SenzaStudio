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
