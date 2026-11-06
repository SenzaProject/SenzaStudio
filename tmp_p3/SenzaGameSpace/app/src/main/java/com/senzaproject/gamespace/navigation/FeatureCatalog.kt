package com.senzaproject.gamespace.navigation

import androidx.annotation.StringRes
import com.senzaproject.gamespace.R
import com.senzaproject.gamespace.core.common.FeatureStatus

data class FeatureEntry(
    val route: String,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int?,
    val status: FeatureStatus,
    val phase: Int = 0,
    /** True when the route needs a game package name, so it cannot be opened from a plain list. */
    val requiresArgument: Boolean = false,
)

/**
 * Single source of truth for what is built and what is not.
 * Splash, Onboarding and Home are the other three of the 16 required screens.
 */
object FeatureCatalog {
    val entries: List<FeatureEntry> = listOf(
        FeatureEntry(Routes.PermissionSetup, R.string.screen_permissions, null, FeatureStatus.IMPLEMENTED),
        FeatureEntry(Routes.DeviceMonitor, R.string.screen_device_monitor, null, FeatureStatus.PARTIAL, 4),
        FeatureEntry(Routes.Library, R.string.screen_library, null, FeatureStatus.IMPLEMENTED),
        FeatureEntry(Routes.GameDetails, R.string.screen_game_details, null, FeatureStatus.IMPLEMENTED, requiresArgument = true),
        FeatureEntry(Routes.PerGame, R.string.screen_per_game, null, FeatureStatus.PARTIAL, requiresArgument = true),
        FeatureEntry(Routes.GameMode, R.string.screen_game_mode, R.string.desc_game_mode, FeatureStatus.PLANNED, 5),
        FeatureEntry(Routes.FpsMonitor, R.string.screen_fps_monitor, R.string.desc_fps_monitor, FeatureStatus.PLANNED, 4),
        FeatureEntry(Routes.Network, R.string.screen_network, R.string.desc_network, FeatureStatus.PLANNED, 4),
        FeatureEntry(Routes.Toolbox, R.string.screen_toolbox, R.string.desc_toolbox, FeatureStatus.PLANNED, 5),
        FeatureEntry(Routes.FloatingPanel, R.string.screen_floating_panel, R.string.desc_floating_panel, FeatureStatus.PLANNED, 5),
        FeatureEntry(Routes.Statistics, R.string.screen_statistics, R.string.desc_statistics, FeatureStatus.PLANNED, 5),
        FeatureEntry(Routes.Settings, R.string.screen_settings, null, FeatureStatus.IMPLEMENTED),
        FeatureEntry(Routes.About, R.string.screen_about, null, FeatureStatus.IMPLEMENTED),
    )

    val planned: List<FeatureEntry> get() = entries.filter { it.status == FeatureStatus.PLANNED }
}
