package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AiModel(val id: String, val displayName: String) {
    GEMINI_FLASH("gemini-2.5-flash-lite", "Gemini 2.5 Flash Lite"),
    GEMINI_PRO("gemini-2.5-pro", "Gemini 2.5 Pro");

    companion object {
        fun fromId(id: String): AiModel = entries.find { it.id == id } ?: GEMINI_FLASH
    }
}

enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userPrefs: UserPreferencesRepository,
    private val secretManager: SecretManager
) : ViewModel() {

    data class SettingsUiState(
        val themeMode: ThemeMode = ThemeMode.SYSTEM,
        val currentAiModel: AiModel = AiModel.GEMINI_FLASH,
        val repoOwner: String? = null,
        val repoName: String? = null,
        val appVersion: String = "1.0.0",
        val isBiometricEnabled: Boolean = true
    )

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadRepoInfo()

        userPrefs.themeMode
            .onEach { modeStr ->
                _uiState.value = _uiState.value.copy(
                    themeMode = try { ThemeMode.valueOf(modeStr) } catch(e: Exception) { ThemeMode.SYSTEM }
                )
            }
            .launchIn(viewModelScope)

        userPrefs.aiModel
            .onEach { modelId ->
                _uiState.value = _uiState.value.copy(
                    currentAiModel = AiModel.fromId(modelId)
                )
            }
            .launchIn(viewModelScope)
            
        userPrefs.isBiometricEnabled
            .onEach { enabled ->
                _uiState.value = _uiState.value.copy(
                    isBiometricEnabled = enabled
                )
            }
            .launchIn(viewModelScope)
    }

    private fun loadRepoInfo() {
        val (owner, repo) = secretManager.getRepoInfo()
        _uiState.value = _uiState.value.copy(
            repoOwner = owner,
            repoName = repo
        )
    }

    fun updateTheme(mode: ThemeMode) {
        viewModelScope.launch {
            userPrefs.setThemeMode(mode.name)
        }
    }

    fun updateAiModel(model: AiModel) {
        viewModelScope.launch {
            userPrefs.setAiModel(model.id)
        }
    }
    
    fun updateBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPrefs.setBiometricEnabled(enabled)
        }
    }

    fun logout() {
        secretManager.clear()
        // State update? The UI will likely navigate away or show login screen via MainViewModel monitoring secretManager, 
        // or we just update local state to show disconnected.
        loadRepoInfo() // Will clear values
    }
}
