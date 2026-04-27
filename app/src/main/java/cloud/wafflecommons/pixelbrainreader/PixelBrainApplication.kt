package cloud.wafflecommons.pixelbrainreader

import android.app.Application
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
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
        registerForegroundSyncObserver()
    }

    /**
     * Registers a ProcessLifecycleOwner observer that triggers a full
     * Git→Health→Git sync cycle every time the app comes to the foreground.
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

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
