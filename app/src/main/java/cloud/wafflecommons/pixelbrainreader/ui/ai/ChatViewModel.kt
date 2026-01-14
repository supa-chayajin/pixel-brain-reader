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
    val isStreaming: Boolean = false,
    val sources: List<String> = emptyList() // Renamed to 'sources' per request
)

// Modes: Scribe (Persona-based) vs Oracle (RAG-based)
enum class ChatMode { SCRIBE, ORACLE }

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val ragManager: GeminiRagManager,
    private val scribeManager: GeminiScribeManager
) : ViewModel() {

    // UI State
    val messages = mutableStateListOf<ChatMessage>()
    var currentMode by mutableStateOf(ChatMode.ORACLE) // Default to RAG for V4.0
    
    var currentPersona by mutableStateOf(ScribePersona.TECH_WRITER)
        private set
        
    // Granular Loading State (null = idle)
    var loadingStage by mutableStateOf<String?>(null)
        private set

    fun switchPersona(persona: ScribePersona) {
        currentPersona = persona
    }
    
    fun toggleMode() {
        currentMode = if (currentMode == ChatMode.SCRIBE) ChatMode.ORACLE else ChatMode.SCRIBE
    }

    fun sendMessage(query: String) {
        if (query.isBlank()) return

        // Add User Message
        messages.add(ChatMessage(content = query, isUser = true))
        
        // Add Placeholder Bot Message
        val botMessageId = java.util.UUID.randomUUID().toString()
        messages.add(ChatMessage(id = botMessageId, content = "", isUser = false, isStreaming = true))
        
        viewModelScope.launch {
            try {
                // 1. Fetch Sources if in Oracle Mode
                var sources: List<String> = emptyList()
                
                if (currentMode == ChatMode.ORACLE) {
                    loadingStage = "🔎 Searching your Second Brain..."
                    sources = ragManager.findSources(query)
                    
                    if (sources.isNotEmpty()) {
                        loadingStage = "🧠 Analyzing ${sources.size} notes..."
                    } else {
                        loadingStage = "✨ No relevant notes found. Switching to creative mode..."
                    }
                } else {
                    loadingStage = "✨ Sparking creativity..."
                }

                // 2. Select Flow based on Mode
                val flow = if (currentMode == ChatMode.ORACLE) {
                    ragManager.generateResponse(query, useRAG = true)
                } else {
                    scribeManager.generateScribeContent(query, currentPersona)
                }
                
                // Start Generation
                loadingStage = "⚡ Generating answer..."

                val sb = StringBuilder()
                var lastUpdate = 0L
                
                flow.collect { token ->
                    sb.append(token)
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastUpdate > 16) {
                        lastUpdate = currentTime
                        updateMessage(botMessageId, sb.toString(), sources)
                    }
                }
                // Final Update
                updateMessage(botMessageId, sb.toString(), sources)
                
            } catch (e: Exception) {
                updateMessage(botMessageId, "Error: ${e.message}", emptyList())
            } finally {
                loadingStage = null
                val index = messages.indexOfFirst { it.id == botMessageId }
                if (index != -1) {
                    messages[index] = messages[index].copy(isStreaming = false)
                }
            }
        }
    }
    
    private fun updateMessage(id: String, content: String, sources: List<String>) {
        val index = messages.indexOfFirst { it.id == id }
        if (index != -1) {
            messages[index] = messages[index].copy(content = content, sources = sources)
        }
    }
    
    fun resetChat() {
        messages.clear()
    }
}
