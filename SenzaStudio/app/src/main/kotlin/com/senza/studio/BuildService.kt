package com.senza.studio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Status bersama antara layar, builder, dan service. */
object BuildControl {
    @Volatile var cancelled = false
    @Volatile var onCancel: (() -> Unit)? = null

    fun request() {
        cancelled = true
        onCancel?.invoke()
    }

    fun reset() { cancelled = false }
}

/**
 * Foreground service: menjaga proses tetap hidup saat build berjalan di latar belakang,
 * menampilkan notifikasi progres dan tombol Batal.
 */
class BuildService : Service() {

    companion object {
        private const val CHANNEL_PROGRESS = "build_progress"
        private const val CHANNEL_DONE = "build_done"
        private const val ID_PROGRESS = 41
        private const val ID_DONE = 42
        const val ACTION_CANCEL = "com.senza.studio.CANCEL_BUILD"

        @Volatile private var running = false

        fun start(ctx: Context, project: String) {
            try {
                val i = Intent(ctx, BuildService::class.java).putExtra("project", project)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Throwable) {
                // tanpa service build tetap jalan selama aplikasi terbuka
            }
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, BuildService::class.java)) } catch (_: Throwable) {}
        }

        fun update(ctx: Context, project: String, n: Int, total: Int, text: String) {
            if (!running) return
            try {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(ID_PROGRESS, progressNotification(ctx, project, n, total, text))
            } catch (_: Throwable) {}
        }

        /** Notifikasi hasil build (tetap ada setelah service berhenti). */
        fun notifyDone(ctx: Context, project: String, success: Boolean, detail: String) {
            try {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(ID_PROGRESS)
                ensureChannels(nm)
                val open = PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL_DONE) else Notification.Builder(ctx)
                b.setSmallIcon(if (success) R.drawable.ic_check else R.drawable.ic_warning)
                    .setContentTitle(if (success) "Build selesai: $project" else "Build gagal: $project")
                    .setContentText(detail)
                    .setStyle(Notification.BigTextStyle().bigText(detail))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                nm.notify(ID_DONE, b.build())
            } catch (_: Throwable) {}
        }

        private fun ensureChannels(nm: NotificationManager) {
            if (Build.VERSION.SDK_INT < 26) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "Progres build", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, "Hasil build", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        private fun progressNotification(ctx: Context, project: String, n: Int, total: Int, text: String): Notification {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannels(nm)
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val cancel = PendingIntent.getService(
                ctx, 1, Intent(ctx, BuildService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL_PROGRESS) else Notification.Builder(ctx)
            b.setSmallIcon(R.drawable.ic_build)
                .setContentTitle("Membangun $project")
                .setContentText(if (n > 0) "Langkah $n dari $total: $text" else text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(null, "Batal", cancel).build())
            if (total > 0) b.setProgress(total, n.coerceIn(0, total), n == 0)
            return b.build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            BuildControl.request()
            return START_NOT_STICKY
        }
        val project = intent?.getStringExtra("project") ?: "proyek"
        val n = progressNotification(this, project, 0, MainActivity.TASKS.size, "Menyiapkan…")
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(ID_PROGRESS, n)
            }
            running = true
        } catch (_: Throwable) {
            running = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
