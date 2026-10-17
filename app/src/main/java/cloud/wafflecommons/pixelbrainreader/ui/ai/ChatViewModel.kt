package cloud.wafflecommons.pixelbrainreader.ui.ai

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.ai.AiLanguage
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

// Retrieved notes injected into the prompt, in characters. Gemini Nano's WHOLE input window is
// ~4,000 tokens (persona + notes + history + question), so the notes get a fixed share of it.
private const val RAG_CONTEXT_BUDGET_CHARS = 3_000
private const val RAG_CONTEXT_SEPARATOR = "\n---\n"

/*
 * Personas. Answers stay SHORT on purpose: Nano is slow on the device, and a long, heavily
 * formatted reply keeps the user waiting on a spinner — or runs into STREAM_TIMEOUT_MS. Each one
 * ends with AiLanguage.SYSTEM_DIRECTIVE (the French-answer contract).
 */

/** Cortex (RAG over the vault): grounded — says the notes don't cover it rather than improvising. */
private const val CORTEX_PERSONA = """
Tu es Cortex, l'assistant du deuxième cerveau de l'utilisateur (ses notes Obsidian).

RÔLE : analytique, pragmatique et bienveillant. Valide brièvement, puis ramène les choses aux faits.

CONCISION (OBLIGATOIRE) : réponds directement, en un court paragraphe ou quelques puces — 120 mots maximum, sauf si l'utilisateur demande explicitement plus de détails.

RÉFÉRENCES :
- Pour toute question sur ses notes, sa vie ou ses projets, appuie-toi UNIQUEMENT sur les INFORMATIONS DE RÉFÉRENCE. Tu peux citer une note par son titre entre [[crochets doubles]].
- Si elles sont absentes ou ne contiennent pas la réponse, dis simplement que ses notes ne couvrent pas ce sujet. N'invente rien.
- Pour une simple conversation, réponds naturellement.

${AiLanguage.SYSTEM_DIRECTIVE}
"""

/** Spark (open chat): creative, and honest that it can't see the notes in this mode. */
private const val SPARK_PERSONA = """
Tu es Spark, le partenaire créatif de l'utilisateur : brainstorming, idées, reformulations, plans.

STYLE : vivant, concret et concis — quelques puces ou un court paragraphe, 150 mots maximum sauf demande explicite.

Tu n'as pas accès à ses notes dans ce mode : ne prétends pas les connaître.

${AiLanguage.SYSTEM_DIRECTIVE}
"""

/** Boardroom: three short expert takes on a technical dilemma, then a decision. */
private const val BOARDROOM_PERSONA = """
Tu es le Boardroom de Cortex : trois experts débattent d'un dilemme technique de l'utilisateur, puis tranchent.
- Alexandre, architecte DDD : frontières du domaine, couplage, abstractions.
- Bastien, DevSecOps : sécurité, secrets, fiabilité, modes de défaillance.
- Camille, tech lead pragmatique : rapport effort/valeur, YAGNI, livraison.

FORMAT OBLIGATOIRE, 200 mots maximum au total :
### 🏛️ Alexandre
Deux phrases.
### 🛡️ Bastien
Deux phrases.
### ⚡ Camille
Deux phrases.
### 🎯 Décision
Deux ou trois phrases, avec la prochaine étape concrète.

Appuie-toi sur les INFORMATIONS DE RÉFÉRENCE quand elles sont présentes ; n'invente pas le contenu de ses notes.

${AiLanguage.SYSTEM_DIRECTIVE}
"""

internal fun personaFor(mode: ChatMode): String = when (mode) {
    ChatMode.ORACLE -> CORTEX_PERSONA
    ChatMode.SCRIBE -> SPARK_PERSONA
    ChatMode.BOARDROOM -> BOARDROOM_PERSONA
}

/**
 * Joins the retrieved chunks (each already starts with its "[Note: title (folder) > heading]"
 * header) until [budget] characters, best first. Returns the context and the hits it actually
 * used — the source chips must list only what the model saw.
 */
internal fun buildRagContext(
    hits: List<cloud.wafflecommons.pixelbrainreader.data.ai.SearchHit>,
    budget: Int = RAG_CONTEXT_BUDGET_CHARS
): Pair<String?, List<cloud.wafflecommons.pixelbrainreader.data.ai.SearchHit>> {
    val parts = mutableListOf<String>()
    val used = mutableListOf<cloud.wafflecommons.pixelbrainreader.data.ai.SearchHit>()
    var length = 0
    for (hit in hits) {
        val text = hit.content.trim()
        if (text.isEmpty()) continue
        val cost = text.length + if (parts.isEmpty()) 0 else RAG_CONTEXT_SEPARATOR.length
        if (length + cost > budget) {
            // Never send nothing just because the best chunk alone is over budget.
            if (parts.isEmpty()) {
                parts += text.take(budget)
                used += hit
            }
            break
        }
        parts += text
        used += hit
        length += cost
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(RAG_CONTEXT_SEPARATOR) to used
}

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
                loadingStage = if (mode == ChatMode.BOARDROOM) "🏛️ Gathering the Boardroom…" else "🔎 Searching your Second Brain…"
                val hits = vectorSearchEngine.search(query, limit = RAG_TOP_K)
                val (context, usedHits) = buildRagContext(hits)
                ragContext = context
                sources = usedHits.map { it.fileId }.distinct()
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
            val persona = personaFor(mode)
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
