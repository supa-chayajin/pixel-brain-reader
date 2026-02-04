package cloud.wafflecommons.pixelbrainreader.widget.ui

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import cloud.wafflecommons.pixelbrainreader.widget.ui.CompanionWidget

class CompanionWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CompanionWidget()
}
