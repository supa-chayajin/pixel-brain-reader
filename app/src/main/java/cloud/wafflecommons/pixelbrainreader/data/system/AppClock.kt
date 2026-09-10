package cloud.wafflecommons.pixelbrainreader.data.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide source of "what day is it?" and "is the app in use?".
 *
 * WHY: the process routinely survives for days, so anything that captured `LocalDate.now()` once
 * (a ViewModel's selected date, a remembered file name) silently went stale after midnight, and
 * nothing re-fetched time-sensitive data (weather, health) when the user returned to the app.
 *
 * [today] is re-evaluated at midnight (ACTION_DATE_CHANGED), on clock/timezone changes and every
 * time the app returns to the foreground. [isInForeground] follows the process lifecycle (true while
 * an activity is started) and [foregroundEvents] fires on every app-level ON_START, so screens and
 * background refreshers can re-fetch cheap, TTL-guarded data on resume.
 */
@Singleton
class AppClock @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _today = MutableStateFlow(LocalDate.now())
    val today: StateFlow<LocalDate> = _today.asStateFlow()

    private val _isInForeground = MutableStateFlow(false)
    val isInForeground: StateFlow<Boolean> = _isInForeground.asStateFlow()

    private val _foregroundEvents = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val foregroundEvents: SharedFlow<Unit> = _foregroundEvents.asSharedFlow()

    private val started = AtomicBoolean(false)

    /** Wires the lifecycle + date-change observers. Idempotent; call once from Application.onCreate. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                refreshToday()
                _isInForeground.value = true
                _foregroundEvents.tryEmit(Unit)
            }

            override fun onStop(owner: LifecycleOwner) {
                _isInForeground.value = false
            }
        })
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) = refreshToday()
            },
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** Re-reads the wall clock. StateFlow equality makes this a no-op unless the day changed. */
    fun refreshToday() {
        _today.value = LocalDate.now()
    }
}
