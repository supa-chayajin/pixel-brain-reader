package cloud.wafflecommons.pixelbrainreader.widget.ui

import android.content.Context
import androidx.glance.appwidget.updateAll
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

    /**
     * Refreshes the widget UI only. Valid if snapshot is already updated.
     */
    fun triggerUpdate() {
        scope.launch {
            CompanionWidget().updateAll(context)
        }
    }
    
    /**
     * Schedules a background worker to update the snapshot AND then the widget.
     * Use this when app is in background or responding to a remote event.
     */
    fun scheduleSnapshotUpdate() {
        val request = OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
