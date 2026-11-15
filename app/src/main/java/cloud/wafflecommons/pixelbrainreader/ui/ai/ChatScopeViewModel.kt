package cloud.wafflecommons.pixelbrainreader.ui.ai

import androidx.lifecycle.ViewModel
import cloud.wafflecommons.pixelbrainreader.data.ai.ChatScope
import cloud.wafflecommons.pixelbrainreader.data.ai.ChatScopeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** "Ask this note" and "Ask this folder" (K2, 12.20): hands the scope to Chat, which opens on it. */
@HiltViewModel
class ChatScopeViewModel @Inject constructor(
    private val scopes: ChatScopeRepository
) : ViewModel() {
    fun ask(scope: ChatScope) = scopes.offer(scope)
}
