package com.senzaproject.gamespace.core.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.senzaproject.gamespace.core.gaming.GameIconLoader

/** The app's real launcher icon. Shows the first letter of [label] if the icon cannot be loaded. */
@Composable
fun GameIcon(
    packageName: String,
    label: String,
    loader: GameIconLoader,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    val icon by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = loader.load(packageName)
    }
    val shape = RoundedCornerShape(size / 4)
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier.size(size).clip(shape))
    } else {
        Box(
            modifier = modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(label.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
        }
    }
}
