package cloud.wafflecommons.pixelbrainreader

import android.app.Application
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import androidx.work.WorkManager
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncOrchestrator
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import androidx.hilt.work.HiltWorkerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class PixelBrainApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncOrchestrator: SyncOrchestrator
    @Inject lateinit var vaultDiscoveryRepository: cloud.wafflecommons.pixelbrainreader.data.repository.VaultDiscoveryRepository
    @Inject lateinit var moodRepository: cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
    @Inject lateinit var habitRepository: cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository
    @Inject lateinit var choreRepository: cloud.wafflecommons.pixelbrainreader.data.repository.ChoreRepository

    /** Application-scoped coroutine scope — survives Activity recreation. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler(
            cloud.wafflecommons.pixelbrainreader.ui.crash.GlobalExceptionHandler(
                this,
                Thread.getDefaultUncaughtExceptionHandler()
            )
        )
        scheduleDailyBurnWork()
        cancelLegacyGoogleOutboxWorkers()
        runStartupReconcile()
        registerForegroundSyncObserver()
    }

    /**
     * Local-only reconcile that runs every cold start. Distinct from RAG
     * indexing (which is now manual) — this only rebuilds derived Room state
     * from on-disk vault files:
     *  - FileEntity table (so the file browser has something to show).
     *  - Mood / Habit / Chore tables (read from their JSON vault files).
     * Cheap: no embeddings, no network, no Health Connect, no Google.
     * Necessary after a destructive Room migration so the UI doesn't sit on
     * an empty database waiting for the foreground SyncOrchestrator to pull.
     */
    private fun runStartupReconcile() {
        appScope.launch {
            try {
                vaultDiscoveryRepository.reindexAll(0L)
            } catch (e: Exception) {
                Log.w("PixelBrainApp", "Startup file reindex failed", e)
            }
            // Mood/Habit/Chore JSON → Room. Each is independent; failures are logged
            // and don't block the others.
            try { moodRepository.syncWithFileSystem() } catch (e: Exception) {
                Log.w("PixelBrainApp", "Startup mood reconcile failed", e)
            }
            try { habitRepository.syncWithFileSystem() } catch (e: Exception) {
                Log.w("PixelBrainApp", "Startup habit reconcile failed", e)
            }
            try { choreRepository.syncWithFileSystem() } catch (e: Exception) {
                Log.w("PixelBrainApp", "Startup chore reconcile failed", e)
            }
        }
    }

    /**
     * Registers a ProcessLifecycleOwner observer that triggers a full
     * Git→Health→Google→Git sync cycle every time the app comes to the foreground.
     * Cooldown/debounce is handled inside SyncOrchestrator (60s minimum interval).
     */
    private fun registerForegroundSyncObserver() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    Log.d("PixelBrainApp", "ON_RESUME detected — launching sync cycle")
                    appScope.launch {
                        syncOrchestrator.executeFullSyncCycle()
                    }
                }
            }
        )
    }

    private fun scheduleDailyBurnWork() {
        val currentTime = java.time.LocalDateTime.now()
        // Target 23:00 (11:00 PM) today
        var targetTime = java.time.LocalDateTime.of(currentTime.toLocalDate(), java.time.LocalTime.of(23, 0))

        // If it's already past 23:00 today, schedule for tomorrow
        if (currentTime.isAfter(targetTime)) {
            targetTime = targetTime.plusDays(1)
        }

        val initialDelay = java.time.Duration.between(currentTime, targetTime).toMillis()

        val exportWorkRequest = androidx.work.PeriodicWorkRequestBuilder<cloud.wafflecommons.pixelbrainreader.data.workers.DailyExportWorker>(
            24, java.util.concurrent.TimeUnit.HOURS
        )
            .setInitialDelay(initialDelay, java.util.concurrent.TimeUnit.MILLISECONDS)
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "DailyExportWorker",
            androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
            exportWorkRequest
        )
    }

    /**
     * Google sync is now import-only and runs inside SyncOrchestrator's
     * foreground cycle. The previous outbox-drain workers ("TaskSyncWorker",
     * "CalendarSyncWorker") have been removed, but devices upgrading from an
     * older build may still have them enqueued in WorkManager's database.
     * Cancel by name so they don't keep firing against a class that no longer exists.
     */
    private fun cancelLegacyGoogleOutboxWorkers() {
        val wm = WorkManager.getInstance(this)
        wm.cancelUniqueWork("TaskSyncWorker")
        wm.cancelUniqueWork("CalendarSyncWorker")
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
