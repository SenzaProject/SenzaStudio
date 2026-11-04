package com.senzaproject.studio.audio.engine

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEffectPlanTest {
    @Test
    fun edgesAreGeometricMidpointsAndStrictlyIncreasing() {
        val plan = SessionEffectPlanner.plan(EngineParameters(eq = EqParameters(enabled = true)))
        val f = EqParameters.GRAPHIC_FREQUENCIES_HZ
        assertEquals(10, plan.eqBands.size)
        assertEquals(sqrt(f[0] * f[1]), plan.eqBands[0].cutoffHz, 0.01f)
        assertEquals(20_000f, plan.eqBands.last().cutoffHz, 0f)
        for (i in 1 until plan.eqBands.size) assertTrue(plan.eqBands[i].cutoffHz > plan.eqBands[i - 1].cutoffHz)
    }

    @Test
    fun disabledEqProducesFlatGains() {
        val bands = EqParameters.defaultBands().map { it.copy(gainDb = 9f) }
        val plan = SessionEffectPlanner.plan(EngineParameters(eq = EqParameters(enabled = false, bands = bands)))
        assertTrue(plan.eqBands.all { it.gainDb == 0f })
    }

    @Test
    fun enabledEqKeepsBandGains() {
        val bands = EqParameters.defaultBands().mapIndexed { i, b -> b.copy(gainDb = i.toFloat()) }
        val plan = SessionEffectPlanner.plan(EngineParameters(eq = EqParameters(enabled = true, bands = bands)))
        assertEquals(listOf(0f, 1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f), plan.eqBands.map { it.gainDb })
    }

    @Test
    fun limiterAndInputGainComeFromTheEngineParameters() {
        val plan = SessionEffectPlanner.plan(
            EngineParameters(
                masterGainDb = -4f,
                dynamics = DynamicsParameters(limiter = LimiterParameters(ceilingDb = -2f, releaseMs = 200f)),
            ),
        )
        assertEquals(-4f, plan.inputGainDb, 0f)
        assertEquals(-2f, plan.limiterThresholdDb, 0f)
        assertEquals(200f, plan.limiterReleaseMs, 0f)
    }

    @Test
    fun unsupportedModulesAreListedWhenEnabled() {
        val plan = SessionEffectPlanner.plan(
            EngineParameters(
                bass = BassParameters(enabled = true),
                spatial = SpatialParameters(enabled = true),
                imaging = ImagingParameters(enabled = true),
                dynamics = DynamicsParameters(
                    compressor = CompressorParameters(enabled = true),
                    loudness = LoudnessParameters(enabled = true),
                ),
            ),
        )
        assertEquals(5, plan.ignored.size)
        assertTrue(SessionEffectPlanner.plan(EngineParameters()).ignored.isEmpty())
    }
}
