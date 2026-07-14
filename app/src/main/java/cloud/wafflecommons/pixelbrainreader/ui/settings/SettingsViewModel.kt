package cloud.wafflecommons.pixelbrainreader.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cloud.wafflecommons.pixelbrainreader.data.workers.IndexingWorker
import cloud.wafflecommons.pixelbrainreader.data.ai.LocalAiManager
import cloud.wafflecommons.pixelbrainreader.data.ai.NanoState
import cloud.wafflecommons.pixelbrainreader.data.auth.GoogleAuthRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.AppThemeConfig
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import cloud.wafflecommons.pixelbrainreader.data.local.preferences.GamificationPreferences
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPrefs: UserPreferencesRepository,
    private val secretManager: SecretManager,
    private val vectorSearchEngine: cloud.wafflecommons.pixelbrainreader.data.ai.VectorSearchEngine,
    private val healthConnectManager: cloud.wafflecommons.pixelbrainreader.data.health.HealthConnectManager,
    private val syncHealthDataUseCase: cloud.wafflecommons.pixelbrainreader.data.usecase.SyncHealthDataUseCase,
    private val habitRepository: cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository,
    private val gamificationPrefs: GamificationPreferences,
    val googleAuthManager: GoogleAuthRepository,
    private val localAiManager: LocalAiManager
) : ViewModel() {

    sealed class GoogleAuthEvent {
        data class ConsentRequired(val intentSender: IntentSender) : GoogleAuthEvent()
        data class Failed(val message: String) : GoogleAuthEvent()
        object Linked : GoogleAuthEvent()
    }

    /** One-shot UI events surfaced by the on-device model lifecycle. */
    sealed class NanoModelEvent {
        /**
         * The user tried to select Local AI while the model was not yet [NanoState.Ready].
         * The selection was rejected; the UI should show the [reason] and keep the
         * previously persisted [AiModel] active.
         */
        data class MustDownloadFirst(val reason: String) : NanoModelEvent()
    }


    data class SettingsUiState(
        val paneWidth: Float = 360f,
        val themeConfig: AppThemeConfig = AppThemeConfig.FOLLOW_SYSTEM,
        val currentAiModel: cloud.wafflecommons.pixelbrainreader.data.model.AiModel = cloud.wafflecommons.pixelbrainreader.data.model.AiModel.CORTEX_LOCAL,
        val appVersion: String = "7.0.0",
        val repoOwner: String? = null,
        val repoName: String? = null,
        // AI Config (Advanced/Internal)
        val embeddingModel: String = "universal_sentence_encoder.tflite",
        val availableEmbeddingModels: List<String> = emptyList(),
        val llmModelName: String = "gemini-2.5-flash-lite",
        // Health Connect
        val healthConnectStatus: Int = 0, // 0=Unknown, 1=Available, 2=NotInstalled, 3=NoPermissions, 4=Connected
        val healthConnectPermissionsGranted: Boolean = false,
        // Google Sync
        val isGoogleSyncEnabled: Boolean = false,
        val isGoogleAccountLinked: Boolean = false
    )

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** Mirrors [LocalAiManager.nanoState] for the Settings UI. */
    val nanoState: StateFlow<NanoState> = localAiManager.nanoState

    private val _nanoModelEvents = MutableSharedFlow<NanoModelEvent>(extraBufferCapacity = 4)
    val nanoModelEvents = _nanoModelEvents.asSharedFlow()

    /**
     * Live state of the manual RAG indexing job.
     *
     * Reflects WorkManager's view of [IndexingWorker.UNIQUE_WORK_NAME] across
     * process death: if the user backgrounds the app while indexing is
     * running and comes back, we re-attach to the same WorkInfo flow and
     * keep showing the running state until the worker actually finishes.
     */
    sealed class IndexingState {
        data object Idle : IndexingState()
        data object Enqueued : IndexingState()
        data object Running : IndexingState()
        data object Succeeded : IndexingState()
        data class Failed(val reason: String) : IndexingState()
    }

    private val _indexingState = MutableStateFlow<IndexingState>(IndexingState.Idle)
    val indexingState: StateFlow<IndexingState> = _indexingState.asStateFlow()

    init {
        // Re-attach to any in-flight indexing job (e.g. after process death
        // while a previous user-triggered indexing was still running).
        observeIndexingWork()
    }

    private fun observeIndexingWork() {
        viewModelScope.launch {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(IndexingWorker.UNIQUE_WORK_NAME)
                .collect { infos ->
                    val latest = infos.firstOrNull()
                    _indexingState.value = when (latest?.state) {
                        WorkInfo.State.ENQUEUED -> IndexingState.Enqueued
                        WorkInfo.State.RUNNING -> IndexingState.Running
                        WorkInfo.State.SUCCEEDED -> IndexingState.Succeeded
                        WorkInfo.State.FAILED -> IndexingState.Failed(
                            latest.outputData.getString("error") ?: "Indexing failed"
                        )
                        WorkInfo.State.CANCELLED -> IndexingState.Failed("Indexing cancelled")
                        WorkInfo.State.BLOCKED, null -> IndexingState.Idle
                    }
                }
        }
    }

    /**
     * User-triggered manual delta indexing. Enqueued as unique work so
     * mashing the button doesn't pile up duplicate runs — KEEP policy
     * means a request issued while one is already running is a no-op.
     */
    fun triggerVaultIndexing() {
        // Manual, user-initiated indexing: gate on battery only. Requiring
        // device-idle here would defer the job until the phone enters Doze, so
        // tapping "Index Knowledge Vault" while actively using the device would
        // appear to do nothing. (The periodic DailyExportWorker keeps deviceIdle.)
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val request = OneTimeWorkRequestBuilder<IndexingWorker>()
            .addTag("manual_indexing")
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IndexingWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    /** Acknowledge a terminal state (success/failure) so the UI returns to Idle. */
    fun dismissIndexingState() {
        val current = _indexingState.value
        if (current is IndexingState.Succeeded || current is IndexingState.Failed) {
            _indexingState.value = IndexingState.Idle
        }
    }

    val moodEmojiMapping: StateFlow<Map<Int, String>> = gamificationPrefs.moodEmojiMappingFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = mapOf(1 to "😭", 2 to "😕", 3 to "😐", 4 to "🙂", 5 to "🤩")
        )

    init {
        loadRepoInfo()
        scanAssetsForModels()
        checkHealthConnectStatus()

        
        userPrefs.listPaneWidth.onEach { width ->
            _uiState.value = _uiState.value.copy(paneWidth = width)
        }.launchIn(viewModelScope)

        userPrefs.themeConfig.onEach { theme ->
             _uiState.value = _uiState.value.copy(themeConfig = theme)
        }.launchIn(viewModelScope)
        
        userPrefs.selectedAiModel.onEach { model ->
             _uiState.value = _uiState.value.copy(currentAiModel = model)
        }.launchIn(viewModelScope)
        
        // Keep observing low-level config for internal use or advanced UI
        userPrefs.embeddingModel.onEach { model ->
             _uiState.value = _uiState.value.copy(embeddingModel = model)
        }.launchIn(viewModelScope)
        
        userPrefs.llmModelName.onEach { name ->
             _uiState.value = _uiState.value.copy(llmModelName = name)
        }.launchIn(viewModelScope)

        userPrefs.isGoogleSyncEnabled.onEach { enabled ->
            _uiState.value = _uiState.value.copy(isGoogleSyncEnabled = enabled)
            googleAuthManager.setAccountLinked(enabled) // Sync internal state for now
        }.launchIn(viewModelScope)

        googleAuthManager.isAccountLinked.onEach { linked ->
            _uiState.value = _uiState.value.copy(isGoogleAccountLinked = linked)
        }.launchIn(viewModelScope)
    }

    private fun scanAssetsForModels() {
        try {
            val files = context.assets.list("")?.filter { it.endsWith(".tflite") } ?: emptyList()
            _uiState.value = _uiState.value.copy(availableEmbeddingModels = files)
        } catch (e: Exception) {
             _uiState.value = _uiState.value.copy(availableEmbeddingModels = listOf("text_embedder.tflite"))
        }
    }
    
    fun updateTheme(config: AppThemeConfig) {
        viewModelScope.launch {
            userPrefs.setThemeConfig(config)
        }
    }

    fun updateAiModel(model: cloud.wafflecommons.pixelbrainreader.data.model.AiModel) {
        // Gate Local AI behind a downloaded + ready on-device model. The Settings
        // screen owns the download lifecycle — see [onDownloadNanoModel]. If the
        // user picks CORTEX_LOCAL while the model isn't Ready, we reject the
        // selection and emit a one-shot event so the UI can show a snackbar. The
        // persisted preference is untouched, so the radio stays on the previous
        // (cloud) choice.
        if (model == cloud.wafflecommons.pixelbrainreader.data.model.AiModel.CORTEX_LOCAL) {
            val state = localAiManager.nanoState.value
            if (state !is NanoState.Ready) {
                viewModelScope.launch {
                    _nanoModelEvents.emit(
                        NanoModelEvent.MustDownloadFirst(describeNotReady(state))
                    )
                }
                return
            }
        }
        viewModelScope.launch {
            userPrefs.setAiModel(model)
        }
    }

    /** Explicit user-initiated download of Gemini Nano. */
    fun onDownloadNanoModel() {
        localAiManager.downloadModel()
    }

    /** Deep-link to AICore system settings so the user can clear the model from disk. */
    fun onOpenNanoModelSettings() {
        localAiManager.openAicoreSettings()
    }

    private fun describeNotReady(state: NanoState): String = when (state) {
        NanoState.NotDownloaded ->
            "Download Gemini Nano first to use Local AI."
        is NanoState.Downloading -> {
            val pct = if (state.progress in 0f..1f) " (${(state.progress * 100).toInt()}%)" else ""
            "Gemini Nano is still downloading$pct. Pick Local AI once it's ready."
        }
        NanoState.Checking, NanoState.Unknown ->
            "Still checking on-device AI availability — try again in a moment."
        is NanoState.Unavailable ->
            "Local AI isn't available on this device: ${state.reason}"
        is NanoState.Error ->
            "Local AI hit an error: ${state.cause.localizedMessage ?: state.cause.message ?: "unknown"}"
        NanoState.Ready -> "Local AI is ready." // unreachable
    }

    // Advanced Local Config setters
    fun updateEmbeddingModel(filename: String) {
        viewModelScope.launch {
            userPrefs.setEmbeddingModel(filename)
        }
    }

    fun updateLlmModelName(name: String) {
        viewModelScope.launch {
            userPrefs.setLlmModelName(name)
        }
    }

    fun logout() {
        secretManager.clear()
        loadRepoInfo()
    }

    private fun loadRepoInfo() {
        val (owner, repo) = secretManager.getRepoInfo()
        _uiState.value = _uiState.value.copy(
            repoOwner = owner,
            repoName = repo
        )
    }

    fun checkHealthConnectStatus() {
        viewModelScope.launch {
            val sdkStatus = healthConnectManager.getSdkStatus()
            val hasPermissions = healthConnectManager.checkPermissions()
            
            Log.d("HealthConnect", "ViewModel Check -> SDK Status: $sdkStatus, Permissions: $hasPermissions")
            
            _uiState.value = _uiState.value.copy(
                healthConnectStatus = sdkStatus,
                healthConnectPermissionsGranted = hasPermissions
            )
        }
    }
    
    fun getHealthPermissions() = healthConnectManager.getRequiredPermissions()
    
    fun syncHealthData() {
        viewModelScope.launch {
             syncHealthDataUseCase()
        }
    }

    fun forceSyncHabits(onComplete: () -> Unit) {
        viewModelScope.launch {
            habitRepository.importConfigFromJson()
            onComplete()
        }
    }

    fun setGoogleSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPrefs.setGoogleSyncEnabled(enabled)
            if (!enabled) {
                googleAuthManager.signOut()
            }
        }
    }

    // V6: Credential Manager + AuthorizationClient flow.
    // Activity-scoped operations are surfaced as events; the UI launches them
    // and feeds the result back via onConsentResolved.
    private val _googleAuthEvents = MutableSharedFlow<GoogleAuthEvent>(extraBufferCapacity = 1)
    val googleAuthEvents = _googleAuthEvents.asSharedFlow()

    fun connectGoogle(activity: Activity) {
        viewModelScope.launch {
            // Credential Manager identity is best-effort. When it refuses
            // (TYPE_NO_CREDENTIAL on devices where OAuth consent screen / SHA-1
            // aren't fully wired), we fall through to AuthorizationClient —
            // it uses a different GMS code path and brings its own account
            // picker via the consent intent. The email gets captured from
            // AuthorizationResult.toGoogleSignInAccount() in either path.
            val signIn = googleAuthManager.signIn(activity)
            if (signIn.isFailure) {
                Log.w(
                    "SettingsViewModel",
                    "Credential Manager sign-in failed; falling through to AuthorizationClient",
                    signIn.exceptionOrNull()
                )
            }
            val outcome = googleAuthManager.authorize().getOrNull()
            when (outcome) {
                is GoogleAuthRepository.AuthorizationOutcome.Authorized -> {
                    userPrefs.setGoogleSyncEnabled(true)
                    _googleAuthEvents.emit(GoogleAuthEvent.Linked)
                }
                is GoogleAuthRepository.AuthorizationOutcome.NeedsUserConsent ->
                    _googleAuthEvents.emit(GoogleAuthEvent.ConsentRequired(outcome.intentSender))
                null -> {
                    // Both Credential Manager AND AuthorizationClient failed —
                    // surface the more specific Credential Manager error if we have one.
                    val msg = signIn.exceptionOrNull()?.message
                        ?: "Google connection failed; check Cloud Console config"
                    _googleAuthEvents.emit(GoogleAuthEvent.Failed(msg))
                }
            }
        }
    }

    fun onConsentResolved(data: Intent?) {
        viewModelScope.launch {
            val res = googleAuthManager.completeAuthorization(data)
            if (res.isSuccess) {
                userPrefs.setGoogleSyncEnabled(true)
                _googleAuthEvents.emit(GoogleAuthEvent.Linked)
            } else {
                _googleAuthEvents.emit(
                    GoogleAuthEvent.Failed(res.exceptionOrNull()?.message ?: "Consent failed")
                )
            }
        }
    }

    private val _isSyncingConfigs = MutableStateFlow(false)
    val isSyncingConfigs: StateFlow<Boolean> = _isSyncingConfigs.asStateFlow()

    fun syncAllConfigsToVault(onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            _isSyncingConfigs.value = true
            try {
                habitRepository.performBulkConfigSync()
                onComplete(true)
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Failed to sync configs", e)
                onComplete(false)
            } finally {
                _isSyncingConfigs.value = false
            }
        }
    }

    fun updateMoodEmoji(score: Int, emoji: String) {
        if (emoji.isBlank()) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val currentMap = moodEmojiMapping.value.toMutableMap()
            // Just take the first character/grapheme cluster if possible, but for simplicity assuming a single emoji string
            currentMap[score] = emoji.trim()
            gamificationPrefs.setMoodEmojiMapping(currentMap)
        }
    }
}
