package com.senzaproject.studio.audio.engine

/** Result of reading a preset from text. */
sealed interface PresetDecodeResult {
    data class Ok(val parameters: EngineParameters, val name: String?, val warnings: List<String>) : PresetDecodeResult
    data class Error(val reason: String) : PresetDecodeResult
}

/**
 * Versioned, human-readable text format for [EngineParameters] (one `key=value` per line).
 *
 * Rules that keep old presets working: keys that are missing take their defaults, unknown keys are
 * ignored, values that cannot be read fall back to the default (with a warning), and the result always
 * goes through [EngineParameters.sanitized]. A preset written by a NEWER format is rejected, never
 * guessed at.
 */
object PresetCodec {
    const val FORMAT_VERSION = 1
    private const val HEADER = "senza-preset"

    fun encode(p: EngineParameters, name: String? = null): String {
        val s = p.sanitized()
        val out = StringBuilder()
        fun line(key: String, value: Any) {
            out.append(key).append('=').append(value).append('\n')
        }
        fun flag(key: String, value: Boolean) = line(key, if (value) "1" else "0")

        line(HEADER, FORMAT_VERSION)
        line("schema", s.schemaVersion)
        if (name != null) line("name", name.replace('\n', ' ').trim())
        flag("bypass", s.bypass)
        line("master_db", s.masterGainDb)

        flag("eq.enabled", s.eq.enabled)
        s.eq.bands.forEachIndexed { i, b -> line("eq.band.$i", "${b.frequencyHz},${b.gainDb},${b.q},${b.type.name}") }

        flag("bass.enabled", s.bass.enabled)
        line("bass.sub_db", s.bass.subBassDb)
        line("bass.harmonics", s.bass.harmonics)
        line("bass.punch", s.bass.punch)
        line("bass.crossover_hz", s.bass.crossoverHz)

        val c = s.dynamics.compressor
        flag("comp.enabled", c.enabled)
        line("comp.threshold_db", c.thresholdDb)
        line("comp.ratio", c.ratio)
        line("comp.attack_ms", c.attackMs)
        line("comp.release_ms", c.releaseMs)
        line("comp.knee_db", c.kneeDb)
        line("comp.makeup_db", c.makeupDb)

        line("limiter.ceiling_db", s.dynamics.limiter.ceilingDb)
        line("limiter.release_ms", s.dynamics.limiter.releaseMs)

        val l = s.dynamics.loudness
        flag("loudness.enabled", l.enabled)
        line("loudness.target_lufs", l.targetLufs)
        line("loudness.max_boost_db", l.maxBoostDb)
        line("loudness.max_cut_db", l.maxCutDb)
        line("loudness.response_s", l.responseSeconds)

        flag("spatial.enabled", s.spatial.enabled)
        line("spatial.mode", s.spatial.mode.name)
        line("spatial.intensity", s.spatial.intensity)
        line("spatial.width", s.spatial.stereoWidth)

        flag("imaging.enabled", s.imaging.enabled)
        line("imaging.mid_db", s.imaging.midGainDb)
        line("imaging.side_db", s.imaging.sideGainDb)
        line("imaging.balance", s.imaging.balance)
        line("imaging.mono_below_hz", s.imaging.monoBelowHz)
        return out.toString()
    }

    fun decode(text: String): PresetDecodeResult {
        val map = HashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            map[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
        }
        val version = map[HEADER]?.toIntOrNull()
            ?: return PresetDecodeResult.Error("Not a Senza preset")
        if (version > FORMAT_VERSION) {
            return PresetDecodeResult.Error("Preset was made by a newer version of Senza Studio (format $version)")
        }

        val warnings = ArrayList<String>()
        fun float(key: String, default: Float): Float {
            val v = map[key] ?: return default
            val f = v.toFloatOrNull()
            if (f == null || !f.isFinite()) {
                warnings += "Ignored invalid value for $key"
                return default
            }
            return f
        }
        fun bool(key: String, default: Boolean): Boolean = when (map[key]) {
            null -> default
            "1", "true" -> true
            "0", "false" -> false
            else -> {
                warnings += "Ignored invalid value for $key"
                default
            }
        }
        fun <T : Enum<T>> enumOf(key: String, values: Array<T>, default: T): T {
            val v = map[key] ?: return default
            return values.firstOrNull { it.name == v } ?: run {
                warnings += "Ignored unknown value for $key"
                default
            }
        }

        val bands = ArrayList<EqBand>()
        for (i in 0 until EqParameters.MAX_BANDS) {
            val raw = map["eq.band.$i"] ?: continue
            val parts = raw.split(',')
            val f = parts.getOrNull(0)?.toFloatOrNull()
            val g = parts.getOrNull(1)?.toFloatOrNull()
            val q = parts.getOrNull(2)?.toFloatOrNull()
            val type = FilterType.entries.firstOrNull { it.name == parts.getOrNull(3) }
            if (f == null || g == null || q == null || type == null || !f.isFinite() || !g.isFinite() || !q.isFinite()) {
                warnings += "Ignored invalid EQ band $i"
            } else {
                bands += EqBand(f, g, q, type)
            }
        }

        val d = EngineParameters()
        val params = EngineParameters(
            bypass = bool("bypass", d.bypass),
            masterGainDb = float("master_db", d.masterGainDb),
            eq = EqParameters(
                enabled = bool("eq.enabled", d.eq.enabled),
                bands = if (bands.isEmpty()) EqParameters.defaultBands() else bands,
            ),
            bass = BassParameters(
                enabled = bool("bass.enabled", d.bass.enabled),
                subBassDb = float("bass.sub_db", d.bass.subBassDb),
                harmonics = float("bass.harmonics", d.bass.harmonics),
                punch = float("bass.punch", d.bass.punch),
                crossoverHz = float("bass.crossover_hz", d.bass.crossoverHz),
            ),
            dynamics = DynamicsParameters(
                compressor = CompressorParameters(
                    enabled = bool("comp.enabled", d.dynamics.compressor.enabled),
                    thresholdDb = float("comp.threshold_db", d.dynamics.compressor.thresholdDb),
                    ratio = float("comp.ratio", d.dynamics.compressor.ratio),
                    attackMs = float("comp.attack_ms", d.dynamics.compressor.attackMs),
                    releaseMs = float("comp.release_ms", d.dynamics.compressor.releaseMs),
                    kneeDb = float("comp.knee_db", d.dynamics.compressor.kneeDb),
                    makeupDb = float("comp.makeup_db", d.dynamics.compressor.makeupDb),
                ),
                limiter = LimiterParameters(
                    ceilingDb = float("limiter.ceiling_db", d.dynamics.limiter.ceilingDb),
                    releaseMs = float("limiter.release_ms", d.dynamics.limiter.releaseMs),
                ),
                loudness = LoudnessParameters(
                    enabled = bool("loudness.enabled", d.dynamics.loudness.enabled),
                    targetLufs = float("loudness.target_lufs", d.dynamics.loudness.targetLufs),
                    maxBoostDb = float("loudness.max_boost_db", d.dynamics.loudness.maxBoostDb),
                    maxCutDb = float("loudness.max_cut_db", d.dynamics.loudness.maxCutDb),
                    responseSeconds = float("loudness.response_s", d.dynamics.loudness.responseSeconds),
                ),
            ),
            spatial = SpatialParameters(
                enabled = bool("spatial.enabled", d.spatial.enabled),
                mode = enumOf("spatial.mode", SpatialMode.entries.toTypedArray(), d.spatial.mode),
                intensity = float("spatial.intensity", d.spatial.intensity),
                stereoWidth = float("spatial.width", d.spatial.stereoWidth),
            ),
            imaging = ImagingParameters(
                enabled = bool("imaging.enabled", d.imaging.enabled),
                midGainDb = float("imaging.mid_db", d.imaging.midGainDb),
                sideGainDb = float("imaging.side_db", d.imaging.sideGainDb),
                balance = float("imaging.balance", d.imaging.balance),
                monoBelowHz = float("imaging.mono_below_hz", d.imaging.monoBelowHz),
            ),
        ).sanitized()
        return PresetDecodeResult.Ok(params, map["name"]?.takeIf { it.isNotBlank() }, warnings)
    }
}
