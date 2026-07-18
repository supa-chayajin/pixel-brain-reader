package cloud.wafflecommons.pixelbrainreader.widget.ui

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.updateAll
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import cloud.wafflecommons.pixelbrainreader.widget.data.WidgetUpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WidgetUpdateManager @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {
    // One structured, process-lived scope instead of a fresh unmanaged CoroutineScope per
    // call — a SupervisorJob isolates failures and there is a single owner for the work.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Every widget class in the suite — re-rendered together so one refresh updates all of them. */
    private val allWidgets: List<GlanceAppWidget>
        get() = listOf(
            CompanionWidget(),
            HealthWidget(),
            MoodWidget(),
            HabitsWidget(),
            TodayWidget(),
            ChoresWidget(),
            QuickActionsWidget()
        )

    /**
     * Re-renders every widget UI. Valid when the snapshot is already up to date (the live widgets
     * re-read their own data on render anyway). `updateAll` is a no-op for a widget type that isn't
     * currently placed, so this stays cheap.
     */
    fun triggerUpdate() {
        scope.launch {
            allWidgets.forEach { widget ->
                runCatching { widget.updateAll(context) }
            }
        }
    }

    /**
     * Schedules a background worker to rebuild the data snapshot AND then re-render the widgets.
     * Use this after a data mutation or when the app is backgrounded.
     *
     * Enqueued as UNIQUE work with [ExistingWorkPolicy.REPLACE] so a burst of mutations (e.g. a
     * sync reconciling many habits/chores, or several quick taps) coalesces into a single rebuild
     * against the latest state instead of spawning one worker per mutation.
     */
    fun scheduleSnapshotUpdate() {
        val request = OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_SNAPSHOT_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private companion object {
        const val UNIQUE_SNAPSHOT_WORK = "widget_snapshot_update"
    }
}
