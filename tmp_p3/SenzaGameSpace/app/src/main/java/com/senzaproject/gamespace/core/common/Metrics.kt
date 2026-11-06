package com.senzaproject.gamespace.core.common

/** Integer percentage of [part] in [total], clamped to 0..100. Returns 0 when [total] is not positive. */
fun percentOf(part: Long, total: Long): Int =
    if (total <= 0L) 0 else ((part * 100L) / total).toInt().coerceIn(0, 100)

/** Battery percentage from the values Android reports, or null when the values are invalid. */
fun batteryPercent(level: Int, scale: Int): Int? =
    if (level < 0 || scale <= 0) null else ((level * 100) / scale).coerceIn(0, 100)
