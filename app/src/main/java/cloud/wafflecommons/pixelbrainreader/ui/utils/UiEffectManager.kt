package cloud.wafflecommons.pixelbrainreader.ui.utils

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class ConfettiType {
    LEVEL_UP,
    GOAL_REACHED,
    ALL_TASKS_DONE
}

sealed class GlobalEffect {
    data class Confetti(val type: ConfettiType) : GlobalEffect()
}

@Singleton
class UiEffectManager @Inject constructor() {
    private val _effects = MutableSharedFlow<GlobalEffect>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val effects: SharedFlow<GlobalEffect> = _effects.asSharedFlow()

    suspend fun triggerEffect(effect: GlobalEffect) {
        _effects.emit(effect)
    }

    // Helper for non-suspending calls (e.g. from callbacks), launch via scope if needed or use tryEmit
    fun tryTriggerEffect(effect: GlobalEffect) {
        _effects.tryEmit(effect)
    }
}
