package cloud.wafflecommons.pixelbrainreader.widget.ui

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import cloud.wafflecommons.pixelbrainreader.widget.data.WidgetUpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WidgetUpdateManager @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {
    /**
     * Refreshes the widget UI only. Valid if snapshot is already updated.
     */
    fun triggerUpdate() {
        CoroutineScope(Dispatchers.IO).launch {
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
