package com.senzaproject.gamespace.feature.devicemonitor

import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.senzaproject.gamespace.R
import com.senzaproject.gamespace.core.common.percentOf
import com.senzaproject.gamespace.core.designsystem.GmMetricRow
import com.senzaproject.gamespace.core.monitoring.DeviceSnapshot

/**
 * Renders real readings from [DeviceSnapshot]. A null reading is shown as "not available",
 * never as a made-up value. [detailed] adds the extra rows used by the Device monitor screen.
 */
@Composable
fun DeviceMetricRows(snapshot: DeviceSnapshot, detailed: Boolean) {
    val context = LocalContext.current
    val unavailable = stringResource(R.string.value_unavailable)
    fun bytes(value: Long): String = Formatter.formatFileSize(context, value)

    if (detailed) {
        GmMetricRow(stringResource(R.string.metric_model), snapshot.model)
        GmMetricRow(stringResource(R.string.metric_abi), snapshot.supportedAbis.firstOrNull() ?: unavailable)
    }
    GmMetricRow(stringResource(R.string.metric_cpu_cores), snapshot.cpuCores.toString())
    if (detailed) {
        GmMetricRow(
            stringResource(R.string.metric_refresh),
            snapshot.refreshRateHz?.let { stringResource(R.string.value_hertz, it) } ?: unavailable,
        )
    }

    val battery = snapshot.battery
    GmMetricRow(
        stringResource(R.string.metric_battery),
        battery?.percent?.let { stringResource(R.string.value_percent, it) } ?: unavailable,
    )
    if (detailed) {
        GmMetricRow(
            stringResource(R.string.metric_charging),
            if (battery == null) unavailable
            else stringResource(if (battery.charging) R.string.value_yes else R.string.value_no),
        )
        GmMetricRow(
            stringResource(R.string.metric_battery_temp),
            battery?.temperatureCelsius?.let { stringResource(R.string.value_celsius, it) } ?: unavailable,
        )
        GmMetricRow(
            stringResource(R.string.metric_voltage),
            battery?.voltageMillivolts?.let { stringResource(R.string.value_millivolt, it) } ?: unavailable,
        )
    }

    val memory = snapshot.memory
    if (memory == null) {
        GmMetricRow(stringResource(R.string.metric_ram_used), unavailable)
    } else {
        val used = memory.totalBytes - memory.availableBytes
        GmMetricRow(
            stringResource(R.string.metric_ram_used),
            "${bytes(used)} (${stringResource(R.string.value_percent, percentOf(used, memory.totalBytes))})",
        )
        if (detailed) {
            GmMetricRow(stringResource(R.string.metric_ram_available), bytes(memory.availableBytes))
            GmMetricRow(stringResource(R.string.metric_ram_total), bytes(memory.totalBytes))
        }
    }

    val storage = snapshot.storage
    GmMetricRow(
        stringResource(R.string.metric_storage_available),
        storage?.let { bytes(it.availableBytes) } ?: unavailable,
    )
    if (detailed) {
        GmMetricRow(
            stringResource(R.string.metric_storage_total),
            storage?.let { bytes(it.totalBytes) } ?: unavailable,
        )
    }
}
