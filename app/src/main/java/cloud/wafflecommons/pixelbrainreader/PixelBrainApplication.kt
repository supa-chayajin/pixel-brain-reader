package cloud.wafflecommons.pixelbrainreader

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkManager
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
    @Inject lateinit var vaultDiscoveryRepository: cloud.wafflecommons.pixelbrainreader.data.repository.VaultDiscoveryRepository
    @Inject lateinit var moodRepository: cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
    @Inject lateinit var habitRepository: cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository
    @Inject lateinit var choreRepository: cloud.wafflecommons.pixelbrainreader.data.repository.ChoreRepository
    @Inject lateinit var reminderScheduler: cloud.wafflecommons.pixelbrainreader.data.notifications.ReminderScheduler

    /** Application-scoped coroutine scope — survives Activity recreation. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // CrashActivity runs in a separate `:crash` process (see manifest);
        // that process boots its own Application instance. Skip all of the
        // main-process init for it: no point scheduling workers / running
        // reconciles in a process whose only job is to show the crash UI,
        // and an exception inside that init would crash-loop the dialog.
        if (!isMainProcess()) {
            return
        }

        Thread.setDefaultUncaughtExceptionHandler(
            cloud.wafflecommons.pixelbrainreader.ui.crash.GlobalExceptionHandler(
                this,
                Thread.getDefaultUncaughtExceptionHandler()
            )
        )
        scheduleDailyBurnWork()
        cancelLegacyGoogleOutboxWorkers()
        runStartupReconcile()
        scheduleVaultIndexing()
        // (Re)schedule reminder notifications from the saved preferences. Also
        // creates the notification channels. Cheap; runs off the main thread.
        appScope.launch { runCatching { reminderScheduler.reschedule() } }
    }

    /**
     * Backfill RAG embeddings in the background. IndexingWorker only embeds files
     * that changed or have no embedding row yet and returns early (no model load)
     * when there's nothing to do — so this is a near-noop once the index is warm.
     * Its job is to rebuild embeddings automatically after a destructive Room
     * migration or an EMBEDDER_SCHEMA_VERSION bump (both wipe the embeddings table),
     * instead of waiting for the user to press Settings → "Index Knowledge Vault".
     * Shares that button's unique name with KEEP so a manual/in-flight index isn't
     * disturbed.
     */
    private fun scheduleVaultIndexing() {
        val request = androidx.work.OneTimeWorkRequestBuilder<cloud.wafflecommons.pixelbrainreader.data.workers.IndexingWorker>()
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            cloud.wafflecommons.pixelbrainreader.data.workers.IndexingWorker.UNIQUE_WORK_NAME,
            androidx.work.ExistingWorkPolicy.KEEP,
            request
        )
    }

    /** True only inside the default app process (excludes `:crash`). */
    private fun isMainProcess(): Boolean {
        val current = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            val pid = android.os.Process.myPid()
            (getSystemService(ACTIVITY_SERVICE) as? android.app.ActivityManager)
                ?.runningAppProcesses
                ?.firstOrNull { it.pid == pid }
                ?.processName
        }
        return current == packageName
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
                    .setRequiresDeviceIdle(true)
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
