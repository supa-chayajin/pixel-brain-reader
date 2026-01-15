package cloud.wafflecommons.pixelbrainreader.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

enum class AppThemeConfig { FOLLOW_SYSTEM, LIGHT, DARK }

@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val PANE_WIDTH_KEY = floatPreferencesKey("list_pane_width")

    private val THEME_CONFIG_KEY = stringPreferencesKey("app_theme_config")

    val listPaneWidth: Flow<Float> = context.dataStore.data
        .map { preferences ->
            preferences[PANE_WIDTH_KEY] ?: 360f // Default width
        }
        


    val themeConfig: Flow<AppThemeConfig> = context.dataStore.data
        .map { preferences ->
            val value = preferences[THEME_CONFIG_KEY] ?: AppThemeConfig.FOLLOW_SYSTEM.name
            try {
                AppThemeConfig.valueOf(value)
            } catch (e: Exception) {
                AppThemeConfig.FOLLOW_SYSTEM
            }
        }

    suspend fun setListPaneWidth(width: Float) {
        context.dataStore.edit { preferences ->
            preferences[PANE_WIDTH_KEY] = width
        }
    }
    


    suspend fun setThemeConfig(config: AppThemeConfig) {
        context.dataStore.edit { preferences ->
            preferences[THEME_CONFIG_KEY] = config.name
        }
    }

    // --- Intelligence Configuration ---

    enum class AiModel(val id: String, val displayName: String) {
        GEMINI_FLASH("gemini-2.5-flash-lite", "Gemini 2.5 Flash Lite"),
        GEMINI_PRO("gemini-2.5-pro", "Gemini 2.5 Pro"),
        CORTEX_LOCAL("cortex-local", "Cortex (On-Device)"); // Added Local Option

        companion object {
            fun fromId(id: String): AiModel = entries.find { it.id == id } ?: GEMINI_FLASH
        }
    }

    private val KEY_AI_MODEL = stringPreferencesKey("ai_model_selection")
    
    val selectedAiModel: Flow<AiModel> = context.dataStore.data
        .map { preferences ->
            val id = preferences[KEY_AI_MODEL] ?: AiModel.GEMINI_FLASH.id
            AiModel.fromId(id)
        }

    suspend fun setAiModel(model: AiModel) {
        context.dataStore.edit { preferences ->
            preferences[KEY_AI_MODEL] = model.id
        }
    }

    // --- Local AI Configuration (Legacy/Advanced) ---

    private val KEY_EMBEDDING_MODEL = stringPreferencesKey("embedding_model_filename")
    private val KEY_LLM_MODEL_NAME = stringPreferencesKey("llm_model_name")

    val embeddingModel: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[KEY_EMBEDDING_MODEL] ?: "universal_sentence_encoder.tflite"
        }

    val llmModelName: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[KEY_LLM_MODEL_NAME] ?: "gemini-2.5-flash-lite"
        }

    suspend fun setEmbeddingModel(filename: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_EMBEDDING_MODEL] = filename
        }
    }

    suspend fun setLlmModelName(name: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_LLM_MODEL_NAME] = name
        }
    }

    // --- UI/UX Persisted States ---

    private val KEY_BRIEFING_EXPANDED = androidx.datastore.preferences.core.booleanPreferencesKey("briefing_expanded_state")

    val isBriefingExpanded: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[KEY_BRIEFING_EXPANDED] ?: true // Default to Expanded
        }

    suspend fun setBriefingExpanded(expanded: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_BRIEFING_EXPANDED] = expanded
        }
    }
}
