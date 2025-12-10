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
             // Indicator at Z = -1 (Behind Content)
             Box(
                 modifier = Modifier
                     .align(Alignment.TopCenter)
                     .zIndex(-1f)
                     .padding(top = 16.dp)
             ) {
                 if (isRefreshing) {
                     androidx.compose.material3.CircularProgressIndicator(
                         modifier = Modifier.size(24.dp),
                         strokeWidth = 2.dp,
                         color = androidx.compose.material3.MaterialTheme.colorScheme.primary
                     )
                 } else {
                      androidx.compose.material3.CircularProgressIndicator(
                         progress = { state.distanceFraction.coerceIn(0f, 1f) },
                         modifier = Modifier.size(24.dp),
                         strokeWidth = 2.dp,
                         color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
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
