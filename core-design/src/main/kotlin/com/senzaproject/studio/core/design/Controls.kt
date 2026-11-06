package com.senzaproject.studio.core.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.senzaproject.studio.audio.engine.Availability
import com.senzaproject.studio.audio.engine.ModuleCapability

/** Label on the left, switch on the right. */
@Composable
fun SenzaSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) SenzaColors.TextPrimary else SenzaColors.TextMuted,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Title with its availability chip and an on/off switch. Controls of an unavailable module are disabled. */
@Composable
fun ModuleToggle(
    title: String,
    capability: ModuleCapability,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val (label, tone) = when (capability.availability) {
        is Availability.Available -> "Available" to StatusTone.Positive
        is Availability.Experimental -> "Experimental" to StatusTone.Caution
        is Availability.Unavailable -> "Not available" to StatusTone.Muted
    }
    val usable = capability.availability !is Availability.Unavailable
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = SenzaColors.TextPrimary)
            StatusChip(label, tone)
        }
        Switch(checked = checked && usable, onCheckedChange = onChange, enabled = usable)
    }
    val a = capability.availability
    if (a is Availability.Unavailable) {
        Text(a.reason, style = MaterialTheme.typography.bodyMedium, color = SenzaColors.TextMuted)
    }
}

/** Label and current value on one line, slider below. */
@Composable
fun ParamSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit,
    enabled: Boolean = true,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = if (enabled) SenzaColors.TextPrimary else SenzaColors.TextMuted)
            Text(valueText, style = MaterialTheme.typography.bodyMedium, color = SenzaColors.Accent)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            enabled = enabled,
        )
    }
}

/** Single-choice chips that wrap onto several lines. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option), maxLines = 1) },
                enabled = enabled,
            )
        }
    }
}

private fun levelColor(db: Double): Color = when {
    db > -1.0 -> SenzaColors.Danger
    db > -9.0 -> SenzaColors.Warning
    else -> SenzaColors.Accent
}

/** Horizontal level bar from -60 dBFS to 0 dBFS with its channel label and reading. */
@Composable
fun LevelBar(label: String, peakDb: Double, rmsDb: Double) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = SenzaColors.TextMuted, modifier = Modifier.padding(end = 8.dp))
        Canvas(modifier = Modifier.weight(1f).height(14.dp)) {
            fun x(db: Double) = (((db + 60.0) / 60.0).coerceIn(0.0, 1.0) * size.width).toFloat()
            drawRoundRect(SenzaColors.SurfaceRaised, size = size, cornerRadius = CornerRadius(6f, 6f))
            drawRoundRect(
                levelColor(peakDb).copy(alpha = 0.35f),
                size = Size(x(peakDb), size.height),
                cornerRadius = CornerRadius(6f, 6f),
            )
            drawRoundRect(
                levelColor(rmsDb),
                size = Size(x(rmsDb), size.height),
                cornerRadius = CornerRadius(6f, 6f),
            )
        }
        Text(
            if (peakDb <= -119.0) "-inf" else String.format(java.util.Locale.US, "%.1f", peakDb),
            style = MaterialTheme.typography.labelMedium,
            color = SenzaColors.TextPrimary,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** Bar spectrum: one bar per band, -90 dBFS at the bottom, 0 dBFS at the top. */
@Composable
fun SpectrumView(levelsDb: FloatArray, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxWidth().height(110.dp)) {
        val n = levelsDb.size
        if (n == 0) return@Canvas
        val gap = 3f
        val barWidth = (size.width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val fraction = ((levelsDb[i] + 90f) / 90f).coerceIn(0f, 1f)
            val h = fraction * size.height
            drawRoundRect(
                color = if (levelsDb[i] > -3f) SenzaColors.Warning else SenzaColors.AccentCyan,
                topLeft = Offset(i * (barWidth + gap), size.height - h),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(3f, 3f),
            )
        }
    }
}

/**
 * EQ response graph: log frequency 20 Hz..20 kHz, +-[rangeDb] dB. [curveDb] is the combined response;
 * [bandMarks] are (frequency, gain) pairs drawn as dots.
 */
@Composable
fun EqGraph(curveDb: FloatArray, bandMarks: List<Pair<Float, Float>>, rangeDb: Float = 15f, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxWidth().height(160.dp)) {
        fun y(db: Float) = ((rangeDb - db) / (2 * rangeDb)).coerceIn(0f, 1f) * size.height
        fun x(hz: Float) = (Math.log10((hz / 20f).toDouble()) / 3.0).toFloat().coerceIn(0f, 1f) * size.width
        for (db in listOf(-10f, -5f, 0f, 5f, 10f)) {
            drawLine(
                if (db == 0f) SenzaColors.TextMuted else SenzaColors.Border,
                Offset(0f, y(db)), Offset(size.width, y(db)), strokeWidth = if (db == 0f) 2f else 1f,
            )
        }
        for (hz in listOf(100f, 1_000f, 10_000f)) {
            drawLine(SenzaColors.Border, Offset(x(hz), 0f), Offset(x(hz), size.height), strokeWidth = 1f)
        }
        if (curveDb.size >= 2) {
            val path = Path()
            for (i in curveDb.indices) {
                val px = i.toFloat() / (curveDb.size - 1) * size.width
                if (i == 0) path.moveTo(px, y(curveDb[i])) else path.lineTo(px, y(curveDb[i]))
            }
            drawPath(path, SenzaColors.Accent, style = Stroke(width = 4f))
        }
        for ((hz, gain) in bandMarks) {
            drawCircle(SenzaColors.AccentCyan, radius = 7f, center = Offset(x(hz), y(gain)))
        }
    }
}
