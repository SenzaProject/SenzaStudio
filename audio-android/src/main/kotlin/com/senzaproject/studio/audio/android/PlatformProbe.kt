package com.senzaproject.studio.audio.android

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import com.senzaproject.studio.audio.engine.DeviceType
import com.senzaproject.studio.audio.engine.OutputDevice
import com.senzaproject.studio.audio.engine.PlatformFacts
import com.senzaproject.studio.audio.engine.ProbeResult

internal fun AudioDeviceInfo.toOutputDevice(): OutputDevice {
    val kind = DeviceType.fromAndroidType(type)
    val label = productName?.toString().orEmpty().ifBlank { kind.displayName }
    return OutputDevice(id, kind, label, sampleRates.toList(), channelCounts.toList())
}

/** Gathers the facts the pure [com.senzaproject.studio.audio.engine.CompatibilityEvaluator] needs. */
object PlatformProbe {
    fun collect(context: Context, devices: List<OutputDevice>): PlatformFacts {
        val pm = context.packageManager
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return PlatformFacts(
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            lowLatencyFeature = pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY),
            proAudioFeature = pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO),
            nativeSampleRateHz = audio.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull(),
            nativeFramesPerBuffer = audio.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull(),
            dynamicsProcessing = probeDynamicsProcessing(),
            devices = devices,
        )
    }

    /** Really tries to create the effect on a throw-away audio session, then releases everything. */
    fun probeDynamicsProcessing(): ProbeResult {
        var track: AudioTrack? = null
        var effect: DynamicsProcessing? = null
        return try {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48_000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()
            val size = AudioTrack.getMinBufferSize(48_000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder().setAudioFormat(format).setBufferSizeInBytes(size).build()
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2, true, 1, false, 0, false, 0, true,
            ).build()
            effect = DynamicsProcessing(0, track.audioSessionId, config)
            ProbeResult.Supported
        } catch (e: Exception) {
            ProbeResult.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            try { effect?.release() } catch (_: Exception) { }
            try { track?.release() } catch (_: Exception) { }
        }
    }
}
