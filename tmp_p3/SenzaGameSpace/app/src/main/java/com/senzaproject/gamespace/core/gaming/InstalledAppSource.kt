package com.senzaproject.gamespace.core.gaming

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads launchable apps from PackageManager. Uses the MAIN/LAUNCHER `<queries>` entry in the manifest,
 * which is the supported way to see apps on Android 11+ without the restricted QUERY_ALL_PACKAGES permission.
 */
@Singleton
class InstalledAppSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun launchableApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, 0)
            .mapNotNull { info ->
                val app = info.activityInfo?.applicationInfo ?: return@mapNotNull null
                if (app.packageName == context.packageName) return@mapNotNull null
                InstalledApp(app.packageName, info.loadLabel(pm).toString(), isDeclaredGame(app))
            }
            .distinctBy { it.packageName }
    }

    suspend fun details(packageName: String): AppDetails? = withContext(Dispatchers.IO) {
        try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(packageName, 0)
            AppDetails(info.versionName, info.firstInstallTime, info.lastUpdateTime)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    /** True only when the app (or its installer) declares it as a game. Undeclared games must be added manually. */
    @Suppress("DEPRECATION")
    private fun isDeclaredGame(app: ApplicationInfo): Boolean =
        app.category == ApplicationInfo.CATEGORY_GAME || (app.flags and ApplicationInfo.FLAG_IS_GAME) != 0
}
