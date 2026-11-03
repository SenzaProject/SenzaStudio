package com.senzaproject.studio.audio.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** DSP/engine code must stay independent from Android and UI. */
class ArchitectureTest {
    @Test
    fun engineSourcesDoNotImportAndroidOrCompose() {
        val root = File("src/main")
        assertTrue("src/main not found from ${File(".").absolutePath}", root.exists())
        val offenders = root.walkTopDown()
            .filter { it.extension == "kt" }
            .filter { f ->
                f.readLines().any { l ->
                    val t = l.trim()
                    t.startsWith("import android.") || t.startsWith("import androidx.")
                }
            }
            .map { it.name }
            .toList()
        assertTrue("Android/Compose imports in engine: $offenders", offenders.isEmpty())
    }
}
