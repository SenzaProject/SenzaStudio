package com.senzaproject.gamespace.feature.library

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.senzaproject.gamespace.R
import com.senzaproject.gamespace.core.database.GameEntity
import com.senzaproject.gamespace.core.gaming.GameFilter
import com.senzaproject.gamespace.core.gaming.GameIconLoader
import com.senzaproject.gamespace.core.gaming.GameQuery
import com.senzaproject.gamespace.core.gaming.GameRepository
import com.senzaproject.gamespace.core.gaming.GameSort
import com.senzaproject.gamespace.core.gaming.InstalledApp
import com.senzaproject.gamespace.core.gaming.LaunchResult
import com.senzaproject.gamespace.core.gaming.ScanOutcome
import com.senzaproject.gamespace.core.gaming.applyQuery
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One-off message for the snackbar. Resolved to text by the screen so it follows the app language. */
data class UiMessage(@StringRes val res: Int, val args: List<Any> = emptyList())

data class LibraryUiState(
    val loaded: Boolean = false,
    val libraryEmpty: Boolean = true,
    val games: List<GameEntity> = emptyList(),
    val query: GameQuery = GameQuery(),
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: GameRepository,
    val iconLoader: GameIconLoader,
) : ViewModel() {

    private val query = MutableStateFlow(GameQuery())

    val state: StateFlow<LibraryUiState> = combine(repository.visibleGames, query) { games, q ->
        LibraryUiState(loaded = true, libraryEmpty = games.isEmpty(), games = games.applyQuery(q), query = q)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    /** null = picker closed; a list (possibly empty) = picker open. */
    private val _candidates = MutableStateFlow<List<InstalledApp>?>(null)
    val candidates: StateFlow<List<InstalledApp>?> = _candidates

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        rescan(silent = true)
    }

    fun setSearch(text: String) = query.update { it.copy(search = text) }
    fun setSort(sort: GameSort) = query.update { it.copy(sort = sort) }
    fun setFilter(filter: GameFilter) = query.update { it.copy(filter = filter) }

    fun rescan() = rescan(silent = false)

    private fun rescan(silent: Boolean) {
        viewModelScope.launch {
            _scanning.value = true
            val outcome = repository.rescan()
            _scanning.value = false
            when (outcome) {
                is ScanOutcome.Success ->
                    if (!silent) _messages.send(UiMessage(R.string.msg_scan_done, listOf(outcome.added, outcome.removed)))
                is ScanOutcome.Failed ->
                    _messages.send(UiMessage(R.string.msg_scan_failed, listOf(outcome.message ?: "?")))
            }
        }
    }

    fun openAddDialog() {
        viewModelScope.launch {
            try {
                _candidates.value = repository.candidatesToAdd()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(UiMessage(R.string.msg_scan_failed, listOf(e.message ?: "?")))
            }
        }
    }

    fun closeAddDialog() {
        _candidates.value = null
    }

    fun addGame(app: InstalledApp) {
        viewModelScope.launch {
            repository.addManually(app)
            _candidates.value = null
            _messages.send(UiMessage(R.string.msg_game_added, listOf(app.label)))
        }
    }

    fun launch(game: GameEntity) {
        viewModelScope.launch {
            when (val result = repository.launch(game.packageName)) {
                LaunchResult.Success -> Unit
                LaunchResult.NotLaunchable -> _messages.send(UiMessage(R.string.msg_not_launchable, listOf(game.label)))
                is LaunchResult.Failed ->
                    _messages.send(UiMessage(R.string.msg_launch_failed, listOf(game.label, result.message ?: "?")))
            }
        }
    }
}
