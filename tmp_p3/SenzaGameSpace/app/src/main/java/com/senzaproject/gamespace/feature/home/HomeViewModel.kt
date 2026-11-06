package com.senzaproject.gamespace.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.senzaproject.gamespace.core.database.GameEntity
import com.senzaproject.gamespace.core.gaming.GameIconLoader
import com.senzaproject.gamespace.core.gaming.GameRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    repository: GameRepository,
    val iconLoader: GameIconLoader,
) : ViewModel() {
    val gameCount: StateFlow<Int> = repository.visibleCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val recentGames: StateFlow<List<GameEntity>> = repository.recentGames(limit = 3)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Fills the library on first launch so Home is not empty before the Library tab is opened.
        viewModelScope.launch { repository.rescan() }
    }
}
