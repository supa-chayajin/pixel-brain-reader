package cloud.wafflecommons.pixelbrainreader.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
    val threshold = 80.dp
    val thresholdPx = with(density) { threshold.toPx() }
    
    // Internal Animatable for smooth content displacement
    val verticalOffset = remember { Animatable(0f) }

    // M3 PullToRefreshState
    val state = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()

    // Sync Animatable with Pull State while dragging
    LaunchedEffect(state.distanceFraction) {
        if (!isRefreshing) {
            verticalOffset.snapTo(state.distanceFraction * thresholdPx)
        }
    }

    // Handle Refresh Lifecycle
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            // Trigger Haptics
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            // Maintain offset during refresh
            verticalOffset.animateTo(thresholdPx)
        } else {
            // Animate back to zero when refreshing finishes
            verticalOffset.animateTo(0f)
        }
    }

    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        indicator = {
             // Indicator: "Pull to Sync" Message (Behind Content)
             androidx.compose.animation.AnimatedVisibility(
                visible = verticalOffset.value > 10f,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(-1f)
                    .padding(top = 24.dp)
             ) {
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    modifier = Modifier.padding(8.dp)
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(4.dp))
                    androidx.compose.material3.Text(
                        text = when {
                            isRefreshing -> "Syncing Repository..."
                            state.distanceFraction >= 1f -> "Release to Sync"
                            else -> "Pull to Sync"
                        },
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
             }
        },
        content = {
             Box(
                modifier = Modifier
                    .offset {
                        IntOffset(0, verticalOffset.value.roundToInt())
                    }
            ) {
                content()
            }
        }
    )
}
