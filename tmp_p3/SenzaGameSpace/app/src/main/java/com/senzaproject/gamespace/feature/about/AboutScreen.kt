package com.senzaproject.gamespace.feature.about

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.senzaproject.gamespace.BuildConfig
import com.senzaproject.gamespace.R
import com.senzaproject.gamespace.core.designsystem.GmCard
import com.senzaproject.gamespace.core.designsystem.GmScreen

@Composable
fun AboutScreen(onBack: () -> Unit) {
    GmScreen(title = stringResource(R.string.screen_about), onBack = onBack) {
        GmCard {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.about_developer), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.about_package, BuildConfig.APPLICATION_ID), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.about_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        GmCard {
            Text(
                stringResource(R.string.about_limits_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.about_limits_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
