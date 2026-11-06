package com.senzaproject.gamespace.core.permissions

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class AppPermission { USAGE_ACCESS, OVERLAY, NOTIFICATIONS, DND_ACCESS }

@Singleton
class PermissionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun isGranted(permission: AppPermission): Boolean = when (permission) {
        AppPermission.USAGE_ACCESS -> isUsageAccessGranted()
        AppPermission.OVERLAY -> Settings.canDrawOverlays(context)
        AppPermission.NOTIFICATIONS ->
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        AppPermission.DND_ACCESS ->
            context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
    }

    /** The system settings page where the user can grant [permission]. */
    fun settingsIntent(permission: AppPermission): Intent = when (permission) {
        AppPermission.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        AppPermission.OVERLAY ->
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        AppPermission.NOTIFICATIONS ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        AppPermission.DND_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
    }

    private fun isUsageAccessGranted(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
