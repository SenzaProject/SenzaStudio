package com.senzaproject.gamespace.core.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {

    val keepScreenOn: StateFlow<Boolean> = repository.keepScreenOn
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val onboardingDone: Flow<Boolean> = repository.onboardingDone

    fun setKeepScreenOn(value: Boolean) {
        viewModelScope.launch { repository.setKeepScreenOn(value) }
    }

    /** Suspends until the value is persisted so callers can safely navigate away afterwards. */
    suspend fun completeOnboarding() {
        repository.setOnboardingDone(true)
    }
}
