package cloud.wafflecommons.pixelbrainreader.data.utils

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataRefreshBus @Inject constructor() {
    private val _refreshEvent = MutableSharedFlow<Unit>(replay = 0)
    val refreshEvent: SharedFlow<Unit> = _refreshEvent.asSharedFlow()

    suspend fun triggerRefresh() {
        _refreshEvent.emit(Unit)
    }
}
