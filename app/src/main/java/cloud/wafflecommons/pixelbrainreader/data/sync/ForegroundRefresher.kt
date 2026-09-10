package cloud.wafflecommons.pixelbrainreader.data.sync

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import cloud.wafflecommons.pixelbrainreader.data.usecase.SyncHealthDataUseCase
import cloud.wafflecommons.pixelbrainreader.di.ApplicationScope
import cloud.wafflecommons.pixelbrainreader.domain.gamification.AutomateHabitsUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps today's health numbers and the habits derived from them fresh while the app is in use —
 * without any network sync (sync stays manual).
 *
 * Whenever the app comes to the foreground, and when the day changes while it is open, it pulls
 * today's Health Connect metrics into the vault and re-derives the health-driven habits, at most
 * every [MIN_INTERVAL_MS] for the same day. Screens observe the results (Room,
 * `HealthMetricsRepository.changes`) and update on their own. Previously this only happened when a
 * screen's ViewModel was created or on a manual pull-to-refresh, so steps, sleep and automatic
 * habits stayed stale after returning to the app.
 *
 * Health Connect only serves reads to a foreground app, so nothing runs from a widget- or
 * worker-started process. The vault writes are serialized with git operations through
 * [GitSyncCoordinator], so a refresh can never dirty the working tree in the middle of a rebase.
 */
@Singleton
class ForegroundRefresher @Inject constructor(
    private val appClock: AppClock,
    private val syncHealthDataUseCase: SyncHealthDataUseCase,
    private val automateHabitsUseCase: AutomateHabitsUseCase,
    private val gitSyncCoordinator: GitSyncCoordinator,
    @param:ApplicationScope private val appScope: CoroutineScope
) {
    private val started = AtomicBoolean(false)

    // Only touched by the single collector coroutine below.
    private var lastRefreshDay: LocalDate? = null
    private var lastRefreshAt = 0L

    /** Idempotent. Refreshes right away if the app is already in the foreground, then on every return. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        appScope.launch {
            combine(appClock.isInForeground, appClock.today) { inForeground, today ->
                today.takeIf { inForeground }
            }
                .filterNotNull()
                .collect { today -> refreshIfStale(today) }
        }
    }

    private suspend fun refreshIfStale(today: LocalDate) {
        val now = System.currentTimeMillis()
        if (today == lastRefreshDay && now - lastRefreshAt < MIN_INTERVAL_MS) return
        lastRefreshDay = today
        lastRefreshAt = now
        try {
            gitSyncCoordinator.mutex.withLock {
                syncHealthDataUseCase(today).onFailure { Log.d(TAG, "Health refresh skipped: ${it.message}") }
                // Derive the health-driven habits even when Health Connect is unavailable on this
                // device: the day's metrics may have arrived from another device through git.
                automateHabitsUseCase(today)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Foreground refresh failed", e)
        }
    }

    private companion object {
        const val TAG = "ForegroundRefresher"
        const val MIN_INTERVAL_MS = 5 * 60_000L
    }
}
