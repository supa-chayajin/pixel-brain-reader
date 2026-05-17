package cloud.wafflecommons.pixelbrainreader.ui.ai

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.ai.GeminiRagManager
import cloud.wafflecommons.pixelbrainreader.data.ai.GeminiScribeManager
import cloud.wafflecommons.pixelbrainreader.data.ai.LocalAiManager
import cloud.wafflecommons.pixelbrainreader.data.ai.NanoException
import cloud.wafflecommons.pixelbrainreader.data.ai.NanoState
import cloud.wafflecommons.pixelbrainreader.data.ai.ScribePersona
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val content: String,
    val isUser: Boolean,
    val isStreaming: Boolean = false,
    val sources: List<String> = emptyList()
)

// Modes: Scribe (Persona-based) vs Oracle (RAG-based)
enum class ChatMode { SCRIBE, ORACLE }

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val ragManager: GeminiRagManager,
    private val scribeManager: GeminiScribeManager,
    private val localAiManager: LocalAiManager
) : ViewModel() {

    // UI State
    val messages = mutableStateListOf<ChatMessage>()
    var currentMode by mutableStateOf(ChatMode.ORACLE)

    var currentPersona by mutableStateOf(ScribePersona.TECH_WRITER)
        private set

    // Granular Loading State (null = idle)
    var loadingStage by mutableStateOf<String?>(null)
        private set

    // On-device Nano availability — drives the ⚡ indicator
    val nanoState: StateFlow<NanoState> = localAiManager.nanoState

    // Cloud opt-in dialog state — strictly user-driven, never auto-confirmed
    var showCloudFallbackDialog by mutableStateOf(false)
        private set
    var cloudFallbackReason: String? by mutableStateOf(null)
        private set
    private var pendingCloudPrompt: String? = null

    fun switchPersona(persona: ScribePersona) {
        currentPersona = persona
    }

    fun toggleMode() {
        currentMode = if (currentMode == ChatMode.SCRIBE) ChatMode.ORACLE else ChatMode.SCRIBE
    }

    /**
     * Always tries Gemini Nano (on-device) first. On any failure, the cloud fallback dialog
     * is raised so the user can give explicit consent before any data leaves the device.
     */
    fun sendMessage(query: String) {
        if (query.isBlank()) return

        messages.add(ChatMessage(content = query, isUser = true))

        viewModelScope.launch {
            loadingStage = "🔒 Asking Gemini Nano (on-device)…"
            val localResult = localAiManager.generateResponse(query)
            loadingStage = null

            localResult.fold(
                onSuccess = { text ->
                    messages.add(
                        ChatMessage(
                            content = text,
                            isUser = false,
                            isStreaming = false
                        )
                    )
                },
                onFailure = { e ->
                    pendingCloudPrompt = query
                    cloudFallbackReason = describeFailure(e)
                    showCloudFallbackDialog = true
                }
            )
        }
    }

    /** User tapped "Use Cloud" — only NOW is the cloud manager invoked. */
    fun onConfirmCloudFallback() {
        val query = pendingCloudPrompt ?: run {
            dismissDialog()
            return
        }
        dismissDialog()
        runCloudGeneration(query)
    }

    /** User tapped "Cancel" — nothing leaves the device. */
    fun onDismissCloudFallback() {
        dismissDialog()
    }

    private fun dismissDialog() {
        showCloudFallbackDialog = false
        cloudFallbackReason = null
        pendingCloudPrompt = null
    }

    private fun runCloudGeneration(query: String) {
        val botMessageId = java.util.UUID.randomUUID().toString()
        messages.add(ChatMessage(id = botMessageId, content = "", isUser = false, isStreaming = true))

        viewModelScope.launch {
            try {
                var sources: List<String> = emptyList()

                if (currentMode == ChatMode.ORACLE) {
                    loadingStage = "🔎 Searching your Second Brain..."
                    sources = ragManager.findSources(query)

                    loadingStage = if (sources.isNotEmpty()) {
                        "🧠 Analyzing ${sources.size} notes..."
                    } else {
                        "✨ No relevant notes found. Switching to creative mode..."
                    }
                } else {
                    loadingStage = "✨ Sparking creativity..."
                }

                val flow = if (currentMode == ChatMode.ORACLE) {
                    ragManager.generateResponse(query, useRAG = true)
                } else {
                    scribeManager.generateScribeContent(query, currentPersona)
                }

                loadingStage = "☁️ Generating answer (Cloud)…"

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

    private fun describeFailure(e: Throwable): String = when (e) {
        is NanoException.ContextExceeded -> "Your prompt is too long for the on-device model."
        is NanoException.Unavailable -> "Gemini Nano is unavailable on this device (${e.reason})."
        is NanoException.BadInput -> "The on-device model rejected the prompt (${e.reason})."
        is NanoException.EmptyResponse -> "The on-device model returned no answer."
        is NanoException.Generation -> "Gemini Nano failed: ${e.cause?.message ?: e.message}"
        else -> "On-device AI unavailable: ${e.message ?: "unknown error"}"
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
