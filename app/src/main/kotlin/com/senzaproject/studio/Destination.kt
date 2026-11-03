package com.senzaproject.studio

/** Top-level destinations of the main navigation, in display order. */
enum class Destination(val route: String, val label: String) {
    STUDIO("studio", "Studio"),
    EQUALIZER("equalizer", "Equalizer"),
    SPATIAL("spatial", "Spatial"),
    PRESETS("presets", "Presets"),
    SETTINGS("settings", "Settings");

    companion object {
        val start: Destination = STUDIO
        fun fromRoute(route: String?): Destination? = entries.firstOrNull { it.route == route }
    }
}
