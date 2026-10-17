package cloud.wafflecommons.pixelbrainreader

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import androidx.work.WorkManager
import cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationRepository
import cloud.wafflecommons.pixelbrainreader.data.notifications.ReminderScheduler
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyDashboardRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.VaultDiscoveryRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.ForegroundRefresher
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncOrchestrator
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import cloud.wafflecommons.pixelbrainreader.data.workers.DailyExportWorker
import cloud.wafflecommons.pixelbrainreader.data.workers.DailyWorkScheduler
import cloud.wafflecommons.pixelbrainreader.widget.ui.WidgetUpdateManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltAndroidApp
class PixelBrainApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var appClock: AppClock

    // dagger.Lazy on purpose: Application.onCreate runs on the main thread for EVERY process start —
    // including the ones WorkManager and widget taps trigger — so the repository graph is only built
    // inside the background coroutines that actually use it.
    @Inject lateinit var vaultDiscoveryRepository: dagger.Lazy<VaultDiscoveryRepository>
    @Inject lateinit var dailyDashboardRepository: dagger.Lazy<DailyDashboardRepository>
    @Inject lateinit var reminderScheduler: dagger.Lazy<ReminderScheduler>
    @Inject lateinit var gamificationRepository: dagger.Lazy<GamificationRepository>
    @Inject lateinit var syncOrchestrator: dagger.Lazy<SyncOrchestrator>
    @Inject lateinit var widgetUpdateManager: dagger.Lazy<WidgetUpdateManager>
    @Inject lateinit var foregroundRefresher: dagger.Lazy<ForegroundRefresher>

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
        appClock.start()
        // Today's health numbers + health-driven habits, refreshed each time the app is opened.
        appScope.launch { safely("Foreground refresher") { foregroundRefresher.get().start() } }
        scheduleDailyBurnWork()
        cancelLegacyGoogleOutboxWorkers()
        // Hydrate the gamification profile from disk first thing so the level/XP shown across the
        // app (and the mutation baseline) is the real saved state from the first frame.
        appScope.launch { safely("Gamification hydrate") { gamificationRepository.get().loadState() } }
        runStartupReconcile()
        scheduleVaultIndexing()
        registerBackgroundObserver()
        // (Re)schedule reminder notifications from the saved preferences. Also
        // creates the notification channels. Cheap; runs off the main thread.
        appScope.launch { safely("Reminder scheduling") { reminderScheduler.get().reschedule() } }
    }

    /**
     * Backfill RAG embeddings in the background. IndexingWorker only embeds files
     * that changed or have no embedding row yet and returns early (no model load)
     * when there's nothing to do — so this is a near-noop once the index is warm.
     * Shares the manual button's unique name with KEEP so an in-flight index isn't disturbed.
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

    /**
     * Whenever the app goes to the background (ProcessLifecycle ON_STOP):
     *  - burn today's board to its markdown file, so a destructive Room migration can never lose
     *    more than the current moment's edits (see DailyDashboardRepository.rehydrateTodayFromDiskIfEmpty);
     *  - rebuild the widget snapshot, so the home-screen widgets reflect what was just done in the app.
     */
    private fun registerBackgroundObserver() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    appScope.launch {
                        safely("Background burn (ON_STOP)") {
                            dailyDashboardRepository.get().burnToDisk(LocalDate.now())
                        }
                        safely("Widget snapshot (ON_STOP)") {
                            widgetUpdateManager.get().scheduleSnapshotUpdate()
                        }
                    }
                }
            }
        )
    }

    /** True only inside the default app process (excludes `:crash`). */
    private fun isMainProcess(): Boolean {
        return getProcessName() == packageName
    }

    /**
     * Local-only reconcile that runs every cold start. Distinct from RAG indexing — this only
     * rebuilds derived Room state from on-disk vault files (file index, today's board after a
     * destructive migration, Mood/Habit/Chore tables, gamification profile).
     * Cheap: no embeddings, no network, no Health Connect, no Google.
     */
    private fun runStartupReconcile() {
        appScope.launch {
            // Cold-start recovery: if a destructive Room migration wiped today's board but
            // the last burn is still on disk, rebuild today's dashboard/scraps/gratitude
            // from that markdown. No-op on a normal launch (guarded on an empty Room).
            // Runs FIRST so no scan or UI initialization can insert a blank today row ahead of it.
            safely("Startup today-rehydrate") { dailyDashboardRepository.get().rehydrateTodayFromDiskIfEmpty() }
            safely("Startup file reindex") { vaultDiscoveryRepository.get().reindexAll(0L) }
            syncOrchestrator.get().reconcileVaultIntoRoom()
        }
    }

    /**
     * Pins the nightly 23:50 burn. KEEP-based at startup (never replaces a run that may be due
     * right now); the worker re-pins itself after each run so the schedule never drifts.
     */
    private fun scheduleDailyBurnWork() {
        appScope.launch {
            safely("Daily burn scheduling") {
                DailyWorkScheduler.ensureScheduled(
                    WorkManager.getInstance(this@PixelBrainApplication),
                    DailyExportWorker.UNIQUE_WORK_NAME,
                    DailyExportWorker.BURN_TIME
                ) { DailyExportWorker.buildRequest() }
            }
        }
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

    private suspend fun safely(label: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("PixelBrainApp", "$label failed", e)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
