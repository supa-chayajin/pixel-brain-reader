package cloud.wafflecommons.pixelbrainreader.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

enum class SaveState {
    IDLE, UNSAVED, SAVING, SAVED, ERROR
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun SaveStatusIndicator(state: SaveState, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current

    // Tactile Haptic Integration for SAVED
    // TextHandleMove is vastly superior to a heavy vibration because it feels
    // like a light, precise "tick", emulating a physical mechanical confirmation.
    // Heavy vibrations are associated with alerts or errors, disrupting flow.
    LaunchedEffect(state) {
        if (state == SaveState.SAVED) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    AnimatedContent(
        targetState = state,
        transitionSpec = {
            (fadeIn(animationSpec = tween(200)) + slideInVertically(
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                initialOffsetY = { fullHeight -> fullHeight / 2 }
            )).togetherWith(
                fadeOut(animationSpec = tween(200)) + slideOutVertically(
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    targetOffsetY = { fullHeight -> -fullHeight / 2 }
                )
            )
        },
        label = "SaveStatusIndicator"
    ) { targetState ->
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier.size(24.dp)
        ) {
            when (targetState) {
                SaveState.IDLE -> {
                    // Takes up space but is invisible
                }
                SaveState.UNSAVED -> {
                    val color = MaterialTheme.colorScheme.onSurfaceVariant
                    Canvas(modifier = Modifier.size(6.dp)) {
                        drawCircle(color = color, alpha = 0.5f)
                    }
                }
                SaveState.SAVING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                SaveState.SAVED -> {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Saved",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                SaveState.ERROR -> {
                    Icon(
                        imageVector = Icons.Rounded.Warning,
                        contentDescription = "Save Error",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
