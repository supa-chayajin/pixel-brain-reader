package cloud.wafflecommons.pixelbrainreader.data.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import javax.inject.Inject
import javax.inject.Singleton

/** A first message for a chat preset, prepared by another screen ("Ask Coach"). */
data class ChatSeed(val presetId: String, val message: String)

/**
 * Hands one seed from a work screen to the chat: the screen [offer]s it and opens the chat, whose
 * ViewModel [take]s it once — switching to the seed's preset and sending its message. A newer
 * seed replaces one not yet taken. Nothing is persisted: it lives as long as the process.
 */
@Singleton
class ChatSeedRepository @Inject constructor() {

    private val _seed = MutableStateFlow<ChatSeed?>(null)
    val seed: StateFlow<ChatSeed?> = _seed.asStateFlow()

    fun offer(seed: ChatSeed) {
        _seed.value = seed
    }

    /** The pending seed, at most once: null when none is waiting or it was already taken. */
    fun take(): ChatSeed? = _seed.getAndUpdate { null }
}
