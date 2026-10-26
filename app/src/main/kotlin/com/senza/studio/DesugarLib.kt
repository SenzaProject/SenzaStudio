package com.senza.studio

import java.io.File
import java.util.zip.ZipFile

/**
 * Bahan core library desugaring (API Java baru seperti java.time di HP lama):
 * jar implementasi `desugar_jdk_libs` (kelas `j$.*`) dan JSON konfigurasinya.
 * Jar implementasi TIDAK ikut daftar library biasa; ia diubah ke dex terpisah oleh L8.
 */
class DesugarLib(val coord: MavenCoord, val json: String, val jar: File) {
    val key get() = coord.toString()

    companion object {
        /** Unduh jar desugar_jdk_libs dan artifact konfigurasinya (versi sama) lewat [resolver]. */
        fun prepare(coord: MavenCoord, resolver: MavenResolver, log: (String) -> Unit): DesugarLib {
            val impl = resolver.resolve(listOf(coord))
                .firstOrNull { it.coord.key == coord.key }?.jars?.firstOrNull()
                ?: throw BuildError(BuildError.Kind.OTHER, "Berkas jar ${coord} tidak ditemukan untuk core library desugaring.")

            val nio = coord.artifact.endsWith("_nio")
            val cfgNames = if (nio) listOf("desugar_jdk_libs_configuration_nio", "desugar_jdk_libs_configuration")
            else listOf("desugar_jdk_libs_configuration")

            var json: String? = null
            var lastError: String? = null
            for (name in cfgNames) {
                try {
                    val cfgCoord = MavenCoord(coord.group, name, coord.version)
                    val cfgJar = resolver.resolve(listOf(cfgCoord))
                        .firstOrNull { it.coord.key == cfgCoord.key }?.jars?.firstOrNull() ?: continue
                    json = readJson(cfgJar, nio)
                    if (json != null) {
                        log("  konfigurasi desugaring dari $name:${coord.version}")
                        break
                    }
                } catch (e: BuildError) {
                    lastError = e.message
                }
            }
            if (json == null) {
                throw BuildError(
                    BuildError.Kind.OTHER,
                    "Konfigurasi desugaring untuk $coord tidak ditemukan." +
                        (lastError?.let { "\n($it)" } ?: "") +
                        "\nPakai versi desugar_jdk_libs yang ada di Google Maven, mis. com.android.tools:desugar_jdk_libs:2.0.4"
                )
            }
            return DesugarLib(coord, json, impl)
        }

        /** Cari META-INF/desugar/d8/desugar*.json di dalam jar konfigurasi. */
        private fun readJson(jar: File, nio: Boolean): String? {
            ZipFile(jar).use { z ->
                val names = z.entries().asSequence()
                    .map { it.name }
                    .filter { it.startsWith("META-INF/desugar/d8/") && it.endsWith(".json") }
                    .toList()
                val pick = (if (nio) names.firstOrNull { it.contains("nio", ignoreCase = true) } else null)
                    ?: names.firstOrNull { it.substringAfterLast('/') == "desugar.json" }
                    ?: names.firstOrNull()
                    ?: return null
                return z.getInputStream(z.getEntry(pick)).use { it.readBytes().toString(Charsets.UTF_8) }
            }
        }
    }
}
