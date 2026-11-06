package com.senzaproject.gamespace.core.gaming

import com.senzaproject.gamespace.core.database.GameEntity

enum class GameSort { NAME, RECENTLY_PLAYED, MOST_LAUNCHED, RECENTLY_ADDED }

enum class GameFilter { ALL, AUTO_DETECTED, MANUAL, PLAYED }

data class GameQuery(
    val search: String = "",
    val sort: GameSort = GameSort.NAME,
    val filter: GameFilter = GameFilter.ALL,
)

private val byName = compareBy<GameEntity, String>(String.CASE_INSENSITIVE_ORDER) { it.label }

/** Search, filter and sort. Pure function, unit-tested. */
fun List<GameEntity>.applyQuery(query: GameQuery): List<GameEntity> {
    val needle = query.search.trim()
    val filtered = filter { game ->
        val matchesSearch = needle.isEmpty() ||
            game.label.contains(needle, ignoreCase = true) ||
            game.packageName.contains(needle, ignoreCase = true)
        val matchesFilter = when (query.filter) {
            GameFilter.ALL -> true
            GameFilter.AUTO_DETECTED -> game.source == GameSource.AUTO
            GameFilter.MANUAL -> game.source == GameSource.MANUAL
            GameFilter.PLAYED -> game.lastLaunchedAt != null
        }
        matchesSearch && matchesFilter
    }
    val comparator: Comparator<GameEntity> = when (query.sort) {
        GameSort.NAME -> byName
        GameSort.RECENTLY_PLAYED -> compareByDescending<GameEntity> { it.lastLaunchedAt ?: Long.MIN_VALUE }.then(byName)
        GameSort.MOST_LAUNCHED -> compareByDescending<GameEntity> { it.launchCount }.then(byName)
        GameSort.RECENTLY_ADDED -> compareByDescending<GameEntity> { it.addedAt }.then(byName)
    }
    return filtered.sortedWith(comparator)
}
