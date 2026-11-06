package com.senzaproject.gamespace.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.senzaproject.gamespace.R
import com.senzaproject.gamespace.core.designsystem.GmCard
import com.senzaproject.gamespace.core.designsystem.GmScreen
import com.senzaproject.gamespace.core.settings.SettingsViewModel

@Composable
fun SettingsScreen(
    onOpenPermissions: () -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()

    GmScreen(title = stringResource(R.string.screen_settings)) {
        GmCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.set_keep_screen_on), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.set_keep_screen_on_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = keepScreenOn, onCheckedChange = viewModel::setKeepScreenOn)
            }
        }
        GmCard(onClick = onOpenPermissions) {
            Text(stringResource(R.string.set_permissions), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.set_permissions_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        GmCard {
            Text(stringResource(R.string.set_language_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.set_language_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        GmCard(onClick = onOpenAbout) {
            Text(stringResource(R.string.screen_about), style = MaterialTheme.typography.titleMedium)
        }
    }
}
