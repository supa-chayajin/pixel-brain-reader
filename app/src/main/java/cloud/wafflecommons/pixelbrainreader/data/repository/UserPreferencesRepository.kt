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

    private val KEY_AI_MODEL = stringPreferencesKey("ai_model_selection")
    
    val selectedAiModel: Flow<cloud.wafflecommons.pixelbrainreader.data.model.AiModel> = context.dataStore.data
        .map { preferences ->
            val id = preferences[KEY_AI_MODEL] ?: cloud.wafflecommons.pixelbrainreader.data.model.AiModel.CORTEX_LOCAL.id
            cloud.wafflecommons.pixelbrainreader.data.model.AiModel.fromId(id)
        }

    suspend fun setAiModel(model: cloud.wafflecommons.pixelbrainreader.data.model.AiModel) {
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

    private val KEY_ORACLE_EXPANDED = androidx.datastore.preferences.core.booleanPreferencesKey("oracle_expanded_state")

    val isOracleExpanded: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[KEY_ORACLE_EXPANDED] ?: true // Default to Expanded
        }

    suspend fun setOracleExpanded(expanded: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_ORACLE_EXPANDED] = expanded
        }
    }
    // --- Sync Metadata ---
    private val KEY_LAST_INDEX_TIME = androidx.datastore.preferences.core.longPreferencesKey("last_index_timestamp")

    val lastIndexTime: Flow<Long> = context.dataStore.data
        .map { preferences ->
            preferences[KEY_LAST_INDEX_TIME] ?: 0L 
        }

    suspend fun setLastIndexTime(timestamp: Long) {
        context.dataStore.edit { preferences ->
            preferences[KEY_LAST_INDEX_TIME] = timestamp
        }
    }

    // --- Google Sync ---
    private val KEY_GOOGLE_SYNC_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("google_sync_enabled")

    val isGoogleSyncEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[KEY_GOOGLE_SYNC_ENABLED] ?: false 
        }

    suspend fun setGoogleSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_GOOGLE_SYNC_ENABLED] = enabled
        }
    }

    // --- Reminders (Phase 3) ---
    private val KEY_VAULT_REMINDER_ENABLED =
        androidx.datastore.preferences.core.booleanPreferencesKey("vault_reminder_enabled")
    private val KEY_VAULT_REMINDER_TIME = stringPreferencesKey("vault_reminder_time")
    private val KEY_CHORES_REMINDER_ENABLED =
        androidx.datastore.preferences.core.booleanPreferencesKey("chores_reminder_enabled")
    // Comma-joined "HH:mm" window list, e.g. "09:00,14:00,20:00".
    private val KEY_CHORES_REMINDER_WINDOWS = stringPreferencesKey("chores_reminder_windows")

    val vaultReminderEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_VAULT_REMINDER_ENABLED] ?: false }

    val vaultReminderTime: Flow<String> = context.dataStore.data
        .map { it[KEY_VAULT_REMINDER_TIME] ?: "20:00" }

    val choresReminderEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_CHORES_REMINDER_ENABLED] ?: false }

    val choresReminderWindows: Flow<List<String>> = context.dataStore.data
        .map { prefs ->
            (prefs[KEY_CHORES_REMINDER_WINDOWS] ?: "09:00,14:00,20:00")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
        }

    suspend fun setVaultReminderEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_VAULT_REMINDER_ENABLED] = enabled }
    }

    suspend fun setVaultReminderTime(hhmm: String) {
        context.dataStore.edit { it[KEY_VAULT_REMINDER_TIME] = hhmm }
    }

    suspend fun setChoresReminderEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_CHORES_REMINDER_ENABLED] = enabled }
    }

    suspend fun setChoresReminderWindows(windows: List<String>) {
        val cleaned = windows.map { it.trim() }.filter { it.isNotBlank() }.distinct().sorted()
        context.dataStore.edit { it[KEY_CHORES_REMINDER_WINDOWS] = cleaned.joinToString(",") }
    }

    // --- Navigation bar order (the regular tabs; the "Daily" button is fixed/separate) ---
    private val KEY_NAVBAR_ORDER = stringPreferencesKey("navbar_order")

    val navBarOrder: Flow<List<String>> = context.dataStore.data
        .map { prefs ->
            val saved = prefs[KEY_NAVBAR_ORDER]
                ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                ?: emptyList()
            // Merge the saved order with the canonical set so newly-added destinations always
            // appear (appended) and removed/renamed ones are dropped — the list stays valid
            // across app updates without a migration.
            val valid = saved.filter { it in DEFAULT_NAVBAR_ORDER }
            valid + DEFAULT_NAVBAR_ORDER.filter { it !in valid }
        }

    suspend fun setNavBarOrder(order: List<String>) {
        val cleaned = order.filter { it in DEFAULT_NAVBAR_ORDER }.distinct()
        val full = cleaned + DEFAULT_NAVBAR_ORDER.filter { it !in cleaned }
        context.dataStore.edit { it[KEY_NAVBAR_ORDER] = full.joinToString(",") }
    }

    // --- Sound effects (opt-in; paired with existing haptics) ---
    private val KEY_SOUND_EFFECTS = androidx.datastore.preferences.core.booleanPreferencesKey("sound_effects_enabled")

    val soundEffectsEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_SOUND_EFFECTS] ?: false }

    suspend fun setSoundEffectsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SOUND_EFFECTS] = enabled }
    }

    companion object {
        /** Canonical order + membership of the reorderable regular nav destinations. */
        val DEFAULT_NAVBAR_ORDER = listOf("home", "habits", "home_os", "chat", "mood", "stats")
    }
}
