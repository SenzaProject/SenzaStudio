package com.senza.studio

import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import kotlin.system.exitProcess

/**
 * Titik masuk compiler Kotlin. Dijalankan lewat `app_process` sebagai PROSES TERPISAH dari
 * aplikasi (lihat [KotlinSupport]), jadi kalau memori habis hanya proses ini yang mati,
 * Senza Studio tetap hidup.
 *
 * Argumen = argumen kotlinc biasa. Pesan error dicetak ke System.err (dibaca oleh builder).
 */
object KotlinCompilerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        var code = 3
        // kotlinc rekursif dalam; thread utama terlalu kecil stack-nya, jadi pakai thread sendiri.
        val t = Thread(null, {
            code = try {
                System.setProperty("java.awt.headless", "true")
                System.setProperty("kotlin.colors.enabled", "false")
                K2JVMCompiler().exec(System.err, *args).code
            } catch (e: OutOfMemoryError) {
                System.err.println("error: OutOfMemoryError (heap proses Kotlin habis)")
                4
            } catch (e: Throwable) {
                System.err.println("error: kompiler Kotlin gagal dimulai: ${e.javaClass.name}: ${e.message}")
                e.printStackTrace()
                3
            }
        }, "kotlinc", 512L * 1024 * 1024)
        t.start()
        t.join()
        System.out.flush()
        System.err.flush()
        exitProcess(code)
    }
}
