package com.senzaproject.gamespace.core.gaming

import android.content.Context
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Loads real app icons from PackageManager off the main thread, with a small in-memory cache. */
@Singleton
class GameIconLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cache = LruCache<String, ImageBitmap>(64)

    /** Returns null when the app has no icon or is no longer installed; callers show a letter fallback. */
    suspend fun load(packageName: String): ImageBitmap? {
        cache.get(packageName)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val bitmap = context.packageManager.getApplicationIcon(packageName)
                    .toBitmap(ICON_SIZE_PX, ICON_SIZE_PX)
                    .asImageBitmap()
                cache.put(packageName, bitmap)
                bitmap
            } catch (_: Exception) {
                null
            }
        }
    }

    private companion object {
        const val ICON_SIZE_PX = 128
    }
}
