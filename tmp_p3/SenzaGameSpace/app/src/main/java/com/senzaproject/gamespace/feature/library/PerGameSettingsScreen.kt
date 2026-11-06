package com.senzaproject.gamespace.feature.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.senzaproject.gamespace.R
import com.senzaproject.gamespace.core.designsystem.GmCard
import com.senzaproject.gamespace.core.designsystem.GmScreen
import com.senzaproject.gamespace.core.designsystem.GmSwitchRow
import com.senzaproject.gamespace.core.gaming.GameMode
import com.senzaproject.gamespace.core.gaming.MonitorLayout

@Composable
fun PerGameSettingsScreen(
    onBack: () -> Unit,
    viewModel: PerGameSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    GmScreen(title = stringResource(R.string.screen_per_game), onBack = onBack) {
        when (val current = state) {
            PerGameUiState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            PerGameUiState.NotFound -> GmCard {
                Text(stringResource(R.string.details_not_found), style = MaterialTheme.typography.bodyMedium)
            }
            is PerGameUiState.Loaded -> {
                val profile = current.profile
                Text(current.game.label, style = MaterialTheme.typography.titleLarge)
                GmCard {
                    Text(stringResource(R.string.pg_phase_note), style = MaterialTheme.typography.bodyMedium)
                }

                GmCard {
                    Text(stringResource(R.string.pg_mode), style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = profile.preferredMode == null,
                            onClick = { viewModel.update { it.copy(preferredMode = null) } },
                            label = { Text(stringResource(R.string.pg_mode_default)) },
                        )
                        GameMode.entries.forEach { mode ->
                            FilterChip(
                                selected = profile.preferredMode == mode,
                                onClick = { viewModel.update { it.copy(preferredMode = mode) } },
                                label = { Text(stringResource(mode.labelRes())) },
                            )
                        }
                    }
                }

                GmCard {
                    val brightness = profile.brightnessPercent
                    GmSwitchRow(
                        title = stringResource(R.string.pg_brightness),
                        description = stringResource(R.string.pg_brightness_desc),
                        checked = brightness != null,
                        onCheckedChange = { on -> viewModel.update { it.copy(brightnessPercent = if (on) 70 else null) } },
                    )
                    if (brightness != null) {
                        var slider by remember(brightness) { mutableFloatStateOf(brightness.toFloat()) }
                        Text(stringResource(R.string.value_percent, slider.toInt()), style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = slider,
                            onValueChange = { slider = it },
                            onValueChangeFinished = { viewModel.update { it.copy(brightnessPercent = slider.toInt().coerceIn(1, 100)) } },
                            valueRange = 1f..100f,
                        )
                    }
                }

                GmCard {
                    GmSwitchRow(
                        title = stringResource(R.string.pg_dnd),
                        description = stringResource(R.string.pg_dnd_desc),
                        checked = profile.dndEnabled,
                        onCheckedChange = { on -> viewModel.update { it.copy(dndEnabled = on) } },
                    )
                }
                GmCard {
                    GmSwitchRow(
                        title = stringResource(R.string.pg_overlay),
                        description = stringResource(R.string.pg_overlay_desc),
                        checked = profile.overlayEnabled,
                        onCheckedChange = { on -> viewModel.update { it.copy(overlayEnabled = on) } },
                    )
                }
                GmCard {
                    Text(stringResource(R.string.pg_layout), style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MonitorLayout.entries.forEach { layout ->
                            FilterChip(
                                selected = profile.monitorLayout == layout,
                                onClick = { viewModel.update { it.copy(monitorLayout = layout) } },
                                label = { Text(stringResource(layout.labelRes())) },
                            )
                        }
                    }
                }
                GmCard {
                    GmSwitchRow(
                        title = stringResource(R.string.pg_track),
                        description = stringResource(R.string.pg_track_desc),
                        checked = profile.trackSessions,
                        onCheckedChange = { on -> viewModel.update { it.copy(trackSessions = on) } },
                    )
                }
                OutlinedButton(onClick = viewModel::reset, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pg_reset))
                }
            }
        }
    }
}
