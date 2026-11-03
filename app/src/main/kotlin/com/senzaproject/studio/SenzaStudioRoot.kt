package com.senzaproject.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.senzaproject.studio.audio.engine.DspModule
import com.senzaproject.studio.core.design.SenzaColors
import com.senzaproject.studio.feature.equalizer.EqualizerScreen
import com.senzaproject.studio.feature.presets.PresetsScreen
import com.senzaproject.studio.feature.settings.AppInfo
import com.senzaproject.studio.feature.settings.SettingsScreen
import com.senzaproject.studio.feature.spatial.SpatialScreen
import com.senzaproject.studio.feature.studio.StudioScreen

@Composable
fun SenzaStudioRoot(appInfo: AppInfo) {
    val vm: EngineViewModel = viewModel(factory = EngineViewModel.Factory)
    val engineState by vm.engineState.collectAsStateWithLifecycle()
    val parameters by vm.parameters.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = Destination.fromRoute(backStack?.destination?.route)

    Scaffold(
        containerColor = SenzaColors.Background,
        bottomBar = {
            SenzaBottomBar(current = current) { target ->
                navController.navigate(target.route) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.start.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Destination.STUDIO.route) { StudioScreen(engineState, vm.capabilities) }
            composable(Destination.EQUALIZER.route) {
                EqualizerScreen(parameters.eq, vm.capabilities.of(DspModule.EQUALIZER))
            }
            composable(Destination.SPATIAL.route) {
                SpatialScreen(parameters.spatial, vm.capabilities.of(DspModule.SPATIAL))
            }
            composable(Destination.PRESETS.route) { PresetsScreen(parameters.schemaVersion) }
            composable(Destination.SETTINGS.route) { SettingsScreen(appInfo, vm.capabilities) }
        }
    }
}

@Composable
private fun SenzaBottomBar(current: Destination?, onSelect: (Destination) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SenzaColors.Surface)
            .navigationBarsPadding(),
    ) {
        Destination.entries.forEach { destination ->
            val selected = destination == current
            Text(
                text = destination.label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) SenzaColors.Accent else SenzaColors.TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(destination) })
                    .padding(vertical = 18.dp),
            )
        }
    }
}
