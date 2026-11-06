package com.senzaproject.gamespace.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.senzaproject.gamespace.R
import com.senzaproject.gamespace.core.designsystem.GameIcon
import com.senzaproject.gamespace.core.designsystem.GmCard
import com.senzaproject.gamespace.core.designsystem.GmMetricRow
import com.senzaproject.gamespace.core.designsystem.GmScreen
import kotlinx.coroutines.flow.collectLatest

@Composable
fun GameDetailsScreen(
    onBack: () -> Unit,
    onOpenSettings: (packageName: String) -> Unit,
    viewModel: GameDetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var confirmRemove by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collectLatest { message ->
            snackbar.showSnackbar(context.getString(message.res, *message.args.toTypedArray()))
        }
    }

    GmScreen(title = stringResource(R.string.screen_game_details), onBack = onBack, snackbarHostState = snackbar) {
        when (val current = state) {
            DetailsUiState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            DetailsUiState.NotFound -> GmCard {
                Text(stringResource(R.string.details_not_found), style = MaterialTheme.typography.bodyMedium)
            }
            is DetailsUiState.Loaded -> {
                val game = current.game
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    GameIcon(game.packageName, game.label, viewModel.iconLoader, size = 72.dp)
                    Column {
                        Text(game.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            game.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Button(onClick = { viewModel.launch(game) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.action_launch))
                }
                GmCard {
                    Text(stringResource(R.string.details_info), style = MaterialTheme.typography.titleMedium)
                    GmMetricRow(stringResource(R.string.details_source), stringResource(game.source.labelRes()))
                    GmMetricRow(stringResource(R.string.details_added), formatDate(game.addedAt))
                    GmMetricRow(stringResource(R.string.details_launch_count), game.launchCount.toString())
                    GmMetricRow(
                        stringResource(R.string.details_last_launched),
                        game.lastLaunchedAt?.let { relativeTime(it) } ?: stringResource(R.string.library_never_launched),
                    )
                    current.details?.let { d ->
                        GmMetricRow(
                            stringResource(R.string.details_version),
                            d.versionName ?: stringResource(R.string.value_unavailable),
                        )
                        GmMetricRow(stringResource(R.string.details_installed), formatDate(d.firstInstallTime))
                        GmMetricRow(stringResource(R.string.details_updated), formatDate(d.lastUpdateTime))
                    }
                    Text(
                        stringResource(R.string.details_launch_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                GmCard {
                    val profile = current.profile
                    Text(stringResource(R.string.details_profile), style = MaterialTheme.typography.titleMedium)
                    GmMetricRow(
                        stringResource(R.string.pg_mode),
                        profile.preferredMode?.let { stringResource(it.labelRes()) } ?: stringResource(R.string.pg_mode_default),
                    )
                    GmMetricRow(
                        stringResource(R.string.pg_brightness),
                        profile.brightnessPercent?.let { stringResource(R.string.value_percent, it) }
                            ?: stringResource(R.string.pg_brightness_unchanged),
                    )
                    GmMetricRow(stringResource(R.string.pg_dnd), stringResource(if (profile.dndEnabled) R.string.value_yes else R.string.value_no))
                    GmMetricRow(stringResource(R.string.pg_overlay), stringResource(if (profile.overlayEnabled) R.string.value_yes else R.string.value_no))
                    GmMetricRow(stringResource(R.string.pg_layout), stringResource(profile.monitorLayout.labelRes()))
                    GmMetricRow(stringResource(R.string.pg_track), stringResource(if (profile.trackSessions) R.string.value_yes else R.string.value_no))
                }
                OutlinedButton(onClick = { onOpenSettings(game.packageName) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.screen_per_game))
                }
                TextButton(onClick = { confirmRemove = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.details_remove))
                }
                if (confirmRemove) {
                    AlertDialog(
                        onDismissRequest = { confirmRemove = false },
                        title = { Text(stringResource(R.string.details_remove_title, game.label)) },
                        text = { Text(stringResource(R.string.details_remove_body)) },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmRemove = false
                                viewModel.removeFromLibrary(onDone = onBack)
                            }) { Text(stringResource(R.string.details_remove_confirm)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.action_cancel)) }
                        },
                    )
                }
            }
        }
    }
}
