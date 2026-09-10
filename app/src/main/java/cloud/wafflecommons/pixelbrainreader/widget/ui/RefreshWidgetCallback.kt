package cloud.wafflecommons.pixelbrainreader.widget.ui

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback

class RefreshWidgetCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        // The shared unique, expedited rebuild: a plain enqueue here bypassed the coalescing (one
        // rebuild per tap) and could be deferred by the system.
        WidgetLiveData.entryPoint(context).widgetUpdateManager().scheduleSnapshotUpdate()
    }
}
