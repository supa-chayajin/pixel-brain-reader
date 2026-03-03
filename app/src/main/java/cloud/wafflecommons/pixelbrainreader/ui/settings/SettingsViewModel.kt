package cloud.wafflecommons.pixelbrainreader.ui.settings

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    private val geminiRagManager: cloud.wafflecommons.pixelbrainreader.data.ai.GeminiRagManager,
    private val healthConnectManager: cloud.wafflecommons.pixelbrainreader.data.health.HealthConnectManager,
    private val syncHealthDataUseCase: cloud.wafflecommons.pixelbrainreader.data.usecase.SyncHealthDataUseCase,
    private val habitRepository: cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository,
    private val gamificationPrefs: GamificationPreferences
) : ViewModel() {

    data class SettingsUiState(
        val paneWidth: Float = 360f,
        val themeConfig: AppThemeConfig = AppThemeConfig.FOLLOW_SYSTEM,
        val currentAiModel: cloud.wafflecommons.pixelbrainreader.data.model.AiModel = cloud.wafflecommons.pixelbrainreader.data.model.AiModel.GEMINI_FLASH,
        val appVersion: String = "4.5.0",
        val repoOwner: String? = null,
        val repoName: String? = null,
        // AI Config (Advanced/Internal)
        val embeddingModel: String = "universal_sentence_encoder.tflite",
        val availableEmbeddingModels: List<String> = emptyList(),
        val llmModelName: String = "gemini-2.5-flash-lite",
        // Health Connect
        val healthConnectStatus: Int = 0, // 0=Unknown, 1=Available, 2=NotInstalled, 3=NoPermissions, 4=Connected
        val healthConnectPermissionsGranted: Boolean = false
    )

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

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
        viewModelScope.launch {
            userPrefs.setAiModel(model)
        }
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
