package com.senzaproject.studio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
import kotlinx.coroutines.delay
import com.senzaproject.studio.core.design.SenzaColors
import com.senzaproject.studio.feature.equalizer.EqualizerScreen
import com.senzaproject.studio.feature.presets.PresetsScreen
import com.senzaproject.studio.feature.presets.PresetsUi
import com.senzaproject.studio.feature.settings.AppInfo
import com.senzaproject.studio.feature.settings.SessionEffectsUi
import com.senzaproject.studio.feature.settings.SettingsScreen
import com.senzaproject.studio.feature.settings.SettingsUi
import com.senzaproject.studio.feature.spatial.SpatialScreen
import com.senzaproject.studio.feature.studio.StudioScreen
import com.senzaproject.studio.feature.studio.StudioUi

@Composable
fun SenzaStudioRoot(appInfo: AppInfo) {
    val vm: EngineViewModel = viewModel(factory = EngineViewModel.Factory)
    val engineState by vm.engineState.collectAsStateWithLifecycle()
    val parameters by vm.parameters.collectAsStateWithLifecycle()
    val capabilities by vm.capabilities.collectAsStateWithLifecycle()
    val player by vm.playerState.collectAsStateWithLifecycle()
    val diagnostics by vm.diagnostics.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val route by vm.route.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    val sessionsEnabled by vm.sessionEffects.enabled.collectAsStateWithLifecycle()
    val sessions by vm.sessionEffects.sessions.collectAsStateWithLifecycle()
    val sessionPlan by vm.sessionEffects.currentPlan.collectAsStateWithLifecycle()
    val sessionError by vm.sessionEffects.error.collectAsStateWithLifecycle()
    val autoSwitch by vm.autoSwitchProfiles.collectAsStateWithLifecycle()
    val activeProfile by vm.activeProfile.collectAsStateWithLifecycle()
    val eqCurve by vm.eqCurve.collectAsStateWithLifecycle()
    val userPresets by vm.userPresets.collectAsStateWithLifecycle()
    val presetProblems by vm.presetProblems.collectAsStateWithLifecycle()
    val appliedPreset by vm.appliedPresetId.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    // Kept as a State (not unwrapped here) so only the meter cards recompose at 25 Hz, not the whole app.
    val meters = vm.meters.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.open(uri)
    }
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
        Column(modifier = Modifier.padding(padding)) {
            NavHost(
                navController = navController,
                startDestination = Destination.start.route,
                modifier = Modifier.weight(1f),
            ) {
                composable(Destination.STUDIO.route) {
                    StudioScreen(
                        ui = StudioUi(engineState, capabilities, parameters, player, route, devices, diagnostics, meters),
                        onOpenFile = { picker.launch(arrayOf("audio/*")) },
                        onPlayPause = vm::togglePlayPause,
                        onStop = vm::stopPlayback,
                        onEdit = vm::update,
                    )
                }
                composable(Destination.EQUALIZER.route) {
                    EqualizerScreen(parameters, eqCurve, capabilities, vm::update)
                }
                composable(Destination.SPATIAL.route) {
                    SpatialScreen(parameters, capabilities, vm::update)
                }
                composable(Destination.PRESETS.route) {
                    PresetsScreen(
                        ui = PresetsUi(parameters.schemaVersion, vm.factoryPresets, userPresets, presetProblems, appliedPreset),
                        onApply = vm::applyPreset,
                        onSave = vm::savePreset,
                        onDelete = vm::deletePreset,
                    )
                }
                composable(Destination.SETTINGS.route) {
                    SettingsScreen(
                        ui = SettingsUi(
                            info = appInfo,
                            report = report,
                            capabilities = capabilities,
                            devices = devices,
                            sessionEffects = SessionEffectsUi(
                                enabled = sessionsEnabled,
                                attached = sessions.map { "${it.packageName.ifBlank { "unknown app" }} (#${it.sessionId})" },
                                plan = sessionPlan,
                                error = sessionError,
                            ),
                            autoSwitchProfiles = autoSwitch,
                            activeProfile = activeProfile,
                        ),
                        onSessionEffectsChange = vm::setSessionEffects,
                        onAutoSwitchChange = vm::setAutoSwitch,
                        onResetAll = vm::resetAll,
                    )
                }
            }
            message?.let { m ->
                // Confirmations fade out on their own; errors stay until dismissed.
                if (!m.isError) LaunchedEffect(m) { delay(3_500); vm.dismissMessage() }
                MessageBanner(m, onDismiss = vm::dismissMessage)
            }
        }
    }
}

@Composable
private fun MessageBanner(message: UiMessage, onDismiss: () -> Unit) {
    val color = if (message.isError) SenzaColors.Danger else SenzaColors.Success
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.16f))
            .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message.text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("OK", color = color) }
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
