package com.senzaproject.gamespace.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.senzaproject.gamespace.R
import com.senzaproject.gamespace.core.settings.SettingsViewModel
import com.senzaproject.gamespace.feature.about.AboutScreen
import com.senzaproject.gamespace.feature.devicemonitor.DeviceMonitorScreen
import com.senzaproject.gamespace.feature.home.HomeScreen
import com.senzaproject.gamespace.feature.library.GameDetailsScreen
import com.senzaproject.gamespace.feature.library.LibraryScreen
import com.senzaproject.gamespace.feature.library.PerGameSettingsScreen
import com.senzaproject.gamespace.feature.onboarding.OnboardingScreen
import com.senzaproject.gamespace.feature.permissions.PermissionSetupScreen
import com.senzaproject.gamespace.feature.planned.PlannedFeatureScreen
import com.senzaproject.gamespace.feature.settings.SettingsScreen
import com.senzaproject.gamespace.feature.splash.SplashScreen

private data class BottomDestination(val route: String, val labelRes: Int, val icon: ImageVector)

private val bottomDestinations = listOf(
    BottomDestination(Routes.Home, R.string.nav_home, Icons.Filled.Home),
    BottomDestination(Routes.Library, R.string.nav_library, Icons.AutoMirrored.Filled.List),
    BottomDestination(Routes.Toolbox, R.string.nav_toolbox, Icons.Filled.Build),
    BottomDestination(Routes.Settings, R.string.nav_settings, Icons.Filled.Settings),
)

@Composable
fun GameMasterRoot(settingsViewModel: SettingsViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val keepScreenOn by settingsViewModel.keepScreenOn.collectAsStateWithLifecycle()
    val view = LocalView.current

    // Applies the "Keep screen on" setting to the window.
    LaunchedEffect(keepScreenOn) { view.keepScreenOn = keepScreenOn }

    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val showBottomBar = bottomDestinations.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomDestinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(Routes.Home) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(stringResource(destination.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        GameMasterNavHost(navController, Modifier.padding(innerPadding))
    }
}

@Composable
private fun GameMasterNavHost(navController: NavHostController, modifier: Modifier) {
    val bottomRoutes = bottomDestinations.map { it.route }
    NavHost(navController = navController, startDestination = Routes.Splash, modifier = modifier) {
        composable(Routes.Splash) {
            SplashScreen(
                onFinished = { onboardingDone ->
                    navController.navigate(if (onboardingDone) Routes.Home else Routes.Onboarding) {
                        popUpTo(Routes.Splash) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.Onboarding) {
            OnboardingScreen(
                onFinished = {
                    navController.navigate(Routes.PermissionSetup) {
                        popUpTo(Routes.Onboarding) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.PermissionSetup) {
            PermissionSetupScreen(
                onContinue = {
                    // From Settings, Home is already on the stack. From onboarding it is not.
                    if (!navController.popBackStack(Routes.Home, inclusive = false)) {
                        navController.navigate(Routes.Home) {
                            popUpTo(navController.graph.id) { inclusive = true }
                        }
                    }
                },
                onBack = if (navController.previousBackStackEntry != null) {
                    { navController.popBackStack() }
                } else {
                    null
                },
            )
        }
        composable(Routes.Home) {
            HomeScreen(
                onOpen = { route -> navController.navigate(route) { launchSingleTop = true } },
                onOpenGame = { pkg -> navController.navigate(Routes.gameDetails(pkg)) { launchSingleTop = true } },
            )
        }
        composable(Routes.Library) {
            LibraryScreen(onOpenGame = { pkg -> navController.navigate(Routes.gameDetails(pkg)) })
        }
        composable(
            route = Routes.GameDetails,
            arguments = listOf(navArgument(Routes.ArgPackage) { type = NavType.StringType }),
        ) {
            GameDetailsScreen(
                onBack = { navController.popBackStack() },
                onOpenSettings = { pkg -> navController.navigate(Routes.perGame(pkg)) },
            )
        }
        composable(
            route = Routes.PerGame,
            arguments = listOf(navArgument(Routes.ArgPackage) { type = NavType.StringType }),
        ) {
            PerGameSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.DeviceMonitor) {
            DeviceMonitorScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.Settings) {
            SettingsScreen(
                onOpenPermissions = { navController.navigate(Routes.PermissionSetup) },
                onOpenAbout = { navController.navigate(Routes.About) },
            )
        }
        composable(Routes.About) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
        plannedScreens(navController, bottomRoutes)
    }
}

private fun NavGraphBuilder.plannedScreens(navController: NavHostController, bottomRoutes: List<String>) {
    FeatureCatalog.planned.forEach { entry ->
        composable(entry.route) {
            PlannedFeatureScreen(
                entry = entry,
                onBack = if (entry.route in bottomRoutes) null else {
                    { navController.popBackStack() }
                },
            )
        }
    }
}
