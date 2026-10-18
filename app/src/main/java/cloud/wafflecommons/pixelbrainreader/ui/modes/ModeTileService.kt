package cloud.wafflecommons.pixelbrainreader.ui.modes

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import cloud.wafflecommons.pixelbrainreader.R
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Quick Settings tile: tap to cycle Work → Personal → Off; long-press opens the modes settings. */
@AndroidEntryPoint
class ModeTileService : TileService() {

    @Inject lateinit var modesRepository: ModesRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        scope.launch { render(modesRepository.currentVisibility()) }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val next = when (modesRepository.currentVisibility().mode) {
                ContextMode.WORK -> ContextMode.PERSONAL
                ContextMode.PERSONAL -> ContextMode.OFF
                ContextMode.OFF -> ContextMode.WORK
            }
            modesRepository.setManualMode(next)
            render(modesRepository.currentVisibility())
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render(visibility: Visibility) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_mode_label)
        tile.subtitle = visibility.mode.label() + if (visibility.screenShareSafe) " · Safe" else ""
        tile.state = if (visibility.mode == ContextMode.WORK) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(
            this,
            when (visibility.mode) {
                ContextMode.WORK -> R.drawable.ic_tile_work
                ContextMode.PERSONAL -> R.drawable.ic_tile_personal
                ContextMode.OFF -> R.drawable.ic_tile_off
            }
        )
        tile.updateTile()
    }
}
