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

enum class ChatMode {
    RAG, // Search & Answer
    SCRIBE // Generate & Create
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val ragManager: GeminiRagManager,
    private val scribeManager: GeminiScribeManager
) : ViewModel() {

    // UI State
    // We use mutableStateOf for Compose observability
    val messages = mutableStateListOf<ChatMessage>()
    var currentMode by mutableStateOf(ChatMode.RAG)
        private set
    var currentPersona by mutableStateOf(ScribePersona.TECH_WRITER)
        private set
    var isLoading by mutableStateOf(false)
        private set

    fun switchMode(mode: ChatMode) {
        currentMode = mode
    }

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
        messages.add(ChatMessage(id = botMessageId, content = "AI is thinking...", isUser = false, isStreaming = true))
        
        viewModelScope.launch {
            try {
                val flow = if (currentMode == ChatMode.RAG) {
                    ragManager.askQuestion(query)
                } else {
                    scribeManager.generateScribeContent(query, currentPersona)
                }

                val sb = StringBuilder()
                flow.collect { token ->
                    sb.append(token)
                    // Efficiently update the last message (streaming effect)
                    // In a real app complexity, we'd use a more robust StateFlow logic.
                    // For this sprint, simple list replacement works.
                    val index = messages.indexOfFirst { it.id == botMessageId }
                    if (index != -1) {
                        messages[index] = messages[index].copy(content = sb.toString())
                    }
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
