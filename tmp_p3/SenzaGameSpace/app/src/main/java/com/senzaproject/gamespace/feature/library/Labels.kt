package com.senzaproject.gamespace.feature.library

import android.text.format.DateUtils
import androidx.annotation.StringRes
import com.senzaproject.gamespace.R
import com.senzaproject.gamespace.core.gaming.GameFilter
import com.senzaproject.gamespace.core.gaming.GameMode
import com.senzaproject.gamespace.core.gaming.GameSort
import com.senzaproject.gamespace.core.gaming.GameSource
import com.senzaproject.gamespace.core.gaming.MonitorLayout
import java.text.DateFormat
import java.util.Date

@StringRes fun GameFilter.labelRes(): Int = when (this) {
    GameFilter.ALL -> R.string.filter_all
    GameFilter.AUTO_DETECTED -> R.string.filter_auto
    GameFilter.MANUAL -> R.string.filter_manual
    GameFilter.PLAYED -> R.string.filter_played
}

@StringRes fun GameSort.labelRes(): Int = when (this) {
    GameSort.NAME -> R.string.sort_name
    GameSort.RECENTLY_PLAYED -> R.string.sort_recent
    GameSort.MOST_LAUNCHED -> R.string.sort_most_launched
    GameSort.RECENTLY_ADDED -> R.string.sort_added
}

@StringRes fun GameMode.labelRes(): Int = when (this) {
    GameMode.BALANCED -> R.string.mode_balanced
    GameMode.PERFORMANCE -> R.string.mode_performance
    GameMode.BATTERY_SAVER -> R.string.mode_battery_saver
    GameMode.CUSTOM -> R.string.mode_custom
}

@StringRes fun MonitorLayout.labelRes(): Int = when (this) {
    MonitorLayout.MINIMAL -> R.string.layout_minimal
    MonitorLayout.COMPACT -> R.string.layout_compact
    MonitorLayout.DETAILED -> R.string.layout_detailed
}

@StringRes fun GameSource.labelRes(): Int = when (this) {
    GameSource.AUTO -> R.string.source_auto
    GameSource.MANUAL -> R.string.source_manual
}

fun relativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

fun formatDate(millis: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
