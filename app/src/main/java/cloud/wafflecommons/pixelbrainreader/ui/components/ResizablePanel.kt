package cloud.wafflecommons.pixelbrainreader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun SplitPaneHandle(
    modifier: Modifier = Modifier,
    onDrag: (Float) -> Unit
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(24.dp) // Hit target
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    onDrag(delta)
                    // Haptic feedback is too intense if on every pixel.
                    // Ideally we'd do it on "snap" or limit.
                    // Leaving it out of drag loop for now to avoid battery drain/annoyance.
                },
                onDragStarted = { 
                     haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        // Visual handle
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight(0.2f)
                .background(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(2.dp)
                )
        )
    }
}
