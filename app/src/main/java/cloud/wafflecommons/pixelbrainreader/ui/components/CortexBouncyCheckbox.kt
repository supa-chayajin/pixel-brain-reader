package cloud.wafflecommons.pixelbrainreader.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

@Composable
fun CortexBouncyCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    
    // Scale animation: 1.0 -> 1.2 -> 1.0 when checked changes
    // We can simulate a "bounce" by checking if state changed? 
    // Actually simpler: just animate based on checked state? No, that's toggle.
    // A click interaction usually scales DOWN then UP. 
    // For now, let's just make it scale a bit when checked.
    
    val scale by animateFloatAsState(
        targetValue = if (checked) 1.1f else 1.0f,
        animationSpec = tween(durationMillis = 200),
        label = "Checkbox Scale"
    )

    Box(modifier = modifier.scale(scale)) {
        Checkbox(
            checked = checked,
            onCheckedChange = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCheckedChange(it)
            },
            colors = CheckboxDefaults.colors(
                checkedColor = MaterialTheme.colorScheme.primary,
                checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                uncheckedColor = MaterialTheme.colorScheme.outline
            )
        )
    }
}
