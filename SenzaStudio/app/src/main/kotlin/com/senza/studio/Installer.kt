package com.senza.studio

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File

/**
 * Pemasangan lewat PackageInstaller (sesi). Lebih andal daripada ACTION_VIEW:
 * hasilnya (sukses / gagal + alasan) dikirim balik ke aplikasi.
 */
object ApkInstaller {
    const val ACTION = "com.senza.studio.INSTALL_RESULT"

    /** Dipanggil saat hasil akhir diterima: (status PackageInstaller, pesan ramah, nama paket). */
    @Volatile var listener: ((Int, String, String?) -> Unit)? = null

    /** Mulai sesi pasang. Melempar exception jika sesi tidak bisa dibuat (pemanggil bisa mundur ke cara lama). */
    fun install(ctx: Context, apk: File, pkg: String?) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (pkg != null) params.setAppPackageName(pkg)
        params.setSize(apk.length())
        val id = pi.createSession(params)
        try {
            pi.openSession(id).use { s ->
                apk.inputStream().use { input ->
                    s.openWrite("base.apk", 0, apk.length()).use { o ->
                        input.copyTo(o)
                        s.fsync(o)
                    }
                }
                val intent = Intent(ctx, InstallResultReceiver::class.java).setAction(ACTION)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                s.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
            }
        } catch (e: Throwable) {
            try { pi.abandonSession(id) } catch (_: Throwable) {}
            throw e
        }
    }

    fun friendly(status: Int, msg: String?): String = when (status) {
        PackageInstaller.STATUS_SUCCESS -> "Aplikasi berhasil dipasang."
        PackageInstaller.STATUS_FAILURE_ABORTED -> "Pemasangan dibatalkan."
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Pemasangan diblokir oleh perangkat atau pengaturan keamanan."
        PackageInstaller.STATUS_FAILURE_CONFLICT ->
            "Bentrok dengan aplikasi yang sudah terpasang. Biasanya tanda tangannya berbeda: copot versi lama dulu, atau pakai keystore yang sama."
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "APK tidak cocok dengan perangkat ini (minSdk atau arsitektur)."
        PackageInstaller.STATUS_FAILURE_INVALID -> "APK dianggap tidak valid oleh sistem." + (msg?.let { " ($it)" } ?: "")
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Ruang penyimpanan tidak cukup."
        else -> "Pemasangan gagal." + (msg?.let { " ($it)" } ?: "")
    }

    internal fun report(ctx: Context, status: Int, msg: String?, pkg: String?) {
        val text = friendly(status, msg)
        val l = listener
        Handler(Looper.getMainLooper()).post {
            if (l != null) l(status, text, pkg)
            else Toast.makeText(ctx.applicationContext, text, Toast.LENGTH_LONG).show()
        }
    }
}

/** Menerima hasil sesi pasang. Jika butuh persetujuan pengguna, membuka dialog sistem. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = confirmIntent(intent)
            if (confirm != null) {
                try { ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Throwable) {
                    ApkInstaller.report(ctx, PackageInstaller.STATUS_FAILURE, "tidak bisa membuka dialog pasang: ${e.message}", pkg)
                }
            }
            return
        }
        ApkInstaller.report(ctx, status, msg, pkg)
    }

    @Suppress("DEPRECATION")
    private fun confirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_INTENT)
}
