package cloud.wafflecommons.pixelbrainreader.ui.utils

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role

object HapticHelper {
    fun View.performHapticClick() {
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    fun View.performHapticSuccess() {
        performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
    }

    fun View.performHapticTick() {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    fun View.performHapticError() {
        performHapticFeedback(HapticFeedbackConstants.REJECT)
    }
}

fun Modifier.hapticClickable(
    interactionSource: MutableInteractionSource? = null,
    indication: androidx.compose.foundation.Indication? = null,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit
): Modifier = composed {
    val haptic = LocalHapticFeedback.current
    val actualInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val actualIndication = indication ?: androidx.compose.foundation.LocalIndication.current

    this.clickable(
        interactionSource = actualInteractionSource,
        indication = actualIndication,
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
    )
}
