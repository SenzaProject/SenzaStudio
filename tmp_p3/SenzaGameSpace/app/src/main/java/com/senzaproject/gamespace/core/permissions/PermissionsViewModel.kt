package com.senzaproject.gamespace.core.permissions

import android.content.Intent
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class PermissionsViewModel @Inject constructor(
    private val repository: PermissionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(readAll())
    val state: StateFlow<Map<AppPermission, Boolean>> = _state.asStateFlow()

    /** Call when the screen resumes, since permissions change in system settings. */
    fun refresh() {
        _state.value = readAll()
    }

    fun settingsIntent(permission: AppPermission): Intent = repository.settingsIntent(permission)

    private fun readAll(): Map<AppPermission, Boolean> =
        AppPermission.entries.associateWith { repository.isGranted(it) }
}
