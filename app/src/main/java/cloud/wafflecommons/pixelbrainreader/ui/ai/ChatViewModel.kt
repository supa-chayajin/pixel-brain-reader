package cloud.wafflecommons.pixelbrainreader.ui.ai

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.ai.LocalAiManager
import cloud.wafflecommons.pixelbrainreader.data.ai.NanoException
import cloud.wafflecommons.pixelbrainreader.data.ai.NanoState
import cloud.wafflecommons.pixelbrainreader.data.ai.VectorSearchEngine
import cloud.wafflecommons.pixelbrainreader.data.local.entity.ChatMessageEntity
import cloud.wafflecommons.pixelbrainreader.data.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Inject

/** UI-shape message rendered by [ChatBubble]. */
data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val content: String,
    val isUser: Boolean,
    val isStreaming: Boolean = false,
    val sources: List<String> = emptyList()
)

/** Three persisted chat surfaces. ORACLE = RAG over the vault, SCRIBE = open chat, BOARDROOM = architecture debate. */
enum class ChatMode { SCRIBE, ORACLE, BOARDROOM }

/** Map UI enum to the storage strings agreed at the data layer boundary. */
private fun ChatMode.storage(): String = when (this) {
    ChatMode.ORACLE -> "RAG"
    ChatMode.SCRIBE -> "CREATIVE"
    ChatMode.BOARDROOM -> "BOARDROOM"
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
private const val RAG_TOP_K = 6
// Overall budget for a streamed on-device answer. Tokens render as they arrive, so a partial
// answer is still shown; if nothing arrives in time we surface a timeout notice.
private const val STREAM_TIMEOUT_MS = 120_000L

/**
 * Single persona used for BOTH chat surfaces (Cortex / RAG and Spark / open chat).
 * Mode only controls whether the vector store is queried, not the model's voice.
 *
 * Tuned for SHORT answers: the on-device model is one-shot, so long, heavily-formatted
 * replies make the user wait on a spinner. Keeping answers terse makes generation prompt.
 */
private const val PIXEL_BRAIN_PERSONA = """
Tu es l'assistant IA de l'application Cortex (PixelBrainReader). Tu dois TOUJOURS répondre en français, quelle que soit la langue de la question ou des notes.

RÔLE : un assistant analytique, structuré, pragmatique et bienveillant. Tu aides l'utilisateur à exploiter son deuxième cerveau, ses notes d'architecture et son organisation quotidienne.

STYLE ET FORMAT :
- Réponds de manière claire, structurée et complète, sans troncature artificielle.
- Organise tes réponses avec des puces Markdown ou des sous-titres courts quand cela apporte de la lisibilité.
- Référence explicitement les notes pertinentes avec des liens Markdown [[Titre de la note]].

RÉFÉRENCES :
- Fonde prioritairement tes réponses sur les INFORMATIONS DE RÉFÉRENCE ci-dessous issues du coffre Obsidian.
- Si l'information n'est pas dans les notes de référence, réponds avec tes connaissances générales en précisant avec franchise ce qui manque dans les notes.
"""

private const val BOARDROOM_PERSONA = """
Tu es le Conseil d'Architecture Virtuel (Cortex Boardroom) de Leslie Petrimaux (Lead Architect & DevSecOps).
Tu organises un débat d'experts de très haut niveau entre trois personnalités complémentaires et intransigeantes, avant de fournir une synthèse collégiale tranchée :

1. 🏛️ Alexandre (Architecte DDD & Clean Architecture) :
   - Obsédé par le Domain-Driven Design, les Bounded Contexts, le langage omniprésent et la pureté des entités de domaine.
   - Traque l'anémie de domaine, le couplage temporel ou technique, et les fuites d'abstraction.

2. 🛡️ Bastien (Lead DevSecOps & Fiabilité Système) :
   - Obsédé par le principe du moindre privilège, la sécurité fail-closed, la gestion des secrets, l'auditabilité et les pipelines CI/CD reproductibles.
   - Analyse les modes de défaillance, la scalabilité, les conteneurs et les risques opérationnels en production.

3. ⚡ Camille (Tech Lead Pragmatique & Delivery) :
   - Obsédée par le pragmatisme, le ratio effort/valeur, l'évitement de l'over-engineering (YAGNI / KISS) et la vitesse de livraison.
   - Pousse pour des compromis viables et met en garde contre les usines à gaz théoriques.

DIRECTIVES DE FORMAT :
- Fonde le débat prioritairement sur les INFORMATIONS DE RÉFÉRENCE ci-dessous (notes du coffre Obsidian, projets, architecture).
- Structure TOUJOURS la réponse en 4 sections claires avec des séparateurs Markdown :
  ### 🏛️ Alexandre (DDD & Architecture)
  (Analyse du modèle, frontières contextuelles, patterns)
  ### 🛡️ Bastien (DevSecOps & Sécurité)
  (Risques d'exploitation, CI/CD, résilience, secrets)
  ### ⚡ Camille (Pragmatisme & Delivery)
  (Mise en œuvre concrète, arbitrage charge/valeur, quick wins)
  ### 🎯 Synthèse & Décision d'Architecture
  (Recommandation collégiale tranchée avec prochaines étapes concrètes et références aux notes [[NomDeLaNote]])
- Réponds TOUJOURS en français technique impeccable.
"""

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val localAiManager: LocalAiManager,
    private val chatRepository: ChatRepository,
    private val vectorSearchEngine: VectorSearchEngine
) : ViewModel() {

    // --- Mode (UI-only state) ------------------------------------------------

    private val _currentMode = MutableStateFlow(ChatMode.ORACLE)
    val currentMode: StateFlow<ChatMode> = _currentMode.asStateFlow()

    // --- Persisted history (per-mode Flow) -----------------------------------

    /**
     * Full chronological history for the currently-selected mode, mapped to UI shape.
     * `flatMapLatest` swaps the upstream Flow whenever the user toggles modes —
     * old subscriptions cancel cleanly so we never blend the two histories.
     */
    val chatHistory: StateFlow<List<ChatMessage>> = _currentMode
        .flatMapLatest { mode -> chatRepository.streamMessages(mode.storage()) }
        // Map at the boundary so the bubble composable stays Room-agnostic. Mapping in the
        // pipeline (instead of an extra MutableStateFlow fed by a permanent collector) keeps
        // WhileSubscribed meaningful — the Room query actually stops ~5s after the Chat
        // screen is backgrounded instead of running for the ViewModel's whole life.
        .map { entities -> entities.map { it.toUi() } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    // --- Ambient state -------------------------------------------------------

    var loadingStage by mutableStateOf<String?>(null)
        private set

    val nanoState: StateFlow<NanoState> = localAiManager.nanoState

    /**
     * Transient streaming bubble appended after [chatHistory] while a response streams in.
     * Cleared once the final answer is persisted to Room.
     */
    private val _streamingMessage = MutableStateFlow<ChatMessage?>(null)
    val streamingMessage: StateFlow<ChatMessage?> = _streamingMessage.asStateFlow()

    /** The in-flight generation launched by [sendMessage], so [resetChat] can cancel it. */
    private var generationJob: Job? = null

    fun setMode(mode: ChatMode) {
        _currentMode.value = mode
    }

    fun toggleMode() {
        _currentMode.value = when (_currentMode.value) {
            ChatMode.ORACLE -> ChatMode.BOARDROOM
            ChatMode.BOARDROOM -> ChatMode.SCRIBE
            ChatMode.SCRIBE -> ChatMode.ORACLE
        }
    }

    // --- Send pipeline -------------------------------------------------------

    /**
     * 1. Persist user turn to Room (atomic — never lost on crash mid-call).
     * 2. Derive the *prior* sliding-window history.
     * 3. In ORACLE and BOARDROOM modes, run one vector search for grounding context + citations.
     * 4. Generate on-device via Gemini Nano.
     * 5. On success: persist the model turn. On failure: persist an inline error turn
     *    (there is no cloud fallback — the app is 100% on-device).
     */
    fun sendMessage(query: String) {
        if (query.isBlank()) return
        // One answer at a time: a second send used to start a parallel generation that wrote into
        // the same streaming bubble.
        if (generationJob?.isActive == true) return

        generationJob = viewModelScope.launch {
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
            if (mode == ChatMode.ORACLE || mode == ChatMode.BOARDROOM) {
                loadingStage = if (mode == ChatMode.BOARDROOM) "🏛️ Summoning the Architecture Boardroom…" else "🔎 Searching your Second Brain…"
                val hits = vectorSearchEngine.search(query, limit = RAG_TOP_K)
                ragContext = hits.takeIf { it.isNotEmpty() }
                    ?.joinToString(separator = "\n---\n") { hit ->
                        val noteName = hit.fileId.substringAfterLast("/").removeSuffix(".md").removeSuffix(".enc")
                        "### Note : [[$noteName]] (${hit.fileId})\n${hit.content.trim()}"
                    }
                sources = hits.map { it.fileId }.distinct()
                Log.d(
                    "RAG_DEBUG",
                    "ChatViewModel.sendMessage($mode): hits=${hits.size} " +
                        "sources=$sources contextLen=${ragContext?.length ?: 0}"
                )
            } else {
                Log.d("RAG_DEBUG", "ChatViewModel.sendMessage(SCRIBE): RAG search skipped")
            }

            // 4. On-device Nano STREAMING call.
            loadingStage = if (mode == ChatMode.BOARDROOM) "🏛️ Boardroom debating (on-device)…" else "🔒 Asking Gemini Nano (on-device)…"
            _streamingMessage.value =
                ChatMessage(content = "", isUser = false, isStreaming = true, sources = sources)
            val sb = StringBuilder()
            val persona = when (mode) {
                ChatMode.BOARDROOM -> BOARDROOM_PERSONA
                ChatMode.ORACLE, ChatMode.SCRIBE -> PIXEL_BRAIN_PERSONA
            }
            try {
                withTimeout(STREAM_TIMEOUT_MS) {
                    localAiManager.generateAugmentedResponseStream(
                        systemPrompt = persona,
                        ragContext = ragContext,
                        chatHistory = priorHistory,
                        currentQuery = query
                    ).collect { delta ->
                        // First token: drop the "Asking…" label — the bubble takes over.
                        if (loadingStage != null) loadingStage = null
                        sb.append(delta)
                        _streamingMessage.value = _streamingMessage.value?.copy(content = sb.toString())
                    }
                }
                loadingStage = null
                val finalText = sb.toString()
                chatRepository.addMessage(
                    ChatMessageEntity(
                        mode = modeStorage,
                        role = "MODEL",
                        content = finalText.ifBlank { "⚠️ The on-device model returned no response." },
                        sources = if (finalText.isBlank()) emptyList() else sources
                    )
                )
                _streamingMessage.value = null
            } catch (e: TimeoutCancellationException) {
                loadingStage = null
                val partial = sb.toString()
                chatRepository.addMessage(
                    ChatMessageEntity(
                        mode = modeStorage,
                        role = "MODEL",
                        content = if (partial.isNotBlank()) "$partial\n\n⚠️ (response interrupted — timed out)"
                            else "⚠️ Gemini Nano didn't respond in time.",
                        sources = if (partial.isBlank()) emptyList() else sources
                    )
                )
                _streamingMessage.value = null
            } catch (e: CancellationException) {
                // e.g. resetChat cancelled us mid-stream: drop the overlay, persist nothing.
                _streamingMessage.value = null
                throw e
            } catch (e: Exception) {
                loadingStage = null
                val partial = sb.toString()
                chatRepository.addMessage(
                    ChatMessageEntity(
                        mode = modeStorage,
                        role = "MODEL",
                        content = if (partial.isNotBlank()) partial else "⚠️ " + describeFailure(e),
                        sources = if (partial.isBlank()) emptyList() else sources
                    )
                )
                _streamingMessage.value = null
            }
        }
    }

    private fun describeFailure(e: Throwable): String = when (e) {
        is NanoException.ContextExceeded -> "Your message is too long for the on-device model."
        is NanoException.Unavailable ->
            "Gemini Nano is unavailable on this device (${e.reason}). Download it in Settings."
        is NanoException.BadInput -> "The on-device model rejected the request (${e.reason})."
        is NanoException.EmptyResponse -> "The on-device model returned no response."
        is NanoException.Generation -> "Gemini Nano failed: ${e.cause?.message ?: e.message}"
        else -> "On-device AI unavailable: ${e.message ?: "unknown error"}"
    }

    /**
     * Clear the active mode's history. Cancels any in-flight generation first so a clear
     * pressed mid-answer can't re-persist a model turn afterwards. The other mode's history
     * is untouched (Room delete is keyed by mode).
     */
    fun resetChat() {
        val modeStorage = _currentMode.value.storage()
        val job = generationJob
        generationJob = null
        loadingStage = null
        _streamingMessage.value = null
        viewModelScope.launch {
            job?.cancelAndJoin()
            chatRepository.clear(modeStorage)
        }
    }
}
