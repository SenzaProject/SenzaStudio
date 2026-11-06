package com.senzaproject.gamespace.core.monitoring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class DeviceMonitorViewModel @Inject constructor(
    repository: DeviceInfoRepository,
) : ViewModel() {
    /** Sampling stops 5 seconds after the screen leaves the foreground. */
    val snapshot: StateFlow<DeviceSnapshot?> = repository.snapshots()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
