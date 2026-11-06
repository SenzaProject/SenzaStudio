package com.senzaproject.gamespace.feature.devicemonitor

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.senzaproject.gamespace.R
import com.senzaproject.gamespace.core.common.FeatureStatus
import com.senzaproject.gamespace.core.designsystem.GmCard
import com.senzaproject.gamespace.core.designsystem.GmScreen
import com.senzaproject.gamespace.core.designsystem.StatusChip
import com.senzaproject.gamespace.core.monitoring.DeviceMonitorViewModel

@Composable
fun DeviceMonitorScreen(
    onBack: () -> Unit,
    viewModel: DeviceMonitorViewModel = hiltViewModel(),
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    GmScreen(title = stringResource(R.string.screen_device_monitor), onBack = onBack) {
        StatusChip(FeatureStatus.PARTIAL)
        GmCard {
            val current = snapshot
            if (current == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                DeviceMetricRows(current, detailed = true)
            }
        }
        GmCard {
            Text(
                text = stringResource(R.string.dm_pending_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
