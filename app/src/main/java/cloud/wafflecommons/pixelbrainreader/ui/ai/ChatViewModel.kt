package cloud.wafflecommons.pixelbrainreader.ui.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.ai.GeminiRagManager
import cloud.wafflecommons.pixelbrainreader.data.ai.GeminiScribeManager
import cloud.wafflecommons.pixelbrainreader.data.ai.LocalAiManager
import cloud.wafflecommons.pixelbrainreader.data.ai.NanoException
import cloud.wafflecommons.pixelbrainreader.data.ai.NanoState
import cloud.wafflecommons.pixelbrainreader.data.ai.ScribePersona
import cloud.wafflecommons.pixelbrainreader.data.ai.VectorSearchEngine
import cloud.wafflecommons.pixelbrainreader.data.local.entity.ChatMessageEntity
import cloud.wafflecommons.pixelbrainreader.data.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** UI-shape message rendered by [ChatBubble]. */
data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val content: String,
    val isUser: Boolean,
    val isStreaming: Boolean = false,
    val sources: List<String> = emptyList()
)

/** Two persisted chat surfaces. ORACLE = RAG over the vault, SCRIBE = persona-creative. */
enum class ChatMode { SCRIBE, ORACLE }

/** Map UI enum to the storage strings agreed at the data layer boundary. */
private fun ChatMode.storage(): String = when (this) {
    ChatMode.ORACLE -> "RAG"
    ChatMode.SCRIBE -> "CREATIVE"
}

private fun ChatMessageEntity.toUi(): ChatMessage = ChatMessage(
    id = id,
    content = content,
    isUser = role == "USER",
    isStreaming = false,
    sources = sources
)

// Sliding window size injected into the Nano prompt. 6 messages = 3 turns.
// Higher values risk REQUEST_TOO_LARGE on long messages, especially with RAG context.
private const val NANO_WINDOW_SIZE = 6
// Top-K vector hits for RAG grounding.
private const val RAG_TOP_K = 3

private const val RAG_SYSTEM_PROMPT = """
You are PixelBrain Cortex, a personal second-brain assistant grounded in the user's own notes.
Answer using ONLY the REFERENCE INFORMATION block when it is provided.
If the references don't contain the answer, say so plainly and offer your best general thought —
but mark it clearly as not from the user's notes.
Be concise. Cite specific facts from the references when you use them.
"""

private fun creativeSystemPrompt(persona: ScribePersona): String = when (persona) {
    ScribePersona.TECH_WRITER ->
        "You are a senior technical writer. Produce clear, precise, well-structured prose. " +
        "Prefer concrete examples over abstractions. Avoid filler."
    ScribePersona.CODER ->
        "You are a senior Kotlin/Android engineer. Produce idiomatic, production-quality code. " +
        "When uncertain about an API, say so rather than guessing. Explain trade-offs."
    ScribePersona.PLANNER ->
        "You are a pragmatic planner. Break the task into a short ordered list of steps. " +
        "Surface dependencies, risks, and the first concrete action."
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val ragManager: GeminiRagManager,
    private val scribeManager: GeminiScribeManager,
    private val localAiManager: LocalAiManager,
    private val chatRepository: ChatRepository,
    private val vectorSearchEngine: VectorSearchEngine
) : ViewModel() {

    // --- Mode + persona (UI-only state) --------------------------------------

    private val _currentMode = MutableStateFlow(ChatMode.ORACLE)
    val currentMode: StateFlow<ChatMode> = _currentMode.asStateFlow()

    var currentPersona by mutableStateOf(ScribePersona.TECH_WRITER)
        private set

    // --- Persisted history (per-mode Flow) -----------------------------------

    /**
     * Full chronological history for the currently-selected mode, mapped to UI shape.
     * `flatMapLatest` swaps the upstream Flow whenever the user toggles modes —
     * old subscriptions cancel cleanly so we never blend the two histories.
     */
    val chatHistory: StateFlow<List<ChatMessage>> = _currentMode
        .flatMapLatest { mode -> chatRepository.streamMessages(mode.storage()) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )
        .let { entityFlow ->
            // Map at the boundary so the bubble composable stays Room-agnostic.
            kotlinx.coroutines.flow.MutableStateFlow<List<ChatMessage>>(emptyList()).also { ui ->
                viewModelScope.launch {
                    entityFlow.collect { entities -> ui.value = entities.map { it.toUi() } }
                }
            }
        }

    // --- Transient streaming overlay (cloud only — Nano is one-shot) ---------

    /**
     * When the cloud fallback path is streaming, this holds the in-progress bubble
     * appended after [chatHistory]. Cleared once the final response is persisted to Room.
     */
    private val _streamingMessage = MutableStateFlow<ChatMessage?>(null)
    val streamingMessage: StateFlow<ChatMessage?> = _streamingMessage.asStateFlow()

    // --- Ambient state -------------------------------------------------------

    var loadingStage by mutableStateOf<String?>(null)
        private set

    val nanoState: StateFlow<NanoState> = localAiManager.nanoState

    var showCloudFallbackDialog by mutableStateOf(false)
        private set
    var cloudFallbackReason: String? by mutableStateOf(null)
        private set
    private var pendingCloudPrompt: String? = null

    fun switchPersona(persona: ScribePersona) {
        currentPersona = persona
    }

    fun toggleMode() {
        _currentMode.value =
            if (_currentMode.value == ChatMode.SCRIBE) ChatMode.ORACLE else ChatMode.SCRIBE
    }

    // --- Send pipeline -------------------------------------------------------

    /**
     * 1. Persist user turn to Room (atomic — never lost on crash mid-call).
     * 2. Fetch the last N messages including the just-saved query, then drop it
     *    to derive the *prior* history for the sliding window.
     * 3. If RAG mode, run a single vector search to get both grounding context
     *    and citation file IDs.
     * 4. Call Gemini Nano with the augmented prompt.
     * 5. On success: persist the model turn (with sources for RAG).
     * 6. On failure: raise the cloud-fallback consent dialog (unchanged contract).
     */
    fun sendMessage(query: String) {
        if (query.isBlank()) return

        viewModelScope.launch {
            val mode = _currentMode.value
            val modeStorage = mode.storage()

            // 1. Persist user turn immediately.
            chatRepository.addMessage(
                ChatMessageEntity(
                    mode = modeStorage,
                    role = "USER",
                    content = query
                )
            )

            // 2. Sliding window = last NANO_WINDOW_SIZE messages, minus the one just saved.
            val window = chatRepository.recentForPrompt(modeStorage, NANO_WINDOW_SIZE + 1)
            val priorHistory = if (window.isNotEmpty()) window.dropLast(1) else emptyList()

            // 3. RAG context (single search call, reuse for both grounding + citations).
            var sources = emptyList<String>()
            var ragContext: String? = null
            if (mode == ChatMode.ORACLE) {
                loadingStage = "🔎 Searching your Second Brain…"
                val hits = vectorSearchEngine.search(query, limit = RAG_TOP_K)
                ragContext = hits.takeIf { it.isNotEmpty() }
                    ?.joinToString(separator = "\n---\n") { it.content }
                sources = hits.map { it.fileId }.distinct()
            }

            // 4. Nano call.
            loadingStage = "🔒 Asking Gemini Nano (on-device)…"
            val systemPrompt = if (mode == ChatMode.ORACLE) RAG_SYSTEM_PROMPT
                               else creativeSystemPrompt(currentPersona)
            val result = localAiManager.generateAugmentedResponse(
                systemPrompt = systemPrompt,
                ragContext = ragContext,
                chatHistory = priorHistory,
                currentQuery = query
            )
            loadingStage = null

            result.fold(
                onSuccess = { text ->
                    chatRepository.addMessage(
                        ChatMessageEntity(
                            mode = modeStorage,
                            role = "MODEL",
                            content = text,
                            sources = sources
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

    fun onConfirmCloudFallback() {
        val query = pendingCloudPrompt ?: run {
            dismissDialog()
            return
        }
        dismissDialog()
        runCloudGeneration(query)
    }

    fun onDismissCloudFallback() = dismissDialog()

    private fun dismissDialog() {
        showCloudFallbackDialog = false
        cloudFallbackReason = null
        pendingCloudPrompt = null
    }

    /**
     * Cloud fallback. Streams tokens into a transient [streamingMessage] overlay
     * so the UI has live feedback; persists the final aggregated response to Room
     * on completion so it's preserved across app kill and mode-toggles.
     *
     * The user message was already persisted at the start of [sendMessage] — we
     * don't re-persist it here.
     */
    private fun runCloudGeneration(query: String) {
        val mode = _currentMode.value
        val modeStorage = mode.storage()
        val transientId = java.util.UUID.randomUUID().toString()
        _streamingMessage.value = ChatMessage(
            id = transientId, content = "", isUser = false, isStreaming = true
        )

        viewModelScope.launch {
            var sources: List<String> = emptyList()
            try {
                if (mode == ChatMode.ORACLE) {
                    loadingStage = "🔎 Searching your Second Brain (cloud)…"
                    sources = ragManager.findSources(query)
                    loadingStage = if (sources.isNotEmpty())
                        "🧠 Analyzing ${sources.size} notes…" else "✨ No relevant notes; switching to open answer…"
                } else {
                    loadingStage = "✨ Sparking creativity…"
                }

                val flow = if (mode == ChatMode.ORACLE) {
                    ragManager.generateResponse(query, useRAG = true)
                } else {
                    scribeManager.generateScribeContent(query, currentPersona)
                }

                loadingStage = "☁️ Generating answer (Cloud)…"

                val sb = StringBuilder()
                var lastUpdate = 0L
                flow.collect { token ->
                    sb.append(token)
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 16) {
                        lastUpdate = now
                        _streamingMessage.value = _streamingMessage.value?.copy(
                            content = sb.toString(), sources = sources
                        )
                    }
                }
                // Final paint of the streaming overlay before we hand off to Room.
                _streamingMessage.value = _streamingMessage.value?.copy(
                    content = sb.toString(), sources = sources, isStreaming = false
                )

                // Persist the completed cloud response so it survives app kill /
                // mode toggles. Room flow re-emission will render it; the overlay
                // is cleared on the same frame to avoid a duplicate bubble.
                chatRepository.addMessage(
                    ChatMessageEntity(
                        mode = modeStorage,
                        role = "MODEL",
                        content = sb.toString(),
                        sources = sources
                    )
                )
                _streamingMessage.value = null
            } catch (e: Exception) {
                _streamingMessage.value = _streamingMessage.value?.copy(
                    content = "Error: ${e.message}", isStreaming = false
                )
                // Leave the transient bubble visible so the user sees the error;
                // it disappears on the next send or mode toggle.
            } finally {
                loadingStage = null
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

    /** Clear history for the active mode. The other mode's history is untouched. */
    fun resetChat() {
        val modeStorage = _currentMode.value.storage()
        viewModelScope.launch {
            chatRepository.clear(modeStorage)
            _streamingMessage.value = null
        }
    }
}
