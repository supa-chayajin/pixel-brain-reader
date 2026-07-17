package cloud.wafflecommons.pixelbrainreader.data.ai

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.Generation
import cloud.wafflecommons.pixelbrainreader.data.local.entity.ChatMessageEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Privacy-first manager for on-device Gemini Nano inference via the
 * **ML Kit GenAI Prompt** API (`com.google.mlkit:genai-prompt`).
 *
 * STRICT CONTRACT — DO NOT BREAK:
 *  1. [generateResponse] NEVER triggers a download. If the model is not [NanoState.Ready]
 *     the call fast-fails with [NanoException.Unavailable]. The Settings screen is the
 *     sole orchestrator of the download lifecycle (see [downloadModel]).
 *  2. This class NEVER falls back to cloud inference.
 *  3. All failures are surfaced as [Result.failure] with a typed [NanoException].
 *  4. The presentation layer is responsible for obtaining explicit user consent before
 *     escalating to any cloud provider.
 */
@Singleton
class LocalAiManager @Inject constructor(
    @ApplicationContext private val appContext: Context
) {
    private val tag = "LocalAiManager"

    private val _nanoState = MutableStateFlow<NanoState>(NanoState.Unknown)
    val nanoState: StateFlow<NanoState> = _nanoState.asStateFlow()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionMutex = Mutex()

    @Volatile
    private var model: GenerativeModel? = null

    @Volatile
    private var downloadJob: Job? = null

    init {
        // Passive probe ONLY. Never starts a download from here — that requires
        // explicit user intent through [downloadModel].
        ioScope.launch { refreshAvailability() }
    }

    /**
     * Probe ML Kit GenAI status and reflect it in [nanoState]. Idempotent and safe to
     * call from lifecycle observers (e.g. ON_RESUME). Will NOT initiate a download.
     *
     * If the system reports a download is already in progress (resumed from a prior
     * session), we attach to its progress flow so the UI can show it — but we never
     * start a new download here.
     */
    suspend fun refreshAvailability() = withContext(Dispatchers.IO) {
        if (_nanoState.value is NanoState.Downloading) return@withContext
        _nanoState.value = NanoState.Checking
        Log.i(tag, "Probing ML Kit GenAI status (package=${appContext.packageName})…")
        try {
            val status = ensureModel().checkStatus()
            Log.i(tag, "ML Kit GenAI checkStatus → ${describeStatus(status)}")
            applyStatus(status)
        } catch (e: GenAiException) {
            logGenAiException("checkStatus", e)
            _nanoState.value = classifyAvailability(e)
        } catch (e: Throwable) {
            Log.e(tag, "Unexpected non-GenAiException probing ML Kit GenAI", e)
            _nanoState.value = NanoState.Error(e)
        }
    }

    /**
     * Explicit user-initiated download. Idempotent — no-op when already downloading,
     * already Ready, or device-Unavailable. Safe to call from the UI thread; the
     * actual work runs on [ioScope].
     */
    fun downloadModel() {
        ioScope.launch {
            when (val current = _nanoState.value) {
                is NanoState.Downloading -> {
                    Log.i(tag, "downloadModel() ignored — download already in progress")
                    return@launch
                }
                NanoState.Ready -> {
                    Log.i(tag, "downloadModel() ignored — model already Ready")
                    return@launch
                }
                is NanoState.Unavailable -> {
                    // Re-probe in case the user cleared disk or updated the system.
                    Log.w(tag, "downloadModel() called while Unavailable (${current.reason}) — re-probing")
                    refreshAvailability()
                    if (_nanoState.value !is NanoState.NotDownloaded && _nanoState.value !is NanoState.Ready) {
                        return@launch
                    }
                }
                else -> Unit
            }
            startDownload()
        }
    }

    /**
     * Deep-link to the AICore app's system settings so the user can clear the
     * Gemini Nano model from disk.
     *
     * ML Kit GenAI does NOT expose a public delete API — the on-disk model is owned
     * by Android AICore and can only be removed by the user from system Settings.
     * If the AICore package is not present on the device we fall back to the
     * top-level Settings screen.
     */
    fun openAicoreSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", AICORE_PACKAGE, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            appContext.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(tag, "AICore app details not found; falling back to global Settings", e)
            val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(fallback)
        }
    }

    /**
     * Run on-device inference. Fast-fails with [NanoException.Unavailable] when the
     * model is not [NanoState.Ready] — this method NEVER triggers a download or
     * status probe of its own.
     */
    suspend fun generateResponse(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        if (prompt.isBlank()) {
            return@withContext Result.failure(NanoException.BadInput("Empty prompt"))
        }

        // Strict contract: never trigger a download from the generation path.
        modelNotReadyReason()?.let {
            return@withContext Result.failure(NanoException.Unavailable(it))
        }

        sessionMutex.withLock {
            try {
                val start = System.currentTimeMillis()
                val response = withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
                    ensureModel().generateContent(prompt)
                }
                val elapsed = System.currentTimeMillis() - start
                if (response == null) {
                    Log.w(tag, "generateContent() timed out after ${elapsed}ms")
                    return@withLock Result.failure(
                        NanoException.Generation(
                            RuntimeException("Gemini Nano did not respond within ${INFERENCE_TIMEOUT_MS / 1000}s")
                        )
                    )
                }
                Log.i(tag, "generateContent OK in ${elapsed}ms")
                val text = response.candidates.firstOrNull()?.text
                if (text.isNullOrBlank()) {
                    Result.failure(NanoException.EmptyResponse)
                } else {
                    Result.success(text)
                }
            } catch (e: GenAiException) {
                logGenAiException("generateContent", e)
                Result.failure(mapGenAiException(e))
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Never swallow cancellation — let it propagate so a cancelled caller
                // (navigation-away, timeout) actually unwinds instead of being reported
                // as a generic generation failure.
                throw e
            } catch (e: Throwable) {
                Log.w(tag, "Unexpected inference error", e)
                Result.failure(NanoException.Generation(e))
            }
        }
    }

    /**
     * Memory-aware inference for chat surfaces.
     *
     * ML Kit's [GenerativeModel.generateContent] takes one string only — no session
     * object, no role-tagged turns. We hand-roll the prompt with explicit section
     * markers so Gemini Nano can distinguish system instructions, optional RAG
     * grounding, and the rolling conversation history.
     *
     * Contract:
     *  - The caller is responsible for trimming [chatHistory] to a safe sliding window
     *    (typical N = 6, i.e. 3 turns). Passing the full history will eventually trip
     *    [NanoException.ContextExceeded].
     *  - [chatHistory] is the *prior* conversation only — [currentQuery] is appended
     *    after it, terminated by a trailing `Model:` cue that anchors the response.
     *  - [ragContext] is optional. When provided, it's injected verbatim under a
     *    REFERENCE INFORMATION block; the [systemPrompt] should already instruct
     *    the model to stay grounded in that block.
     *  - All failure modes are the same as [generateResponse] — including
     *    [NanoException.Unavailable] when Nano isn't Ready.
     */
    suspend fun generateAugmentedResponse(
        systemPrompt: String,
        ragContext: String?,
        chatHistory: List<ChatMessageEntity>,
        currentQuery: String
    ): Result<String> {
        val prompt = buildAugmentedPrompt(systemPrompt, ragContext, chatHistory, currentQuery)
        return generateResponse(prompt)
    }

    /** Human-readable reason the on-device model isn't ready, or null when it IS Ready. */
    private fun modelNotReadyReason(): String? = when (val s = _nanoState.value) {
        is NanoState.Ready -> null
        NanoState.NotDownloaded -> "model not downloaded — open Settings to download Gemini Nano"
        is NanoState.Downloading -> {
            val pct = if (s.progress in 0f..1f) " (${(s.progress * 100).toInt()}%)" else ""
            "model is downloading$pct"
        }
        is NanoState.Unavailable -> s.reason
        is NanoState.Error -> s.cause.localizedMessage ?: s.cause.message ?: "error"
        NanoState.Checking -> "still checking on-device AI availability"
        NanoState.Unknown -> "Gemini Nano is not ready on this device"
    }

    /**
     * Streaming variant of [generateAugmentedResponse]. Emits response deltas as Gemini Nano
     * produces them (ML Kit `generateContentStream`), so the UI renders the answer incrementally
     * instead of blocking on a single one-shot call. Never triggers a download.
     */
    fun generateAugmentedResponseStream(
        systemPrompt: String,
        ragContext: String?,
        chatHistory: List<ChatMessageEntity>,
        currentQuery: String
    ): Flow<String> {
        val prompt = buildAugmentedPrompt(systemPrompt, ragContext, chatHistory, currentQuery)
        return generateResponseStream(prompt)
    }

    /**
     * On-device streaming inference. Emits text deltas from Gemini Nano; throws a typed
     * [NanoException] if the model isn't Ready or generation fails. Stream collection is
     * cancellable — a caller timeout/cancel actually aborts it.
     */
    fun generateResponseStream(prompt: String): Flow<String> = flow {
        if (prompt.isBlank()) throw NanoException.BadInput("Empty prompt")
        modelNotReadyReason()?.let { throw NanoException.Unavailable(it) }

        sessionMutex.withLock {
            val start = System.currentTimeMillis()
            var chunks = 0
            var chars = 0
            try {
                ensureModel().generateContentStream(prompt).collect { response ->
                    val text = response.candidates.firstOrNull()?.text
                    chunks++
                    if (chunks <= 5 || chunks % 25 == 0) {
                        Log.d(tag, "stream chunk #$chunks len=${text?.length ?: 0} at ${System.currentTimeMillis() - start}ms")
                    }
                    if (!text.isNullOrEmpty()) {
                        chars += text.length
                        emit(text)
                    }
                }
                Log.i(tag, "generateContentStream done: $chunks chunks, $chars chars in ${System.currentTimeMillis() - start}ms")
            } catch (e: GenAiException) {
                logGenAiException("generateContentStream", e)
                throw mapGenAiException(e)
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun buildAugmentedPrompt(
        systemPrompt: String,
        ragContext: String?,
        chatHistory: List<ChatMessageEntity>,
        currentQuery: String
    ): String = buildString {
        // Scaffolding labels are intentionally in French — the surrounding
        // structural language is a strong steer for Gemini Nano to keep the
        // generated response in French too, complementing the explicit
        // instruction inside [systemPrompt].
        append("[SYSTÈME]\n")
        append(systemPrompt.trim())
        append("\n\n")

        // Phase 3: omit the references block entirely when context is empty.
        // Showing an empty block confuses the model into refusing to chat.
        if (!ragContext.isNullOrBlank()) {
            append("[INFORMATIONS DE RÉFÉRENCE]\n")
            append("Utilise les extraits ci-dessous, issus des notes de l'utilisateur, ")
            append("pour répondre avec précision. Si la réponse n'y figure pas, dis-le clairement.\n\n")
            append(ragContext.trim())
            append("\n\n")
        }

        append("[HISTORIQUE DE CONVERSATION]\n")
        chatHistory.forEach { msg ->
            val label = if (msg.role == "USER") "Utilisateur" else "Assistant"
            append(label).append(": ").append(msg.content.trim()).append('\n')
        }
        append('\n')
        append("[QUESTION DE L'UTILISATEUR]\n")
        append("Utilisateur: ").append(currentQuery.trim()).append('\n')
        // Trailing cue — Nano completes after this label, which keeps the response
        // role-anchored even when chat history is empty.
        append("Assistant:")
    }

    private companion object {
        // Generous — the first inference after a fresh (re)download reloads the model into
        // memory and can take a while. Chat streaming uses its own tighter budget.
        const val INFERENCE_TIMEOUT_MS = 120_000L
        const val AICORE_PACKAGE = "com.google.android.aicore"
    }

    private fun ensureModel(): GenerativeModel {
        model?.let { return it }
        synchronized(this) {
            model?.let { return it }
            val newModel = Generation.getClient()
            model = newModel
            return newModel
        }
    }

    /**
     * Map an ML Kit feature status to our [NanoState].
     *
     * - [FeatureStatus.AVAILABLE] → [NanoState.Ready]. We deliberately do NOT call
     *   `GenerativeModel.warmup()`: on Pixel devices it has been observed to block
     *   indefinitely at the AICore service layer, and worse, to wedge subsequent
     *   `generateContent()` calls behind it. Skipping warmup means the first
     *   inference pays a one-time model-load cost (a few seconds), but it
     *   actually completes — which is the correctness contract we need.
     * - [FeatureStatus.DOWNLOADABLE] → [NanoState.NotDownloaded] and waits for
     *   explicit user opt-in via [downloadModel].
     * - [FeatureStatus.DOWNLOADING] → attach to the in-flight download flow so
     *   the UI can show progress (system-initiated, e.g. resumed from a prior
     *   session).
     */
    private fun applyStatus(status: Int) {
        when (status) {
            FeatureStatus.AVAILABLE -> {
                Log.i(tag, "ML Kit GenAI status=AVAILABLE — marking Ready (no warmup; first inference loads on demand)")
                _nanoState.value = NanoState.Ready
            }
            FeatureStatus.DOWNLOADING -> startDownload()
            FeatureStatus.DOWNLOADABLE ->
                _nanoState.value = NanoState.NotDownloaded
            FeatureStatus.UNAVAILABLE ->
                _nanoState.value = NanoState.Unavailable(
                    reason = "ML Kit GenAI reports UNAVAILABLE on this device"
                )
            else ->
                _nanoState.value = NanoState.Unavailable(
                    reason = "Unknown FeatureStatus=$status"
                )
        }
    }

    /**
     * Subscribe to [GenerativeModel.download] and translate [DownloadStatus] events
     * to [NanoState]. Guarded with a single [downloadJob] so repeated probes don't
     * spawn multiple collectors.
     */
    private fun startDownload() {
        if (downloadJob?.isActive == true) return
        _nanoState.value = NanoState.Downloading(progress = -1f)
        downloadJob = ioScope.launch {
            try {
                ensureModel().download().collect { event ->
                    when (event) {
                        is DownloadStatus.DownloadStarted ->
                            _nanoState.value = NanoState.Downloading(
                                progress = 0f,
                                bytesDownloaded = 0L,
                                totalBytes = event.bytesToDownload
                            )
                        is DownloadStatus.DownloadProgress -> {
                            val total = (_nanoState.value as? NanoState.Downloading)?.totalBytes ?: -1L
                            val downloaded = event.totalBytesDownloaded
                            val ratio = if (total > 0L) {
                                (downloaded.toFloat() / total).coerceIn(0f, 1f)
                            } else -1f
                            _nanoState.value = NanoState.Downloading(
                                progress = ratio,
                                bytesDownloaded = downloaded,
                                totalBytes = total
                            )
                        }
                        is DownloadStatus.DownloadCompleted -> {
                            Log.i(tag, "ML Kit GenAI download completed — marking Ready (no warmup)")
                            _nanoState.value = NanoState.Ready
                        }
                        is DownloadStatus.DownloadFailed -> {
                            logGenAiException("download", event.e)
                            _nanoState.value = classifyAvailability(event.e)
                        }
                    }
                }
            } catch (e: GenAiException) {
                logGenAiException("downloadStream", e)
                _nanoState.value = classifyAvailability(e)
            } catch (e: Throwable) {
                Log.e(tag, "Unexpected download stream error", e)
                _nanoState.value = NanoState.Error(e)
            }
        }
    }

    private fun classifyAvailability(e: GenAiException): NanoState {
        return when (e.errorCode) {
            GenAiException.ErrorCode.NOT_AVAILABLE,
            GenAiException.ErrorCode.AICORE_INCOMPATIBLE,
            GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE,
            GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE -> {
                Log.w(tag, "classify → Unavailable (errorCode=${describeErrorCode(e.errorCode)}): ${describe(e)}")
                NanoState.Unavailable(reason = describe(e))
            }
            else -> {
                Log.e(tag, "classify → Error (errorCode=${describeErrorCode(e.errorCode)}): ${describe(e)}")
                NanoState.Error(e)
            }
        }
    }

    private fun mapGenAiException(e: GenAiException): NanoException {
        return when (e.errorCode) {
            GenAiException.ErrorCode.REQUEST_TOO_LARGE ->
                NanoException.ContextExceeded(describe(e))
            GenAiException.ErrorCode.REQUEST_TOO_SMALL ->
                NanoException.BadInput(describe(e))
            GenAiException.ErrorCode.NOT_AVAILABLE,
            GenAiException.ErrorCode.AICORE_INCOMPATIBLE,
            GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE,
            GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE ->
                NanoException.Unavailable(describe(e))
            else -> NanoException.Generation(e)
        }
    }

    private fun describe(e: GenAiException): String =
        e.localizedMessage ?: e.message ?: "ML Kit GenAI error (code ${describeErrorCode(e.errorCode)})"

    private fun describeStatus(status: Int): String = when (status) {
        FeatureStatus.AVAILABLE -> "AVAILABLE"
        FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE"
        FeatureStatus.DOWNLOADING -> "DOWNLOADING"
        FeatureStatus.UNAVAILABLE -> "UNAVAILABLE"
        else -> "UNKNOWN($status)"
    }

    private fun describeErrorCode(code: Int): String = when (code) {
        GenAiException.ErrorCode.UNKNOWN -> "UNKNOWN"
        GenAiException.ErrorCode.REQUEST_PROCESSING_ERROR -> "REQUEST_PROCESSING_ERROR"
        GenAiException.ErrorCode.CANCELLED -> "CANCELLED"
        GenAiException.ErrorCode.NOT_AVAILABLE -> "NOT_AVAILABLE"
        GenAiException.ErrorCode.BUSY -> "BUSY"
        GenAiException.ErrorCode.RESPONSE_PROCESSING_ERROR -> "RESPONSE_PROCESSING_ERROR"
        GenAiException.ErrorCode.REQUEST_TOO_LARGE -> "REQUEST_TOO_LARGE"
        GenAiException.ErrorCode.REQUEST_TOO_SMALL -> "REQUEST_TOO_SMALL"
        GenAiException.ErrorCode.RESPONSE_GENERATION_ERROR -> "RESPONSE_GENERATION_ERROR"
        GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> "PER_APP_BATTERY_USE_QUOTA_EXCEEDED"
        GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> "BACKGROUND_USE_BLOCKED"
        GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE -> "NOT_ENOUGH_DISK_SPACE"
        GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE -> "NEEDS_SYSTEM_UPDATE"
        GenAiException.ErrorCode.AICORE_INCOMPATIBLE -> "AICORE_INCOMPATIBLE"
        GenAiException.ErrorCode.INVALID_INPUT_IMAGE -> "INVALID_INPUT_IMAGE"
        GenAiException.ErrorCode.CACHE_PROCESSING_ERROR -> "CACHE_PROCESSING_ERROR"
        else -> "code=$code"
    }

    private fun logGenAiException(stage: String, e: GenAiException) {
        Log.e(tag, "ML Kit GenAI failure @ $stage")
        Log.e(tag, "  errorCode  = ${describeErrorCode(e.errorCode)} (${e.errorCode})")
        Log.e(tag, "  message    = ${e.message}")
        Log.e(tag, "  localized  = ${e.localizedMessage}")
        Log.e(tag, "  cause      = ${e.cause}")
        Log.e(tag, "  exception class = ${e::class.java.name}")
        Log.e(tag, "ML Kit GenAI stack trace:", e)
    }
}

/** Lifecycle state of the on-device Gemini Nano model. */
sealed class NanoState {
    /** Initial state before the first probe completes. */
    data object Unknown : NanoState()

    /** A status probe is in flight. */
    data object Checking : NanoState()

    /** Model is downloadable but the user has not yet started the download. */
    data object NotDownloaded : NanoState()

    /**
     * A download is in progress.
     *
     * @param progress 0f..1f, or [-1f] when the total size is not yet known
     *                 (the UI should fall back to an indeterminate indicator).
     * @param bytesDownloaded Total bytes received so far.
     * @param totalBytes Expected total size, or -1L when unknown.
     */
    data class Downloading(
        val progress: Float,
        val bytesDownloaded: Long = 0L,
        val totalBytes: Long = -1L
    ) : NanoState()

    /** Model is on-device and warmed up — ready for inference. */
    data object Ready : NanoState()

    /** Device cannot run the model (no AICore, incompatible hardware, disk full, …). */
    data class Unavailable(val reason: String) : NanoState()

    /** Transient error during probe/download/warmup. */
    data class Error(val cause: Throwable) : NanoState()
}

/** Typed failure modes returned by [LocalAiManager.generateResponse]. */
sealed class NanoException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unavailable(val reason: String) : NanoException("Gemini Nano unavailable: $reason")
    class ContextExceeded(val reason: String) : NanoException("Gemini Nano context exceeded: $reason")
    class BadInput(val reason: String) : NanoException("Gemini Nano bad input: $reason")
    object EmptyResponse : NanoException("Gemini Nano returned an empty response")
    class Generation(cause: Throwable) :
        NanoException("Gemini Nano generation failed: ${cause.message}", cause)
}
