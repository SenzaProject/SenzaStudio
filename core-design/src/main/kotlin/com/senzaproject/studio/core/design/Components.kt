package com.senzaproject.studio.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.senzaproject.studio.audio.engine.Availability
import com.senzaproject.studio.audio.engine.ModuleCapability

/** Standard screen frame: brand line, title, subtitle, divider, then content. */
@Composable
fun SenzaScreen(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(SenzaSpacing.Screen),
        verticalArrangement = Arrangement.spacedBy(SenzaSpacing.Gap),
    ) {
        Column {
            Text("SENZA", style = MaterialTheme.typography.labelLarge, color = SenzaColors.Accent)
            Text(title, style = MaterialTheme.typography.headlineMedium, color = SenzaColors.TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = SenzaColors.TextMuted)
        }
        HorizontalDivider(color = SenzaColors.Border)
        content()
    }
}

@Composable
fun SenzaCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SenzaColors.Surface)
            .border(1.dp, SenzaColors.Border, shape)
            .padding(SenzaSpacing.Card),
        verticalArrangement = Arrangement.spacedBy(SenzaSpacing.Tight),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = SenzaColors.TextMuted)
}

enum class StatusTone { Positive, Caution, Muted, Negative }

@Composable
fun StatusChip(text: String, tone: StatusTone) {
    val color = when (tone) {
        StatusTone.Positive -> SenzaColors.Success
        StatusTone.Caution -> SenzaColors.Warning
        StatusTone.Muted -> SenzaColors.TextMuted
        StatusTone.Negative -> SenzaColors.Danger
    }
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** One line per engine module with its honest availability state. */
@Composable
fun CapabilityRow(capability: ModuleCapability) {
    val (label, tone, detail) = when (val a = capability.availability) {
        is Availability.Available -> Triple("Available", StatusTone.Positive, null)
        is Availability.Experimental -> Triple("Experimental", StatusTone.Caution, a.note)
        is Availability.Unavailable -> Triple("Not available", StatusTone.Muted, a.reason)
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                capability.module.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = SenzaColors.TextPrimary,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            StatusChip(label, tone)
        }
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = SenzaColors.TextMuted)
        }
    }
}
