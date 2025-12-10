package cloud.wafflecommons.pixelbrainreader.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

import androidx.compose.ui.zIndex
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.Icons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val density = LocalDensity.current
    
    // Trigger Haptics on Refresh Start
    androidx.compose.runtime.LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        }
    }

    // M3 1.3 requires state sharing
    val state = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()

    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        indicator = {
             // Indicator: "Pull to Sync" Message (Behind Content)
             // Visible only during drag, hidden when Sync starts (Top Bar takes over)
             androidx.compose.animation.AnimatedVisibility(
                visible = state.distanceFraction > 0f && !isRefreshing,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(-1f) // Behind content
                    .padding(top = 32.dp) // Push down a bit
             ) {
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    modifier = Modifier.padding(8.dp)
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = androidx.compose.material.icons.Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(4.dp))
                    androidx.compose.material3.Text(
                        text = if (state.distanceFraction >= 1f) "Release to Sync" else "Syncing Repository...",
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
             }
        },
        content = {
            // Content Moves Down (Displacement)
             Box(
                modifier = Modifier
                    .offset {
                        val thresholdPx = with(density) { 100.dp.toPx() }
                        val offsetPx = (state.distanceFraction * thresholdPx).coerceAtMost(thresholdPx * 2) 
                        IntOffset(0, offsetPx.roundToInt())
                    }
            ) {
                content()
            }
        }
    )
}
