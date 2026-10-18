package cloud.wafflecommons.pixelbrainreader.ui.work

import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import cloud.wafflecommons.pixelbrainreader.R
import cloud.wafflecommons.pixelbrainreader.widget.ui.WidgetNav

/** Quick Settings tile: opens the mission log on a new entry (after unlocking: it is work content). */
class MissionLogTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_log_label)
        tile.state = Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_log)
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val pending = PendingIntent.getActivity(
            this,
            0,
            WidgetNav.openIntent(this, WidgetNav.SCREEN_LOG),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (isLocked) unlockAndRun { startActivityAndCollapse(pending) } else startActivityAndCollapse(pending)
    }
}
