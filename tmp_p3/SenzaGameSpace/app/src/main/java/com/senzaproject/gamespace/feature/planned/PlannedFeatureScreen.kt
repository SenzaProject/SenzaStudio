package com.senzaproject.gamespace.feature.planned

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.senzaproject.gamespace.R
import com.senzaproject.gamespace.core.designsystem.GmCard
import com.senzaproject.gamespace.core.designsystem.GmScreen
import com.senzaproject.gamespace.core.designsystem.StatusChip
import com.senzaproject.gamespace.navigation.FeatureEntry

/** Honest placeholder: states plainly that the feature is not built and what it will contain. */
@Composable
fun PlannedFeatureScreen(entry: FeatureEntry, onBack: (() -> Unit)?) {
    GmScreen(title = stringResource(entry.titleRes), onBack = onBack) {
        StatusChip(entry.status, entry.phase)
        GmCard {
            Text(
                text = stringResource(R.string.planned_status_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.planned_status_body, entry.phase),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        entry.descRes?.let { desc ->
            GmCard {
                Text(
                    text = stringResource(R.string.planned_scope_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
