package cloud.wafflecommons.pixelbrainreader.ui.ai

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.ai.GeminiRagManager
import cloud.wafflecommons.pixelbrainreader.data.ai.GeminiScribeManager
import cloud.wafflecommons.pixelbrainreader.data.ai.ScribePersona
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val content: String,
    val isUser: Boolean,
    val isStreaming: Boolean = false
)

// ChatMode Enum Removed (Scribe Only)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val ragManager: GeminiRagManager,
    private val scribeManager: GeminiScribeManager
) : ViewModel() {

    // UI State
    // We use mutableStateOf for Compose observability
    val messages = mutableStateListOf<ChatMessage>()
    // Mode is now Always Scribe
    // var currentMode by mutableStateOf(ChatMode.SCRIBE) 
    
    var currentPersona by mutableStateOf(ScribePersona.TECH_WRITER)
        private set
    var isLoading by mutableStateOf(false)
        private set

    fun switchPersona(persona: ScribePersona) {
        currentPersona = persona
    }

    fun sendMessage(query: String) {
        if (query.isBlank()) return

        // Add User Message
        messages.add(ChatMessage(content = query, isUser = true))
        
        // Add Placeholder Bot Message
        isLoading = true
        val botMessageId = java.util.UUID.randomUUID().toString()
        messages.add(ChatMessage(id = botMessageId, content = "Thinking...", isUser = false, isStreaming = true))
        
        viewModelScope.launch {
            try {
                // Always use Scribe Manager
                val flow = scribeManager.generateScribeContent(query, currentPersona)

                val sb = StringBuilder()
                var lastUpdate = 0L
                flow.collect { token ->
                    sb.append(token)
                    val currentTime = System.currentTimeMillis()
                    // Throttle updates to ~16ms (60fps) to prevent main thread starvation (ANR Prevention)
                    if (currentTime - lastUpdate > 16) {
                        lastUpdate = currentTime
                        val index = messages.indexOfFirst { it.id == botMessageId }
                        if (index != -1) {
                            messages[index] = messages[index].copy(content = sb.toString())
                        }
                    }
                }
                // Ensure Final Update
                val index = messages.indexOfFirst { it.id == botMessageId }
                if (index != -1) {
                    messages[index] = messages[index].copy(content = sb.toString())
                }
            } catch (e: Exception) {
                // Handle Error
                val index = messages.indexOfFirst { it.id == botMessageId }
                if (index != -1) {
                    messages[index] = messages[index].copy(content = "Error: ${e.message}")
                }
            } finally {
                isLoading = false
                 val index = messages.indexOfFirst { it.id == botMessageId }
                if (index != -1) {
                    messages[index] = messages[index].copy(isStreaming = false)
                }
            }
        }
    }
    
    fun resetChat() {
        messages.clear()
    }
}
