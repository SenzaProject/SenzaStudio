package com.senzaproject.studio.feature.presets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.senzaproject.studio.audio.engine.Preset
import com.senzaproject.studio.core.design.SectionLabel
import com.senzaproject.studio.core.design.SenzaCard
import com.senzaproject.studio.core.design.SenzaColors
import com.senzaproject.studio.core.design.SenzaScreen
import com.senzaproject.studio.core.design.StatusChip
import com.senzaproject.studio.core.design.StatusTone

data class PresetsUi(
    val schemaVersion: Int,
    val factory: List<Preset>,
    val user: List<Preset>,
    /** Files that could not be read; shown so nothing disappears silently. */
    val problems: List<String>,
    /** The preset whose parameters equal the current settings, if any. */
    val appliedId: String?,
)

@Composable
fun PresetsScreen(
    ui: PresetsUi,
    onApply: (Preset) -> Unit,
    onSave: (String) -> Unit,
    onDelete: (Preset) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Preset?>(null) }

    SenzaScreen(title = "PRESETS", subtitle = "Factory and custom profiles") {
        SenzaCard {
            SectionLabel("Save current settings")
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text("Preset name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onSave(name); name = "" },
                enabled = name.isNotBlank(),
            ) { Text("Save preset", maxLines = 1, softWrap = false) }
        }

        SenzaCard {
            SectionLabel("Factory presets")
            ui.factory.forEach { PresetRow(it, it.id == ui.appliedId, onApply, onDelete = null) }
        }

        SenzaCard {
            SectionLabel("My presets")
            if (ui.user.isEmpty()) {
                Text("Nothing saved yet.", style = MaterialTheme.typography.bodyMedium, color = SenzaColors.TextMuted)
            }
            ui.user.forEach { PresetRow(it, it.id == ui.appliedId, onApply, onDelete = { p -> pendingDelete = p }) }
            ui.problems.forEach {
                Text("Could not read $it", style = MaterialTheme.typography.bodyMedium, color = SenzaColors.Danger)
            }
        }

        Text(
            "Presets store every control (parameter schema v${ui.schemaVersion}). Applying one replaces the current settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = SenzaColors.TextMuted,
        )
    }

    val target = pendingDelete
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete preset?") },
            text = { Text("\"${target.name}\" will be removed from this device.") },
            confirmButton = { TextButton(onClick = { onDelete(target); pendingDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PresetRow(preset: Preset, applied: Boolean, onApply: (Preset) -> Unit, onDelete: ((Preset) -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            preset.name,
            style = MaterialTheme.typography.titleMedium,
            color = if (applied) SenzaColors.Accent else SenzaColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        if (applied) StatusChip("Active", StatusTone.Positive)
        TextButton(onClick = { onApply(preset) }) { Text("Apply", maxLines = 1, softWrap = false) }
        if (onDelete != null) TextButton(onClick = { onDelete(preset) }) { Text("Delete", maxLines = 1, softWrap = false) }
    }
}
