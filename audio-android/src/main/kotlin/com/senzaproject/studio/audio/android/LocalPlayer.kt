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
import com.senzaproject.studio.audio.dsp.DspChain
import com.senzaproject.studio.audio.dsp.PcmConvert
import com.senzaproject.studio.audio.engine.EngineDiagnostics
import com.senzaproject.studio.audio.engine.EngineParameters
import com.senzaproject.studio.audio.engine.MeterSnapshot
import com.senzaproject.studio.audio.engine.SPECTRUM_BANDS
import com.senzaproject.studio.audio.engine.PlayerPhase
import com.senzaproject.studio.audio.engine.PlayerState

/**
 * App-local player: MediaExtractor + MediaCodec decode, [DspChain] processing, float AudioTrack output.
 * This is the one path where every Senza module really processes audio, because the audio session
 * belongs to this app.
 *
 * Threading: one worker thread per playback does decode -> DSP -> blocking AudioTrack write.
 * Decoupling decode from the audio write with a ring buffer is a Phase 6 item.
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

    @Volatile private var stopRequested = false
    @Volatile private var paused = false
    @Volatile private var ducked = false
    @Volatile private var worker: Thread? = null
    @Volatile private var track: AudioTrack? = null

    val isActive: Boolean get() = worker?.isAlive == true
    val isPaused: Boolean get() = paused

    fun open(uri: Uri, title: String) {
        stop()
        stopRequested = false
        paused = false
        val t = Thread({ playbackLoop(uri, title) }, "senza-player")
        worker = t
        t.start()
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    fun setDucked(value: Boolean) {
        ducked = value
        track?.setVolume(if (value) DUCK_VOLUME else 1f)
    }

    /** Stops playback and waits (briefly) for the worker to finish. */
    fun stop() {
        stopRequested = true
        paused = false
        val t = worker
        if (t != null && t !== Thread.currentThread()) {
            try { t.join(2_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        worker = null
    }

    private fun playbackLoop(uri: Uri, title: String) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var out: AudioTrack? = null
        var chain: DspChain? = null
        var routingListener: AudioRouting.OnRoutingChangedListener? = null
        try {
            listener.onPlayerState(PlayerState(PlayerPhase.LOADING, title))
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
            val durationMs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) / 1000 else null
            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(inFormat, null, null, 0)
            dec.start()

            var sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            var framesWritten = 0L
            var lastPublish = 0L
            var lastMeters = 0L
            var underruns = 0
            var dspFailures = 0
            var clipped = 0
            var dspBypassed = false
            var scratch = FloatArray(0)
            var backup = FloatArray(0)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val mainHandler = Handler(Looper.getMainLooper())

            fun publishState(phase: PlayerPhase, error: String? = null) {
                val pos = if (sampleRate > 0) framesWritten * 1000 / sampleRate else 0
                listener.onPlayerState(PlayerState(phase, title, durationMs, pos, error))
            }

            fun publishDiagnostics() {
                val c = chain ?: return
                val t = out ?: return
                underruns = t.underrunCount
                val latency = (t.bufferSizeInFrames + c.latencySamples) * 1000.0 / sampleRate
                listener.onDiagnostics(
                    EngineDiagnostics(
                        sampleRateHz = sampleRate,
                        bufferFrames = t.bufferSizeInFrames,
                        estimatedLatencyMs = latency,
                        underruns = underruns,
                        dspFailures = dspFailures,
                        clippedBlocks = clipped,
                        outputPeakDb = c.loudnessMeter.samplePeakDb,
                        momentaryLufs = c.loudnessMeter.momentaryLufs,
                        phaseCorrelation = c.stereoMeter.correlation,
                    ),
                    t.routedDevice?.id,
                )
            }

            fun publishMeters() {
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

            fun ensureOutput() {
                if (out != null) return
                require(channels == 1 || channels == 2) { "Only mono and stereo files are supported (this one has $channels channels)" }
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
                val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
                check(minBytes > 0) { "This device cannot play float stereo at $sampleRate Hz" }
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
                    .build()
                t.setVolume(if (ducked) DUCK_VOLUME else 1f)
                val l = AudioRouting.OnRoutingChangedListener { publishDiagnostics() }
                t.addOnRoutingChangedListener(l, mainHandler)
                routingListener = l
                val c = DspChain(sampleRate)
                c.configure(currentParameters())
                chain = c
                listener.onChain(c)
                scratch = FloatArray(BLOCK_FRAMES * 2)
                backup = FloatArray(BLOCK_FRAMES * 2)
                out = t
                track = t
                t.play()
            }

            fun render(buf: java.nio.ByteBuffer) {
                ensureOutput()
                val t = out!!
                val c = chain!!
                while (buf.hasRemaining() && !stopRequested) {
                    val frames = if (floatPcm) {
                        PcmConvert.pcmFloatToStereoFloat(buf, channels, scratch, 0, BLOCK_FRAMES)
                    } else {
                        PcmConvert.pcm16ToStereoFloat(buf, channels, scratch, 0, BLOCK_FRAMES)
                    }
                    if (frames == 0) break
                    val samples = frames * 2
                    if (!dspBypassed) {
                        System.arraycopy(scratch, 0, backup, 0, samples)
                        try {
                            c.process(scratch, frames)
                        } catch (e: Exception) {
                            dspFailures++
                            dspBypassed = true
                            System.arraycopy(backup, 0, scratch, 0, samples)
                        }
                    }
                    var peak = 0f
                    for (i in 0 until samples) {
                        val a = if (scratch[i] < 0f) -scratch[i] else scratch[i]
                        if (a > peak) peak = a
                    }
                    if (peak >= CLIP_LEVEL) clipped++
                    var offset = 0
                    while (offset < samples && !stopRequested) {
                        val written = t.write(scratch, offset, samples - offset, AudioTrack.WRITE_BLOCKING)
                        if (written < 0) throw IllegalStateException("AudioTrack write failed (code $written)")
                        offset += written
                    }
                    framesWritten += frames
                }
                val now = System.nanoTime()
                if (now - lastMeters > METER_INTERVAL_NS) {
                    lastMeters = now
                    publishMeters()
                }
                if (now - lastPublish > PUBLISH_INTERVAL_NS) {
                    lastPublish = now
                    publishState(PlayerPhase.PLAYING)
                    publishDiagnostics()
                }
            }

            while (!outputDone && !stopRequested) {
                if (paused) {
                    out?.pause()
                    publishState(PlayerPhase.PAUSED)
                    while (paused && !stopRequested) Thread.sleep(20)
                    if (stopRequested) break
                    out?.play()
                    publishState(PlayerPhase.PLAYING)
                }
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
                    if (info.size > 0) render(outBuf)
                    dec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = dec.outputFormat
                    val newRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val newChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    check(out == null || (newRate == sampleRate && newChannels == channels)) {
                        "The audio format changed during playback, which is not supported"
                    }
                    sampleRate = newRate
                    channels = newChannels
                    floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
            }

            val t = out
            if (t != null) {
                if (stopRequested) {
                    t.pause()
                    t.flush()
                } else {
                    // Let the already written audio finish playing before reporting the end.
                    t.stop()
                    val deadline = System.nanoTime() + (t.bufferSizeInFrames * 1_000_000_000L / sampleRate) + 500_000_000L
                    while (t.playbackHeadPosition < framesWritten && System.nanoTime() < deadline && !stopRequested) {
                        Thread.sleep(10)
                    }
                }
                publishDiagnostics()
            }
            publishState(if (stopRequested) PlayerPhase.IDLE else PlayerPhase.ENDED)
        } catch (e: Exception) {
            listener.onPlayerState(PlayerState(PlayerPhase.ERROR, title, error = e.message ?: e.javaClass.simpleName))
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            try { extractor?.release() } catch (_: Exception) { }
            val t = out
            if (t != null) {
                routingListener?.let { try { t.removeOnRoutingChangedListener(it) } catch (_: Exception) { } }
                try { t.release() } catch (_: Exception) { }
            }
            track = null
            listener.onChain(null)
            listener.onMeters(MeterSnapshot.SILENT)
        }
    }

    private companion object {
        const val BLOCK_FRAMES = 1024
        const val BUFFER_MULTIPLIER = 4
        const val TIMEOUT_US = 10_000L
        const val DUCK_VOLUME = 0.2f
        const val CLIP_LEVEL = 0.9999f
        const val PUBLISH_INTERVAL_NS = 250_000_000L
        const val METER_INTERVAL_NS = 40_000_000L
    }
}
