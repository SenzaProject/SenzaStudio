package com.senzaproject.gamespace.core.monitoring

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.view.Display
import com.senzaproject.gamespace.core.common.batteryPercent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

data class BatterySnapshot(
    val percent: Int?,
    val charging: Boolean,
    val temperatureCelsius: Float?,
    val voltageMillivolts: Int?,
)

data class MemorySnapshot(val totalBytes: Long, val availableBytes: Long)

data class StorageSnapshot(val totalBytes: Long, val availableBytes: Long)

data class DeviceSnapshot(
    val model: String,
    val supportedAbis: List<String>,
    val cpuCores: Int,
    val refreshRateHz: Float?,
    val battery: BatterySnapshot?,
    val memory: MemorySnapshot?,
    val storage: StorageSnapshot?,
)

/**
 * Reads device information from documented Android APIs only.
 * Any value Android does not provide is returned as null, never substituted.
 */
@Singleton
class DeviceInfoRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun snapshots(intervalMs: Long = 2_000L): Flow<DeviceSnapshot> = flow {
        while (true) {
            emit(readSnapshot())
            delay(intervalMs)
        }
    }.flowOn(Dispatchers.Default)

    private fun readSnapshot() = DeviceSnapshot(
        model = "${Build.MANUFACTURER} ${Build.MODEL}",
        supportedAbis = Build.SUPPORTED_ABIS.toList(),
        cpuCores = Runtime.getRuntime().availableProcessors(),
        refreshRateHz = readRefreshRate(),
        battery = readBattery(),
        memory = readMemory(),
        storage = readStorage(),
    )

    private fun readBattery(): BatterySnapshot? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
        return BatterySnapshot(
            percent = batteryPercent(
                intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
            ),
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL,
            // Android reports temperature in tenths of a degree Celsius.
            temperatureCelsius = if (temperature == Int.MIN_VALUE) null else temperature / 10f,
            voltageMillivolts = if (voltage == Int.MIN_VALUE || voltage <= 0) null else voltage,
        )
    }

    private fun readMemory(): MemorySnapshot? = runCatching {
        val manager = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
        MemorySnapshot(totalBytes = info.totalMem, availableBytes = info.availMem)
    }.getOrNull()

    private fun readStorage(): StorageSnapshot? = runCatching {
        val stat = StatFs(Environment.getDataDirectory().path)
        StorageSnapshot(totalBytes = stat.totalBytes, availableBytes = stat.availableBytes)
    }.getOrNull()

    private fun readRefreshRate(): Float? = runCatching {
        val displays = context.getSystemService(DisplayManager::class.java)
        displays.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate
    }.getOrNull()
}
