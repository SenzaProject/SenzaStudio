package com.senzaproject.gamespace.core.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val onboardingKey = booleanPreferencesKey("onboarding_done")
    private val keepScreenOnKey = booleanPreferencesKey("keep_screen_on")

    val onboardingDone: Flow<Boolean> =
        context.settingsDataStore.data.map { it[onboardingKey] ?: false }

    val keepScreenOn: Flow<Boolean> =
        context.settingsDataStore.data.map { it[keepScreenOnKey] ?: false }

    suspend fun setOnboardingDone(value: Boolean) {
        context.settingsDataStore.edit { it[onboardingKey] = value }
    }

    suspend fun setKeepScreenOn(value: Boolean) {
        context.settingsDataStore.edit { it[keepScreenOnKey] = value }
    }
}
