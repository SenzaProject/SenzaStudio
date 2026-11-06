package com.senzaproject.gamespace.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.senzaproject.gamespace.core.database.GameEntity
import com.senzaproject.gamespace.core.database.GameProfileEntity
import com.senzaproject.gamespace.core.gaming.GameRepository
import com.senzaproject.gamespace.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PerGameUiState {
    data object Loading : PerGameUiState
    data object NotFound : PerGameUiState
    data class Loaded(val game: GameEntity, val profile: GameProfileEntity) : PerGameUiState
}

@HiltViewModel
class PerGameSettingsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: GameRepository,
) : ViewModel() {

    private val packageName: String = checkNotNull(savedStateHandle.get<String>(Routes.ArgPackage))

    val state: StateFlow<PerGameUiState> = combine(
        repository.observeGame(packageName),
        repository.observeProfile(packageName),
    ) { game, profile ->
        if (game == null) PerGameUiState.NotFound else PerGameUiState.Loaded(game, profile)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PerGameUiState.Loading)

    /** Every change is written to the database immediately. */
    fun update(transform: (GameProfileEntity) -> GameProfileEntity) {
        val current = (state.value as? PerGameUiState.Loaded)?.profile ?: return
        viewModelScope.launch { repository.saveProfile(transform(current)) }
    }

    fun reset() {
        viewModelScope.launch { repository.resetProfile(packageName) }
    }
}
