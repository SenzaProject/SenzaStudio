package com.senzaproject.studio.audio.engine

import java.io.File

class PresetStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** User presets plus the files that could not be read (so the UI can say so instead of hiding them). */
data class PresetListing(val presets: List<Preset>, val problems: List<String>)

/**
 * File-based persistence for the current settings, user presets and per-device profiles, in the
 * versioned [PresetCodec] text format. Writes go to a temporary file first and are renamed into place,
 * so a crash cannot leave a half-written preset. Every failure is reported as a [PresetStoreException]
 * with a message the UI can show.
 */
class FilePresetStore(private val root: File) {
    private val presetDir get() = File(root, "presets")
    private val profileDir get() = File(root, "profiles")
    private val currentFile get() = File(root, "current.senza")

    fun loadCurrent(): EngineParameters? = readParameters(currentFile)

    fun saveCurrent(parameters: EngineParameters) = write(currentFile, PresetCodec.encode(parameters))

    fun listUser(): PresetListing {
        val dir = presetDir
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) }?.sortedBy { it.name }.orEmpty()
        val presets = ArrayList<Preset>()
        val problems = ArrayList<String>()
        for (f in files) {
            val id = f.name.removeSuffix(EXTENSION)
            when (val r = decodeFile(f)) {
                is PresetDecodeResult.Ok -> presets += Preset(id, r.name ?: id, r.parameters, factory = false)
                is PresetDecodeResult.Error -> problems += "${f.name}: ${r.reason}"
            }
        }
        return PresetListing(presets.sortedBy { it.name.lowercase() }, problems)
    }

    fun saveUser(name: String, parameters: EngineParameters, now: Long = System.currentTimeMillis()): Preset {
        val clean = name.trim().replace('\n', ' ')
        if (clean.isEmpty()) throw PresetStoreException("Give the preset a name")
        if (clean.length > MAX_NAME_LENGTH) throw PresetStoreException("Preset names can have at most $MAX_NAME_LENGTH characters")
        var id = "user.$now"
        var suffix = 1
        while (File(presetDir, id + EXTENSION).exists()) id = "user.$now-${suffix++}"
        write(File(presetDir, id + EXTENSION), PresetCodec.encode(parameters, clean))
        return Preset(id, clean, parameters.sanitized(), factory = false)
    }

    fun deleteUser(id: String) {
        if (!ID_PATTERN.matches(id)) throw PresetStoreException("Only user presets can be deleted")
        val f = File(presetDir, id + EXTENSION)
        if (f.exists() && !f.delete()) throw PresetStoreException("Could not delete the preset file")
    }

    fun loadProfiles(): Map<DeviceType, EngineParameters> {
        val out = HashMap<DeviceType, EngineParameters>()
        for (type in DeviceType.entries) {
            val p = try { readParameters(File(profileDir, type.name + EXTENSION)) } catch (_: PresetStoreException) { null }
            if (p != null) out[type] = p
        }
        return out
    }

    fun saveProfiles(profiles: Map<DeviceType, EngineParameters>) {
        for ((type, p) in profiles) write(File(profileDir, type.name + EXTENSION), PresetCodec.encode(p))
    }

    private fun readParameters(file: File): EngineParameters? {
        if (!file.exists()) return null
        return when (val r = decodeFile(file)) {
            is PresetDecodeResult.Ok -> r.parameters
            is PresetDecodeResult.Error -> throw PresetStoreException("${file.name}: ${r.reason}")
        }
    }

    private fun decodeFile(file: File): PresetDecodeResult =
        try {
            PresetCodec.decode(file.readText())
        } catch (e: Exception) {
            PresetDecodeResult.Error("could not be read (${e.message ?: e.javaClass.simpleName})")
        }

    private fun write(target: File, text: String) {
        try {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw PresetStoreException("Could not write ${target.name}")
            }
        } catch (e: PresetStoreException) {
            throw e
        } catch (e: Exception) {
            throw PresetStoreException("Could not write ${target.name}: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private companion object {
        const val EXTENSION = ".senza"
        const val MAX_NAME_LENGTH = 40
        val ID_PATTERN = Regex("user\\.[0-9]+(-[0-9]+)?")
    }
}
