package com.senzaproject.gamespace.navigation

object Routes {
    const val Splash = "splash"
    const val Onboarding = "onboarding"
    const val PermissionSetup = "permission_setup"
    const val Home = "home"
    const val Library = "library"
    const val ArgPackage = "packageName"
    const val GameDetails = "game_details/{$ArgPackage}"
    const val GameMode = "game_mode"
    const val FpsMonitor = "fps_monitor"
    const val DeviceMonitor = "device_monitor"
    const val Toolbox = "toolbox"
    const val FloatingPanel = "floating_panel"
    const val Network = "network"
    const val Statistics = "statistics"
    const val PerGame = "per_game/{$ArgPackage}"
    const val Settings = "settings"
    const val About = "about"

    fun gameDetails(packageName: String) = "game_details/$packageName"
    fun perGame(packageName: String) = "per_game/$packageName"
}
