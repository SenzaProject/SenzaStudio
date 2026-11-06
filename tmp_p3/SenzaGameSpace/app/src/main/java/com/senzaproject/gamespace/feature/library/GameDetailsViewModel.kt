package com.senzaproject.gamespace.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.senzaproject.gamespace.R
import com.senzaproject.gamespace.core.database.GameEntity
import com.senzaproject.gamespace.core.database.GameProfileEntity
import com.senzaproject.gamespace.core.gaming.AppDetails
import com.senzaproject.gamespace.core.gaming.GameIconLoader
import com.senzaproject.gamespace.core.gaming.GameRepository
import com.senzaproject.gamespace.core.gaming.LaunchResult
import com.senzaproject.gamespace.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface DetailsUiState {
    data object Loading : DetailsUiState
    data object NotFound : DetailsUiState
    data class Loaded(val game: GameEntity, val profile: GameProfileEntity, val details: AppDetails?) : DetailsUiState
}

@HiltViewModel
class GameDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: GameRepository,
    val iconLoader: GameIconLoader,
) : ViewModel() {

    private val packageName: String = checkNotNull(savedStateHandle.get<String>(Routes.ArgPackage))

    val state: StateFlow<DetailsUiState> = combine(
        repository.observeGame(packageName),
        repository.observeProfile(packageName),
        flow { emit(repository.appDetails(packageName)) },
    ) { game, profile, details ->
        if (game == null) DetailsUiState.NotFound else DetailsUiState.Loaded(game, profile, details)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailsUiState.Loading)

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

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

    fun removeFromLibrary(onDone: () -> Unit) {
        viewModelScope.launch {
            repository.hide(packageName)
            onDone()
        }
    }
}
