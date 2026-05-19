package cloud.wafflecommons.pixelbrainreader.ui.utils

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize

fun Modifier.shimmerEffect(): Modifier = composed {
    val size = remember { androidx.compose.runtime.mutableStateOf(IntSize.Zero) }
    val transition = rememberInfiniteTransition(label = "Shimmer")
    val startOffsetX by transition.animateFloat(
        initialValue = -2 * size.value.width.toFloat(),
        targetValue = 2 * size.value.width.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1000)
        ),
        label = "ShimmerOffset"
    )

    // Theme-aware base so the shimmer remains visible in both light and dark mode.
    // The previous hardcoded #B8B5B5 / #8F8B8B grays were nearly invisible on the
    // pure-black dark surface (#000).
    val shimmerBase = MaterialTheme.colorScheme.onSurfaceVariant
    background(
        brush = Brush.linearGradient(
            colors = listOf(
                shimmerBase.copy(alpha = 0.3f),
                shimmerBase.copy(alpha = 0.5f),
                shimmerBase.copy(alpha = 0.3f),
            ),
            start = Offset(startOffsetX, 0f),
            end = Offset(startOffsetX + size.value.width.toFloat(), size.value.height.toFloat())
        )
    ).onGloballyPositioned {
        size.value = it.size
    }
}
