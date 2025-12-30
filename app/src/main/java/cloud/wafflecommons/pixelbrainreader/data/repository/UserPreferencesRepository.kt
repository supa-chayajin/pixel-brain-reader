package cloud.wafflecommons.pixelbrainreader.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
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
    private val AI_MODEL_KEY = stringPreferencesKey("ai_model")
    private val THEME_CONFIG_KEY = stringPreferencesKey("app_theme_config")
    private val BIOMETRIC_ENABLED_KEY = booleanPreferencesKey("biometric_enabled")

    val listPaneWidth: Flow<Float> = context.dataStore.data
        .map { preferences ->
            preferences[PANE_WIDTH_KEY] ?: 360f // Default width
        }
        
    val aiModel: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[AI_MODEL_KEY] ?: "gemini-2.5-flash-lite" // Default
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

    val isBiometricEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[BIOMETRIC_ENABLED_KEY] ?: true // Default TRUE
        }

    suspend fun setListPaneWidth(width: Float) {
        context.dataStore.edit { preferences ->
            preferences[PANE_WIDTH_KEY] = width
        }
    }
    
    suspend fun setAiModel(modelId: String) {
        context.dataStore.edit { preferences ->
            preferences[AI_MODEL_KEY] = modelId
        }
    }

    suspend fun setThemeConfig(config: AppThemeConfig) {
        context.dataStore.edit { preferences ->
            preferences[THEME_CONFIG_KEY] = config.name
        }
    }
    
    suspend fun setBiometricEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[BIOMETRIC_ENABLED_KEY] = enabled
        }
    }
}

