package cloud.wafflecommons.pixelbrainreader.ui.utils

sealed interface UiEvent {
    data class ShowToast(val message: String) : UiEvent
}
