package cloud.wafflecommons.pixelbrainreader.widget.ui

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class CompanionWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CompanionWidget()

    /**
     * The periodic system refresh used to only re-render the existing snapshot, so steps and the
     * progress rings stayed as old as the last in-app change. Rebuild the snapshot too (it
     * re-renders the widgets when done).
     */
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        runCatching { WidgetLiveData.entryPoint(context).widgetUpdateManager().scheduleSnapshotUpdate() }
    }
}
