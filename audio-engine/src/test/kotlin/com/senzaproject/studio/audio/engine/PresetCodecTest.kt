package com.senzaproject.studio.audio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetCodecTest {
    private val rich = EngineParameters(
        bypass = true,
        masterGainDb = -4.5f,
        eq = EqParameters(
            enabled = true,
            bands = listOf(
                EqBand(100f, 3.5f, 0.7f, FilterType.LOW_SHELF),
                EqBand(1_000f, -2f, 2.5f),
                EqBand(12_000f, 4f, 1f, FilterType.HIGH_SHELF),
            ),
        ),
        bass = BassParameters(true, 5f, 0.6f, 0.4f, 120f),
        dynamics = DynamicsParameters(
            compressor = CompressorParameters(true, -24f, 3f, 5f, 200f, 8f, 3f),
            limiter = LimiterParameters(-1f, 120f),
            loudness = LoudnessParameters(true, -18f, 8f, 10f, 4f),
        ),
        spatial = SpatialParameters(true, SpatialMode.IMMERSIVE, 0.8f, 1.4f),
        imaging = ImagingParameters(true, 2f, -3f, 0.25f, 150f),
    ).sanitized()

    private fun ok(text: String) = decode(text) as PresetDecodeResult.Ok

    private fun decode(text: String) = PresetCodec.decode(text)

    @Test
    fun everyFieldSurvivesARoundTrip() {
        val result = ok(PresetCodec.encode(rich, "My preset"))
        assertEquals(rich, result.parameters)
        assertEquals("My preset", result.name)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun defaultsRoundTrip() {
        assertEquals(EngineParameters().sanitized(), ok(PresetCodec.encode(EngineParameters())).parameters)
    }

    @Test
    fun missingKeysTakeDefaultsAndUnknownKeysAreIgnored() {
        val r = ok("senza-preset=1\nmaster_db=-6\nfuture.feature=42\n")
        assertEquals(-6f, r.parameters.masterGainDb, 0f)
        assertEquals(EngineParameters().bass, r.parameters.bass)
        assertEquals(EqParameters.defaultBands(), r.parameters.eq.bands)
    }

    @Test
    fun invalidValuesFallBackWithAWarning() {
        val r = ok("senza-preset=1\nmaster_db=loud\nbass.enabled=maybe\neq.band.0=abc\nspatial.mode=DISCO\n")
        assertEquals(EngineParameters().masterGainDb, r.parameters.masterGainDb, 0f)
        assertEquals(4, r.warnings.size)
    }

    @Test
    fun outOfRangeValuesAreClamped() {
        val r = ok("senza-preset=1\nmaster_db=500\nbass.sub_db=-99\nimaging.balance=7\n")
        assertEquals(EngineParameters.MAX_MASTER_GAIN_DB, r.parameters.masterGainDb, 0f)
        assertEquals(-BassParameters.MAX_SUB_BASS_DB, r.parameters.bass.subBassDb, 0f)
        assertEquals(1f, r.parameters.imaging.balance, 0f)
    }

    @Test
    fun nanAndInfinityAreRejected() {
        val r = ok("senza-preset=1\nmaster_db=NaN\nbass.punch=Infinity\n")
        assertEquals(0f, r.parameters.masterGainDb, 0f)
        assertEquals(0f, r.parameters.bass.punch, 0f)
        assertEquals(2, r.warnings.size)
    }

    @Test
    fun textThatIsNotAPresetOrIsTooNewIsRejected() {
        assertTrue(decode("hello world") is PresetDecodeResult.Error)
        assertTrue(decode("") is PresetDecodeResult.Error)
        val newer = decode("senza-preset=${PresetCodec.FORMAT_VERSION + 1}\n")
        assertTrue(newer is PresetDecodeResult.Error && newer.reason.contains("newer"))
    }

    @Test
    fun commentsBlankLinesAndWindowsLineEndingsAreTolerated() {
        val r = ok("# comment\r\n\r\nsenza-preset=1\r\nmaster_db=-2\r\n")
        assertEquals(-2f, r.parameters.masterGainDb, 0f)
    }

    @Test
    fun namesCannotInjectExtraLines() {
        val r = ok(PresetCodec.encode(EngineParameters(), "Evil\nmaster_db=-20"))
        assertEquals(0f, r.parameters.masterGainDb, 0f)
    }
}
