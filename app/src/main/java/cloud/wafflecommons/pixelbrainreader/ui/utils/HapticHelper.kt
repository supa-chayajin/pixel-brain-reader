package cloud.wafflecommons.pixelbrainreader.ui.utils

import android.view.HapticFeedbackConstants
import android.view.View

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
