package cloud.wafflecommons.pixelbrainreader.ui.games

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import cloud.wafflecommons.pixelbrainreader.R
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.games.GameSessionController
import cloud.wafflecommons.pixelbrainreader.data.games.GamesRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Quick Settings tile (RFC-009 §4.3): starts a session on the game played last, or stops the running
 * one. A long-press opens the Games tab. Unavailable on a read-only device.
 */
@AndroidEntryPoint
class GameTileService : TileService() {

    @Inject lateinit var controller: GameSessionController

    @Inject lateinit var games: GamesRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        scope.launch { render() }
    }

    override fun onClick() {
        super.onClick()
        if (VaultWriteGate.readOnly) return
        scope.launch {
            if (controller.current() != null) {
                controller.stop()
            } else {
                controller.defaultPage()?.let { controller.start(it) }
            }
            render()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun render() {
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_game_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_game)
        val running = controller.current()
        when {
            VaultWriteGate.readOnly -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.subtitle = null
            }
            running != null -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = games.page(running.page)?.title ?: "Playing"
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = controller.defaultPage()?.title ?: "No game yet"
            }
        }
        tile.updateTile()
    }
}
