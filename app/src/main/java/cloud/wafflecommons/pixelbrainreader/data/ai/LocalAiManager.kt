package cloud.wafflecommons.pixelbrainreader.data.ai

import android.content.Context
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.Generation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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
 * We do NOT call `com.google.ai.edge.aicore` directly because that SDK requires
 * per-app allowlisting in Google's Early Access Program — third-party apps get
 * `NOT_AVAILABLE / "Required LLM feature not found"`. ML Kit GenAI wraps the
 * same AICore + Nano model behind a higher-level API that is open to all apps.
 *
 * STRICT CONTRACT — DO NOT BREAK:
 *  1. This class NEVER falls back to cloud inference.
 *  2. All failures are surfaced as [Result.failure] with a typed [NanoException] subclass.
 *  3. The presentation layer is responsible for obtaining explicit user consent before
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
        ioScope.launch { refreshAvailability() }
    }

    /** Re-probe ML Kit GenAI availability. Safe to call from lifecycle observers (e.g. ON_RESUME). */
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
     * Run on-device inference.
     *
     * @return [Result.success] with the model's text, or [Result.failure] holding a
     *   [NanoException]. **Never** reaches the network.
     */
    suspend fun generateResponse(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        if (prompt.isBlank()) {
            return@withContext Result.failure(NanoException.BadInput("Empty prompt"))
        }

        if (_nanoState.value !is NanoState.Ready) {
            refreshAvailability()
            if (_nanoState.value !is NanoState.Ready) {
                val reason = when (val s = _nanoState.value) {
                    is NanoState.Unavailable -> s.reason
                    is NanoState.Error -> s.cause.localizedMessage ?: s.cause.message ?: "error"
                    is NanoState.Downloading -> "model is downloading"
                    else -> "Gemini Nano is not ready on this device"
                }
                return@withContext Result.failure(NanoException.Unavailable(reason))
            }
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
            } catch (e: Throwable) {
                Log.w(tag, "Unexpected inference error", e)
                Result.failure(NanoException.Generation(e))
            }
        }
    }

    private companion object {
        const val WARMUP_TIMEOUT_MS = 300_000L
        const val INFERENCE_TIMEOUT_MS = 900_000L
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
     * Map an ML Kit feature status to our [NanoState]. If the feature is
     * downloadable we kick off a background collection on [download()] so the
     * UI can show progress instead of a stale "Unavailable".
     */
    private suspend fun applyStatus(status: Int) {
        when (status) {
            FeatureStatus.AVAILABLE -> markReadyAfterWarmup()
            FeatureStatus.DOWNLOADING,
            FeatureStatus.DOWNLOADABLE -> startDownload()
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
     * Warm up the inference engine before declaring the model Ready.
     *
     * Without this, the *first* call to [GenerativeModel.generateContent] hangs
     * indefinitely while ML Kit lazily binds to the AICore service and loads
     * the LLM weights on a Pixel device. `warmup()` performs that handshake
     * up-front so subsequent inference returns in normal latency.
     *
     * Bounded with [WARMUP_TIMEOUT_MS] so a wedged AICore bind does not leave
     * the UI stuck on "Checking…". If warmup times out we still flip to Ready
     * optimistically — ML Kit will join any in-progress warmup on the first
     * inference call, and [generateResponse] has its own timeout so the user
     * can't be hung forever there either.
     */
    private suspend fun markReadyAfterWarmup() {
        Log.i(tag, "ML Kit GenAI status=AVAILABLE — running warmup() with ${WARMUP_TIMEOUT_MS / 1000}s budget…")
        val start = System.currentTimeMillis()
        try {
            val completed = withTimeoutOrNull(WARMUP_TIMEOUT_MS) {
                ensureModel().warmup()
                true
            }
            val elapsed = System.currentTimeMillis() - start
            if (completed == null) {
                Log.w(tag, "warmup() did not complete in ${elapsed}ms — flipping to Ready optimistically; first inference will join any in-progress warmup")
            } else {
                Log.i(tag, "warmup OK in ${elapsed}ms — Nano is Ready")
            }
            _nanoState.value = NanoState.Ready
        } catch (e: GenAiException) {
            logGenAiException("warmup", e)
            _nanoState.value = classifyAvailability(e)
        } catch (e: Throwable) {
            Log.e(tag, "Unexpected warmup error", e)
            _nanoState.value = NanoState.Error(e)
        }
    }

    /**
     * Subscribe to [GenerativeModel.download] and translate its [DownloadStatus]
     * events to [NanoState]. We guard with a single [downloadJob] so repeated
     * `ON_RESUME` probes don't spawn multiple collectors.
     */
    private fun startDownload() {
        if (downloadJob?.isActive == true) return
        _nanoState.value = NanoState.Downloading(progress = 0L)
        downloadJob = ioScope.launch {
            try {
                ensureModel().download().collect { event ->
                    when (event) {
                        is DownloadStatus.DownloadStarted ->
                            _nanoState.value = NanoState.Downloading(
                                progress = 0L,
                                totalBytes = event.bytesToDownload
                            )
                        is DownloadStatus.DownloadProgress -> {
                            val current = _nanoState.value
                            if (current is NanoState.Downloading) {
                                _nanoState.value = current.copy(progress = event.totalBytesDownloaded)
                            }
                        }
                        is DownloadStatus.DownloadCompleted -> {
                            Log.i(tag, "ML Kit GenAI download completed — warming up")
                            markReadyAfterWarmup()
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
    data object Unknown : NanoState()
    data object Checking : NanoState()
    data class Downloading(val progress: Long, val totalBytes: Long = -1L) : NanoState()
    data object Ready : NanoState()
    data class Unavailable(val reason: String) : NanoState()
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
