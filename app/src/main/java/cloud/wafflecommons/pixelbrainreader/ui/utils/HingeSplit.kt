package cloud.wafflecommons.pixelbrainreader.ui.utils

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlin.math.roundToInt

/**
 * True when the phone stands half-folded like a small laptop (E6): the screen bends along a
 * horizontal hinge, so the top half faces the user and the bottom half lies flat.
 */
@Composable
fun rememberTabletop(): Boolean = currentWindowAdaptiveInfo().windowPosture.isTabletop

/**
 * [top] above the hinge, [bottom] below it, nothing on the fold itself. Without a horizontal
 * hinge (flat, or not a foldable) the split falls at the middle. The layout fills its bounds.
 */
@Composable
fun HingeSplit(top: @Composable () -> Unit, bottom: @Composable () -> Unit, modifier: Modifier = Modifier) {
    val hinge = currentWindowAdaptiveInfo().windowPosture.hingeList.firstOrNull { !it.isVertical }
    var originY by remember { mutableFloatStateOf(0f) }
    Layout(
        contents = listOf(top, bottom),
        modifier = modifier.onGloballyPositioned { originY = it.positionInWindow().y }
    ) { (topMeasurables, bottomMeasurables), constraints ->
        if (!constraints.hasBoundedHeight) {
            // Nothing to split (a scrolling parent): stack them.
            val loose = constraints.copy(minHeight = 0)
            val stacked = (topMeasurables + bottomMeasurables).map { it.measure(loose) }
            return@Layout layout(constraints.maxWidth, stacked.sumOf { it.height }) {
                var y = 0
                stacked.forEach { it.place(0, y); y += it.height }
            }
        }
        val height = constraints.maxHeight
        val hingeTop = hinge?.bounds?.top?.minus(originY)?.roundToInt()?.coerceIn(0, height) ?: (height / 2)
        val hingeBottom = hinge?.bounds?.bottom?.minus(originY)?.roundToInt()?.coerceIn(hingeTop, height) ?: hingeTop
        val topPlaceables = topMeasurables.map {
            it.measure(constraints.copy(minHeight = hingeTop, maxHeight = hingeTop))
        }
        val bottomHeight = height - hingeBottom
        val bottomPlaceables = bottomMeasurables.map {
            it.measure(constraints.copy(minHeight = bottomHeight, maxHeight = bottomHeight))
        }
        layout(constraints.maxWidth, height) {
            topPlaceables.forEach { it.place(0, 0) }
            bottomPlaceables.forEach { it.place(0, hingeBottom) }
        }
    }
}
