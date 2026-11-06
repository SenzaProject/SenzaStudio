package com.senzaproject.studio.audio.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.senzaproject.studio.audio.dsp.DspChain
import com.senzaproject.studio.audio.dsp.FloatRing
import com.senzaproject.studio.audio.dsp.PcmConvert
import com.senzaproject.studio.audio.engine.EngineDiagnostics
import com.senzaproject.studio.audio.engine.EngineParameters
import com.senzaproject.studio.audio.engine.MeterSnapshot
import com.senzaproject.studio.audio.engine.PlayerPhase
import com.senzaproject.studio.audio.engine.PlayerState
import com.senzaproject.studio.audio.engine.SPECTRUM_BANDS
import java.nio.ByteBuffer
import java.util.concurrent.locks.LockSupport

/**
 * App-local player: MediaExtractor + MediaCodec decode, [DspChain] processing, float AudioTrack output.
 * This is the one path where every Senza module really processes audio, because the audio session
 * belongs to this app.
 *
 * Threading (P6): two threads per playback, decoupled by a lock-free [FloatRing]:
 *  - "senza-decode": MediaExtractor/MediaCodec -> stereo float -> ring. It may stall (slow storage,
 *    codec hiccups) without the audio output noticing as long as the ring holds data.
 *  - "senza-audio" (THREAD_PRIORITY_URGENT_AUDIO): ring -> [DspChain] -> blocking AudioTrack write.
 *    The loop allocates nothing and takes no locks while it processes; DSP time is measured per
 *    block ([EngineDiagnostics.dspLoadPercent]).
 * Meters, state and diagnostics are published by a main-thread ticker that only READS volatile
 * state, so the allocations of snapshots never happen on the audio thread. While the UI is not
 * visible the ticker slows down and the spectrum FFT is switched off.
 * Limits (deliberate, reported to the user): mono/stereo files only, no seeking, one format per file.
 */
internal class LocalPlayer(
    private val context: Context,
    private val currentParameters: () -> EngineParameters,
    private val listener: Listener,
) {
    interface Listener {
        fun onPlayerState(state: PlayerState)
        fun onDiagnostics(diagnostics: EngineDiagnostics, routedDeviceId: Int?)
        fun onChain(chain: DspChain?)
        fun onMeters(meters: MeterSnapshot)
    }

    private class StreamFormat(val sampleRate: Int, val channels: Int)

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var playback: Playback? = null
    @Volatile private var ducked = false
    @Volatile private var uiVisible = true

    val isActive: Boolean get() = playback?.isAlive == true
    val isPaused: Boolean get() = playback?.paused == true

    fun open(uri: Uri, title: String) {
        stop()
        val p = Playback(uri, title)
        playback = p
        p.start()
    }

    fun pause() {
        playback?.paused = true
    }

    fun resume() {
        playback?.paused = false
    }

    fun setDucked(value: Boolean) {
        ducked = value
        playback?.applyVolume()
    }

    /** Lifecycle: spectrum analysis and fast meter updates only while the UI is visible. */
    fun setUiVisible(value: Boolean) {
        uiVisible = value
        playback?.applyUiVisible()
    }

    /** Stops playback and waits (briefly) for both threads to finish. */
    fun stop() {
        val p = playback
        if (p != null) {
            p.requestStop()
            p.joinThreads()
        }
        playback = null
    }

    private inner class Playback(val uri: Uri, val title: String) {
        @Volatile var paused = false
        @Volatile private var stopRequested = false

        @Volatile private var track: AudioTrack? = null
        @Volatile private var chain: DspChain? = null
        @Volatile private var sampleRate = 0
        @Volatile private var framesWritten = 0L
        @Volatile private var durationMs: Long? = null

        @Volatile private var streamFormat: StreamFormat? = null
        @Volatile private var decodeDone = false
        @Volatile private var decodeError: Throwable? = null

        @Volatile private var starvations = 0
        @Volatile private var dspFailures = 0
        @Volatile private var clipped = 0
        @Volatile private var dspLoad = 0.0
        @Volatile private var dspPeakLoad = 0.0

        @Volatile private var tickerActive = false
        private var lastPublishNs = 0L

        private val stateLock = Any()
        private var finalPublished = false

        private val ring = FloatRing(RING_FRAMES * 2)
        private val decodeThread = Thread({ decodeLoop() }, "senza-decode")
        private val audioThread = Thread({ audioLoop() }, "senza-audio")

        val isAlive: Boolean get() = audioThread.isAlive

        fun start() {
            decodeThread.start()
            audioThread.start()
        }

        fun requestStop() {
            stopRequested = true
            paused = false
        }

        fun joinThreads() {
            val me = Thread.currentThread()
            if (me === audioThread || me === decodeThread) return
            try { audioThread.join(2_000) } catch (_: InterruptedException) { me.interrupt() }
            try { decodeThread.join(2_000) } catch (_: InterruptedException) { me.interrupt() }
        }

        fun applyVolume() {
            track?.setVolume(if (ducked) DUCK_VOLUME else 1f)
        }

        fun applyUiVisible() {
            chain?.spectrumEnabled = uiVisible
            if (tickerActive) {
                mainHandler.removeCallbacks(ticker)
                mainHandler.post(ticker)
            }
        }

        // ---- decode thread ----

        private fun decodeLoop() {
            var extractor: MediaExtractor? = null
            var codec: MediaCodec? = null
            try {
                val ex = MediaExtractor()
                extractor = ex
                ex.setDataSource(context, uri, null)
                var index = -1
                for (i in 0 until ex.trackCount) {
                    if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                        index = i
                        break
                    }
                }
                if (index < 0) throw IllegalArgumentException("The file has no audio track")
                ex.selectTrack(index)
                val inFormat = ex.getTrackFormat(index)
                val mime = inFormat.getString(MediaFormat.KEY_MIME) ?: throw IllegalArgumentException("Unknown audio type")
                durationMs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) / 1000 else null
                val dec = MediaCodec.createDecoderByType(mime)
                codec = dec
                dec.configure(inFormat, null, null, 0)
                dec.start()

                var rate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var floatPcm = false
                var formatPublished = false
                val chunk = FloatArray(CHUNK_FRAMES * 2)
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false

                while (!outputDone && !stopRequested) {
                    if (!inputDone) {
                        val inIndex = dec.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            val inBuf = dec.getInputBuffer(inIndex)!!
                            val size = ex.readSampleData(inBuf, 0)
                            if (size < 0) {
                                dec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                dec.queueInputBuffer(inIndex, 0, size, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val outIndex = dec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (outIndex >= 0) {
                        val outBuf = dec.getOutputBuffer(outIndex)!!
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        if (info.size > 0) {
                            require(channels == 1 || channels == 2) {
                                "Only mono and stereo files are supported (this one has $channels channels)"
                            }
                            if (!formatPublished) {
                                streamFormat = StreamFormat(rate, channels)
                                formatPublished = true
                            }
                            pushToRing(outBuf, channels, floatPcm, chunk)
                        }
                        dec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = dec.outputFormat
                        val newRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val newChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        check(!formatPublished || (newRate == rate && newChannels == channels)) {
                            "The audio format changed during playback, which is not supported"
                        }
                        rate = newRate
                        channels = newChannels
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                }
            } catch (t: Throwable) {
                decodeError = t
            } finally {
                decodeDone = true
                try { codec?.stop() } catch (_: Exception) { }
                try { codec?.release() } catch (_: Exception) { }
                try { extractor?.release() } catch (_: Exception) { }
            }
        }

        /** Converts one decoder buffer to stereo float and queues it; waits (parked) while the ring is full. */
        private fun pushToRing(buf: ByteBuffer, channels: Int, floatPcm: Boolean, chunk: FloatArray) {
            while (buf.hasRemaining() && !stopRequested) {
                val frames = if (floatPcm) {
                    PcmConvert.pcmFloatToStereoFloat(buf, channels, chunk, 0, CHUNK_FRAMES)
                } else {
                    PcmConvert.pcm16ToStereoFloat(buf, channels, chunk, 0, CHUNK_FRAMES)
                }
                if (frames == 0) break
                val total = frames * 2
                var offset = 0
                while (offset < total && !stopRequested) {
                    val written = ring.write(chunk, offset, total - offset)
                    offset += written
                    if (written == 0) LockSupport.parkNanos(if (paused) PAUSED_POLL_NS else FULL_POLL_NS)
                }
            }
        }

        // ---- audio thread ----

        private fun audioLoop() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            var out: AudioTrack? = null
            var routingListener: AudioRouting.OnRoutingChangedListener? = null
            try {
                publishState(PlayerPhase.LOADING)

                while (streamFormat == null && !decodeDone && !stopRequested) LockSupport.parkNanos(WAIT_POLL_NS)
                val fmt = streamFormat
                if (fmt == null && !stopRequested) {
                    decodeError?.let { throw it }
                }
                if (fmt != null && !stopRequested) {
                    val rate = fmt.sampleRate
                    sampleRate = rate
                    val format = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                    val minBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
                    check(minBytes > 0) { "This device cannot play float stereo at $rate Hz" }
                    val t = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build(),
                        )
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(minBytes * BUFFER_MULTIPLIER)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        // Music does not need the lowest latency path: the power-saving path lets the
                        // device sleep between buffer refills (battery).
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)
                        .build()
                    out = t
                    t.setVolume(if (ducked) DUCK_VOLUME else 1f)
                    val l = AudioRouting.OnRoutingChangedListener { publishDiagnostics() }
                    t.addOnRoutingChangedListener(l, mainHandler)
                    routingListener = l
                    val c = DspChain(rate)
                    c.configure(currentParameters())
                    c.spectrumEnabled = uiVisible
                    chain = c
                    track = t
                    listener.onChain(c)

                    // Pre-buffer so playback does not start on a half-empty ring.
                    while (ring.availableToRead() < PREBUFFER_FRAMES * 2 && !decodeDone && !stopRequested) {
                        LockSupport.parkNanos(WAIT_POLL_NS)
                    }
                    t.play()
                    tickerActive = true
                    lastPublishNs = System.nanoTime()
                    publishState(PlayerPhase.PLAYING)
                    publishDiagnostics()
                    mainHandler.post(ticker)

                    runAudio(t, c, rate)

                    if (!stopRequested) decodeError?.let { throw it }
                    if (stopRequested) {
                        t.pause()
                        t.flush()
                    } else {
                        // Let the already written audio finish playing before reporting the end.
                        t.stop()
                        val deadline = System.nanoTime() + (t.bufferSizeInFrames * 1_000_000_000L / rate) + 500_000_000L
                        while (t.playbackHeadPosition < framesWritten && System.nanoTime() < deadline && !stopRequested) {
                            LockSupport.parkNanos(WAIT_POLL_NS * 2)
                        }
                    }
                    publishDiagnostics()
                }
                publishFinal(if (stopRequested) PlayerPhase.IDLE else PlayerPhase.ENDED, null)
            } catch (e: Throwable) {
                publishFinal(PlayerPhase.ERROR, e.message ?: e.javaClass.simpleName)
            } finally {
                stopRequested = true // lets the decode thread finish if it has not already
                tickerActive = false
                mainHandler.removeCallbacks(ticker)
                val t = out
                if (t != null) {
                    routingListener?.let { try { t.removeOnRoutingChangedListener(it) } catch (_: Exception) { } }
                    try { t.release() } catch (_: Exception) { }
                }
                track = null
                chain = null
                listener.onChain(null)
                listener.onMeters(MeterSnapshot.SILENT)
            }
        }

        /**
         * The real-time part: ring -> DSP -> AudioTrack. No allocation and no locks in here (the one
         * lock-using call, [publishState], only runs on pause transitions, never while processing).
         */
        private fun runAudio(t: AudioTrack, c: DspChain, rate: Int) {
            val block = FloatArray(BLOCK_FRAMES * 2)
            val backup = FloatArray(BLOCK_FRAMES * 2)
            var dspBypassed = false
            var starving = false
            var windowBusyNs = 0L
            var windowAudioNs = 0L
            var peakLoad = 0.0

            while (!stopRequested) {
                if (paused) {
                    t.pause()
                    publishState(PlayerPhase.PAUSED)
                    while (paused && !stopRequested) LockSupport.parkNanos(PAUSED_POLL_NS)
                    if (stopRequested) break
                    t.play()
                    publishState(PlayerPhase.PLAYING)
                }

                val got = ring.read(block, 0, BLOCK_FRAMES * 2)
                if (got == 0) {
                    val finished = decodeDone
                    if (finished && ring.availableToRead() == 0) break
                    if (!finished && !starving) {
                        starving = true
                        starvations++
                    }
                    LockSupport.parkNanos(STARVE_POLL_NS)
                    continue
                }
                starving = false
                val frames = got / 2

                if (!dspBypassed) {
                    System.arraycopy(block, 0, backup, 0, got)
                    val t0 = System.nanoTime()
                    try {
                        c.process(block, frames)
                    } catch (e: Exception) {
                        dspFailures++
                        dspBypassed = true
                        System.arraycopy(backup, 0, block, 0, got)
                    }
                    val busy = System.nanoTime() - t0
                    val blockNs = frames * 1_000_000_000L / rate
                    val load = busy.toDouble() / blockNs
                    if (load > peakLoad) {
                        peakLoad = load
                        dspPeakLoad = load * 100.0
                    }
                    windowBusyNs += busy
                    windowAudioNs += blockNs
                    if (windowAudioNs >= LOAD_WINDOW_NS) {
                        dspLoad = windowBusyNs * 100.0 / windowAudioNs
                        windowBusyNs = 0L
                        windowAudioNs = 0L
                    }
                }

                var peak = 0f
                var i = 0
                while (i < got) {
                    val a = if (block[i] < 0f) -block[i] else block[i]
                    if (a > peak) peak = a
                    i++
                }
                if (peak >= CLIP_LEVEL) clipped++

                var offset = 0
                while (offset < got && !stopRequested) {
                    val written = t.write(block, offset, got - offset, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) throw IllegalStateException("AudioTrack write failed (code $written)")
                    offset += written
                }
                framesWritten += frames
            }
        }

        // ---- publishing (state under a lock; meters and diagnostics from the main-thread ticker) ----

        private val ticker = object : Runnable {
            override fun run() {
                if (!tickerActive) return
                val visible = uiVisible
                val isPaused = paused
                if (visible && !isPaused) publishMeters()
                val now = System.nanoTime()
                val interval = if (visible) PUBLISH_INTERVAL_NS else SLOW_PUBLISH_INTERVAL_NS
                if (now - lastPublishNs >= interval) {
                    lastPublishNs = now
                    publishState(if (isPaused) PlayerPhase.PAUSED else PlayerPhase.PLAYING)
                    publishDiagnostics()
                }
                if (tickerActive) {
                    mainHandler.postDelayed(this, if (visible && !isPaused) METER_INTERVAL_MS else SLOW_TICK_MS)
                }
            }
        }

        private fun publishState(phase: PlayerPhase) {
            synchronized(stateLock) {
                if (finalPublished) return
                val rate = sampleRate
                val pos = if (rate > 0) framesWritten * 1000 / rate else 0L
                listener.onPlayerState(PlayerState(phase, title, durationMs, pos, null))
            }
        }

        /** The last state of this playback; nothing from the ticker can overwrite it afterwards. */
        private fun publishFinal(phase: PlayerPhase, error: String?) {
            synchronized(stateLock) {
                finalPublished = true
                val state = if (phase == PlayerPhase.ERROR) {
                    PlayerState(phase, title, error = error)
                } else {
                    val rate = sampleRate
                    val pos = if (rate > 0) framesWritten * 1000 / rate else 0L
                    PlayerState(phase, title, durationMs, pos, null)
                }
                listener.onPlayerState(state)
            }
        }

        private fun publishDiagnostics() {
            val c = chain ?: return
            val t = track ?: return
            try {
                val rate = sampleRate
                if (rate <= 0) return
                val buffer = t.bufferSizeInFrames
                val latency = (buffer + c.latencySamples) * 1000.0 / rate
                listener.onDiagnostics(
                    EngineDiagnostics(
                        sampleRateHz = rate,
                        bufferFrames = buffer,
                        estimatedLatencyMs = latency,
                        underruns = t.underrunCount,
                        dspFailures = dspFailures,
                        clippedBlocks = clipped,
                        outputPeakDb = c.loudnessMeter.samplePeakDb,
                        momentaryLufs = c.loudnessMeter.momentaryLufs,
                        phaseCorrelation = c.stereoMeter.correlation,
                        decoderStarvations = starvations,
                        dspLoadPercent = dspLoad,
                        dspPeakBlockLoadPercent = dspPeakLoad,
                    ),
                    t.routedDevice?.id,
                )
            } catch (_: IllegalStateException) {
                // The track was released while this ran; the playback is ending anyway.
            }
        }

        private fun publishMeters() {
            val c = chain ?: return
            val bands = FloatArray(SPECTRUM_BANDS)
            c.spectrum.snapshot(bands)
            listener.onMeters(
                MeterSnapshot(
                    peakDbLeft = c.levelMeter.peakDbLeft,
                    peakDbRight = c.levelMeter.peakDbRight,
                    rmsDbLeft = c.levelMeter.rmsDbLeft,
                    rmsDbRight = c.levelMeter.rmsDbRight,
                    momentaryLufs = c.loudnessMeter.momentaryLufs,
                    shortTermLufs = c.loudnessMeter.shortTermLufs,
                    truePeakDb = c.loudnessMeter.truePeakDb,
                    phaseCorrelation = c.stereoMeter.correlation,
                    gainReductionDb = c.gainReductionDb,
                    normalizerGainDb = c.normalizerGainDb,
                    spectrumDb = bands,
                ),
            )
        }
    }

    private companion object {
        const val BLOCK_FRAMES = 1024
        const val CHUNK_FRAMES = 1024
        /** Decoder-to-audio ring: about 340 ms at 48 kHz (decoded PCM, upstream of the DSP). */
        const val RING_FRAMES = 16_384
        const val PREBUFFER_FRAMES = 4_096
        const val BUFFER_MULTIPLIER = 4
        const val TIMEOUT_US = 10_000L
        const val DUCK_VOLUME = 0.2f
        const val CLIP_LEVEL = 0.9999f
        const val PUBLISH_INTERVAL_NS = 250_000_000L
        const val SLOW_PUBLISH_INTERVAL_NS = 1_000_000_000L
        const val METER_INTERVAL_MS = 40L
        const val SLOW_TICK_MS = 250L
        const val LOAD_WINDOW_NS = 500_000_000L
        const val WAIT_POLL_NS = 5_000_000L
        const val STARVE_POLL_NS = 1_000_000L
        const val FULL_POLL_NS = 8_000_000L
        const val PAUSED_POLL_NS = 50_000_000L
    }
}
