package com.senzaproject.gamespace.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.senzaproject.gamespace.R
import com.senzaproject.gamespace.core.database.GameEntity
import com.senzaproject.gamespace.core.designsystem.GameIcon
import com.senzaproject.gamespace.core.designsystem.GmCard
import com.senzaproject.gamespace.core.designsystem.GmScreen
import com.senzaproject.gamespace.core.gaming.GameFilter
import com.senzaproject.gamespace.core.gaming.GameIconLoader
import com.senzaproject.gamespace.core.gaming.GameSort
import com.senzaproject.gamespace.core.gaming.InstalledApp
import kotlinx.coroutines.flow.collectLatest

@Composable
fun LibraryScreen(
    onOpenGame: (packageName: String) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var searchText by rememberSaveable { mutableStateOf(state.query.search) }

    LaunchedEffect(Unit) {
        viewModel.messages.collectLatest { message ->
            snackbar.showSnackbar(context.getString(message.res, *message.args.toTypedArray()))
        }
    }

    GmScreen(title = stringResource(R.string.screen_library), snackbarHostState = snackbar, scrollable = false) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = searchText,
                onValueChange = {
                    searchText = it
                    viewModel.setSearch(it)
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.library_search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            )
            IconButton(onClick = viewModel::rescan, enabled = !scanning) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.library_rescan))
            }
            IconButton(onClick = viewModel::openAddDialog) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.library_add))
            }
        }
        if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(GameFilter.entries.toList()) { filter ->
                FilterChip(
                    selected = state.query.filter == filter,
                    onClick = { viewModel.setFilter(filter) },
                    label = { Text(stringResource(filter.labelRes())) },
                )
            }
        }
        SortMenu(current = state.query.sort, onSelect = viewModel::setSort)

        when {
            !state.loaded || (state.libraryEmpty && scanning) -> Unit
            state.libraryEmpty -> InfoText(R.string.library_empty)
            state.games.isEmpty() -> InfoText(R.string.library_no_results)
            else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.games, key = { it.packageName }) { game ->
                    GameCard(
                        game = game,
                        loader = viewModel.iconLoader,
                        onOpen = { onOpenGame(game.packageName) },
                        onLaunch = { viewModel.launch(game) },
                    )
                }
            }
        }
    }

    candidates?.let { apps ->
        AddGameDialog(
            candidates = apps,
            loader = viewModel.iconLoader,
            onPick = viewModel::addGame,
            onDismiss = viewModel::closeAddDialog,
        )
    }
}

@Composable
private fun InfoText(res: Int) {
    Text(
        text = stringResource(res),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun GameCard(game: GameEntity, loader: GameIconLoader, onOpen: () -> Unit, onLaunch: () -> Unit) {
    GmCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GameIcon(game.packageName, game.label, loader)
            Column(Modifier.weight(1f)) {
                Text(game.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val last = game.lastLaunchedAt
                Text(
                    text = if (last != null) {
                        stringResource(R.string.library_last_launched, relativeTime(last))
                    } else {
                        stringResource(R.string.library_never_launched)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onLaunch) {
                Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.action_launch))
            }
        }
    }
}

@Composable
private fun SortMenu(current: GameSort, onSelect: (GameSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(stringResource(R.string.library_sort_by, stringResource(current.labelRes())))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            GameSort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(stringResource(sort.labelRes())) },
                    onClick = {
                        onSelect(sort)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun AddGameDialog(
    candidates: List<InstalledApp>,
    loader: GameIconLoader,
    onPick: (InstalledApp) -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    val shown = remember(candidates, filter) {
        val needle = filter.trim()
        candidates.filter {
            needle.isEmpty() || it.label.contains(needle, ignoreCase = true) || it.packageName.contains(needle, ignoreCase = true)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.library_search_hint)) },
                )
                if (shown.isEmpty()) {
                    InfoText(R.string.add_dialog_empty)
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(shown, key = { it.packageName }) { app ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { onPick(app) }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                GameIcon(app.packageName, app.label, loader, size = 40.dp)
                                Column {
                                    Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
