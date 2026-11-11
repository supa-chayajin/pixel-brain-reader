package cloud.wafflecommons.pixelbrainreader.ui.focus

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import cloud.wafflecommons.pixelbrainreader.R
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.focus.FocusRepository
import cloud.wafflecommons.pixelbrainreader.data.focus.FocusSession
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Quick Settings tile (D9): starts a 50-minute focus session, or stops the running one. Shown as
 * unavailable on a read-only device (a session logs and earns XP).
 */
@AndroidEntryPoint
class FocusTileService : TileService() {

    @Inject lateinit var repository: FocusRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        scope.launch { render(repository.current()) }
    }

    override fun onClick() {
        super.onClick()
        if (VaultWriteGate.readOnly) return
        scope.launch {
            if (repository.current() != null) repository.stop() else repository.start(FocusSession.DEFAULT_MINUTES)
            render(repository.current())
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render(session: FocusSession?) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_focus_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_focus)
        when {
            VaultWriteGate.readOnly -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.subtitle = null
            }
            session != null && !session.isOver(System.currentTimeMillis()) -> {
                val left = ((session.endMillis - System.currentTimeMillis()) / 60_000L + 1).coerceAtLeast(1)
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "$left min left"
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "${FocusSession.DEFAULT_MINUTES} min"
            }
        }
        tile.updateTile()
    }
}
