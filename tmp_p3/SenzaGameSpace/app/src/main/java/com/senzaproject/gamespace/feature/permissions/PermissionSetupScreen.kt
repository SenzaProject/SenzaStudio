package com.senzaproject.gamespace.feature.permissions

import android.Manifest
import android.content.ActivityNotFoundException
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.senzaproject.gamespace.R
import com.senzaproject.gamespace.core.designsystem.GmCard
import com.senzaproject.gamespace.core.designsystem.GmScreen
import com.senzaproject.gamespace.core.permissions.AppPermission
import com.senzaproject.gamespace.core.permissions.PermissionsViewModel

private data class PermissionItem(
    val permission: AppPermission,
    @StringRes val title: Int,
    @StringRes val why: Int,
)

private val items = listOf(
    PermissionItem(AppPermission.USAGE_ACCESS, R.string.perm_usage_title, R.string.perm_usage_why),
    PermissionItem(AppPermission.OVERLAY, R.string.perm_overlay_title, R.string.perm_overlay_why),
    PermissionItem(AppPermission.NOTIFICATIONS, R.string.perm_notif_title, R.string.perm_notif_why),
    PermissionItem(AppPermission.DND_ACCESS, R.string.perm_dnd_title, R.string.perm_dnd_why),
)

@Composable
fun PermissionSetupScreen(
    onContinue: () -> Unit,
    onBack: (() -> Unit)?,
    viewModel: PermissionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var askedNotificationOnce by rememberSaveable { mutableStateOf(false) }

    // Permissions change in system settings, so re-read them every time the screen resumes.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refresh() }

    fun openSettings(permission: AppPermission) {
        try {
            context.startActivity(viewModel.settingsIntent(permission))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.perm_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun grant(permission: AppPermission) {
        // The runtime dialog is shown once; after that the user is sent to the settings page.
        if (permission == AppPermission.NOTIFICATIONS &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !askedNotificationOnce
        ) {
            askedNotificationOnce = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openSettings(permission)
        }
    }

    GmScreen(title = stringResource(R.string.screen_permissions), onBack = onBack) {
        Text(
            text = stringResource(R.string.perm_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        items.forEach { item ->
            val granted = state[item.permission] == true
            GmCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = stringResource(item.title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(if (granted) R.string.perm_granted else R.string.perm_not_granted),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (granted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(item.why),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!granted) {
                    Button(onClick = { grant(item.permission) }) {
                        Text(stringResource(R.string.perm_grant))
                    }
                }
            }
        }
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (onBack == null) R.string.perm_continue else R.string.perm_done))
        }
    }
}
