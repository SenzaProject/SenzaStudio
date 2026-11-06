package com.senzaproject.gamespace.core.gaming

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

sealed interface LaunchResult {
    data object Success : LaunchResult
    /** Not installed, disabled, or no launcher activity. */
    data object NotLaunchable : LaunchResult
    data class Failed(val message: String?) : LaunchResult
}

@Singleton
class GameLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun launch(packageName: String): LaunchResult {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return LaunchResult.NotLaunchable
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            LaunchResult.Success
        } catch (e: Exception) {
            LaunchResult.Failed(e.message)
        }
    }
}
