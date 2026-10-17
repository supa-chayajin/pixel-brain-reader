package cloud.wafflecommons.pixelbrainreader.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

enum class AppThemeConfig { FOLLOW_SYSTEM, LIGHT, DARK }

@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** Every setting is read through here: an unreadable preferences file yields the defaults instead of crashing collectors. */
    private val storedPreferences: Flow<Preferences> = context.dataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.e("UserPreferences", "Preferences unreadable; using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }

    /**
     * One setting as a flow. DataStore re-emits the whole preference set on every write, so without
     * distinctUntilChanged a single write (e.g. the list-pane width while dragging) woke every
     * observer of every setting.
     */
    private fun <T> setting(read: (Preferences) -> T): Flow<T> = storedPreferences.map(read).distinctUntilChanged()

    private val PANE_WIDTH_KEY = floatPreferencesKey("list_pane_width")

    private val THEME_CONFIG_KEY = stringPreferencesKey("app_theme_config")

    val listPaneWidth: Flow<Float> = setting { preferences ->
        preferences[PANE_WIDTH_KEY] ?: 360f // Default width
    }

    val themeConfig: Flow<AppThemeConfig> = setting { preferences ->
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

    val selectedAiModel: Flow<cloud.wafflecommons.pixelbrainreader.data.model.AiModel> = setting { preferences ->
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

    val embeddingModel: Flow<String> = setting { preferences ->
        preferences[KEY_EMBEDDING_MODEL] ?: "universal_sentence_encoder.tflite"
    }

    val llmModelName: Flow<String> = setting { preferences ->
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

    private val KEY_BRIEFING_EXPANDED = booleanPreferencesKey("briefing_expanded_state")

    val isBriefingExpanded: Flow<Boolean> = setting { preferences ->
        preferences[KEY_BRIEFING_EXPANDED] ?: true // Default to Expanded
    }

    suspend fun setBriefingExpanded(expanded: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_BRIEFING_EXPANDED] = expanded
        }
    }

    private val KEY_ORACLE_EXPANDED = booleanPreferencesKey("oracle_expanded_state")

    val isOracleExpanded: Flow<Boolean> = setting { preferences ->
        preferences[KEY_ORACLE_EXPANDED] ?: true // Default to Expanded
    }

    suspend fun setOracleExpanded(expanded: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_ORACLE_EXPANDED] = expanded
        }
    }

    // --- Sync Metadata ---
    private val KEY_LAST_INDEX_TIME = longPreferencesKey("last_index_timestamp")

    val lastIndexTime: Flow<Long> = setting { preferences ->
        preferences[KEY_LAST_INDEX_TIME] ?: 0L
    }

    suspend fun setLastIndexTime(timestamp: Long) {
        context.dataStore.edit { preferences ->
            preferences[KEY_LAST_INDEX_TIME] = timestamp
        }
    }

    // --- Google Sync ---
    private val KEY_GOOGLE_SYNC_ENABLED = booleanPreferencesKey("google_sync_enabled")

    val isGoogleSyncEnabled: Flow<Boolean> = setting { preferences ->
        preferences[KEY_GOOGLE_SYNC_ENABLED] ?: false
    }

    suspend fun setGoogleSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_GOOGLE_SYNC_ENABLED] = enabled
        }
    }

    // --- Reminders (Phase 3) ---
    private val KEY_VAULT_REMINDER_ENABLED = booleanPreferencesKey("vault_reminder_enabled")
    private val KEY_VAULT_REMINDER_TIME = stringPreferencesKey("vault_reminder_time")
    private val KEY_CHORES_REMINDER_ENABLED = booleanPreferencesKey("chores_reminder_enabled")
    // Comma-joined "HH:mm" window list, e.g. "09:00,14:00,20:00".
    private val KEY_CHORES_REMINDER_WINDOWS = stringPreferencesKey("chores_reminder_windows")

    val vaultReminderEnabled: Flow<Boolean> = setting { it[KEY_VAULT_REMINDER_ENABLED] ?: false }

    val vaultReminderTime: Flow<String> = setting { it[KEY_VAULT_REMINDER_TIME] ?: "20:00" }

    val choresReminderEnabled: Flow<Boolean> = setting { it[KEY_CHORES_REMINDER_ENABLED] ?: false }

    val choresReminderWindows: Flow<List<String>> = setting { prefs ->
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

    val navBarOrder: Flow<List<String>> = setting { prefs ->
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
    private val KEY_SOUND_EFFECTS = booleanPreferencesKey("sound_effects_enabled")

    val soundEffectsEnabled: Flow<Boolean> = setting { it[KEY_SOUND_EFFECTS] ?: false }

    suspend fun setSoundEffectsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SOUND_EFFECTS] = enabled }
    }

    // --- Memory Echoes (Spaced-repetition reviewed notes) ---
    private val KEY_REVIEWED_ECHOES = stringPreferencesKey("reviewed_memory_echoes")

    val reviewedEchoes: Flow<String> = setting { it[KEY_REVIEWED_ECHOES] ?: "" }

    suspend fun recordEchoReviewed(path: String) {
        val now = System.currentTimeMillis()
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_REVIEWED_ECHOES] ?: ""
            val updated = (current.split(",")
                .filter { it.isNotBlank() && !it.startsWith("$path:") } + "$path:$now")
                .takeLast(100)
                .joinToString(",")
            prefs[KEY_REVIEWED_ECHOES] = updated
        }
    }

    companion object {
        /** Canonical order + membership of the reorderable regular nav destinations. */
        val DEFAULT_NAVBAR_ORDER = listOf("home", "habits", "home_os", "chat", "mood", "stats")
    }
}
