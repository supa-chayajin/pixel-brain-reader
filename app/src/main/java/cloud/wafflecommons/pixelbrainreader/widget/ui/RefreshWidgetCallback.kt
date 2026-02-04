package cloud.wafflecommons.pixelbrainreader.widget.ui

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import cloud.wafflecommons.pixelbrainreader.di.WidgetEntryPoint
import dagger.hilt.android.EntryPointAccessors

class RefreshWidgetCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetEntryPoint::class.java
        )
        // Monitor or Trigger Update
        val request = androidx.work.OneTimeWorkRequestBuilder<cloud.wafflecommons.pixelbrainreader.widget.data.WidgetUpdateWorker>().build()
        androidx.work.WorkManager.getInstance(context).enqueue(request)
    }
}
