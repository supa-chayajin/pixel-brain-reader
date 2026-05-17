package cloud.wafflecommons.pixelbrainreader.data.ai

import android.content.Context
import android.util.Log
import com.google.ai.edge.aicore.DownloadCallback
import com.google.ai.edge.aicore.DownloadConfig
import com.google.ai.edge.aicore.GenerativeAIException
import com.google.ai.edge.aicore.GenerativeModel
import com.google.ai.edge.aicore.generationConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Privacy-first manager for on-device Gemini Nano inference via Google AI Edge AICore.
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

    init {
        ioScope.launch { refreshAvailability() }
    }

    /** Re-probe AICore availability. Safe to call from lifecycle observers (e.g. ON_RESUME). */
    suspend fun refreshAvailability() = withContext(Dispatchers.IO) {
        // A download in progress drives state via the DownloadCallback — don't override it.
        if (_nanoState.value is NanoState.Downloading) return@withContext
        _nanoState.value = NanoState.Checking
        try {
            ensureModel().prepareInferenceEngine()
            _nanoState.value = NanoState.Ready
        } catch (e: GenerativeAIException) {
            _nanoState.value = classifyAvailability(e)
        } catch (e: Throwable) {
            Log.w(tag, "Unexpected error probing AICore", e)
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
                val response = ensureModel().generateContent(prompt)
                val text = response.text
                if (text.isNullOrBlank()) {
                    Result.failure(NanoException.EmptyResponse)
                } else {
                    Result.success(text)
                }
            } catch (e: GenerativeAIException) {
                Result.failure(mapAicoreException(e))
            } catch (e: Throwable) {
                Log.w(tag, "Unexpected inference error", e)
                Result.failure(NanoException.Generation(e))
            }
        }
    }

    private fun ensureModel(): GenerativeModel {
        model?.let { return it }
        synchronized(this) {
            model?.let { return it }
            val config = generationConfig {
                context = appContext
                temperature = 0.2f
                topK = 16
                maxOutputTokens = 512
            }
            val downloadConfig = DownloadConfig(object : DownloadCallback {
                override fun onDownloadStarted(bytesToDownload: Long) {
                    _nanoState.value = NanoState.Downloading(progress = 0, totalBytes = bytesToDownload)
                }
                override fun onDownloadProgress(totalBytesDownloaded: Long) {
                    val current = _nanoState.value
                    if (current is NanoState.Downloading) {
                        _nanoState.value = current.copy(progress = totalBytesDownloaded)
                    }
                }
                override fun onDownloadCompleted() {
                    _nanoState.value = NanoState.Ready
                }
                override fun onDownloadFailed(failureStatus: String, e: GenerativeAIException) {
                    _nanoState.value = classifyAvailability(e)
                }
                override fun onDownloadDidNotStart(e: GenerativeAIException) {
                    _nanoState.value = classifyAvailability(e)
                }
            })
            val newModel = GenerativeModel(config, downloadConfig)
            model = newModel
            return newModel
        }
    }

    private fun classifyAvailability(e: GenerativeAIException): NanoState {
        return when (e.errorCode) {
            GenerativeAIException.ErrorCode.NOT_AVAILABLE,
            GenerativeAIException.ErrorCode.NEEDS_SYSTEM_UPDATE,
            GenerativeAIException.ErrorCode.NOT_ENOUGH_DISK_SPACE ->
                NanoState.Unavailable(reason = describe(e))
            else -> NanoState.Error(e)
        }
    }

    private fun mapAicoreException(e: GenerativeAIException): NanoException {
        return when (e.errorCode) {
            GenerativeAIException.ErrorCode.REQUEST_TOO_LARGE ->
                NanoException.ContextExceeded(describe(e))
            GenerativeAIException.ErrorCode.NOT_AVAILABLE,
            GenerativeAIException.ErrorCode.NEEDS_SYSTEM_UPDATE,
            GenerativeAIException.ErrorCode.NOT_ENOUGH_DISK_SPACE ->
                NanoException.Unavailable(describe(e))
            else -> NanoException.Generation(e)
        }
    }

    private fun describe(e: GenerativeAIException): String =
        e.localizedMessage ?: e.message ?: "Gemini Nano error (code ${e.errorCode})"
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
