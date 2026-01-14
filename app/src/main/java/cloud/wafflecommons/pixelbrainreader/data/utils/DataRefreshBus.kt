package cloud.wafflecommons.pixelbrainreader.data.utils

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataRefreshBus @Inject constructor() {
    private val _refreshEvents = MutableSharedFlow<Unit>(replay = 0)
    val refreshEvents: SharedFlow<Unit> = _refreshEvents.asSharedFlow()

    suspend fun notifyDataChanged() {
        _refreshEvents.emit(Unit)
    }
}
