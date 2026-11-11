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
    @param:ApplicationContext private val context: Context
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

    // --- Private notes and the AI (settled decision #20) ---
    private val KEY_PRIVATE_NOTES_IN_AI = booleanPreferencesKey("private_notes_in_ai")

    /**
     * Whether the private vault reaches the AI: embedded while its password is saved, and handed
     * to Chat while it is unlocked. Off by default (owner, 2026-11-10): a device-only choice.
     */
    val privateNotesInAi: Flow<Boolean> = setting { it[KEY_PRIVATE_NOTES_IN_AI] ?: false }

    suspend fun setPrivateNotesInAi(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_PRIVATE_NOTES_IN_AI] = enabled
        }
    }

    // --- Meeting prep (12.12, D4): off by default, nothing notifies unless switched on ---
    private val KEY_MEETING_PREP_REMINDERS = booleanPreferencesKey("meeting_prep_reminders")

    val meetingPrepReminders: Flow<Boolean> = setting { it[KEY_MEETING_PREP_REMINDERS] ?: false }

    suspend fun setMeetingPrepReminders(enabled: Boolean) {
        context.dataStore.edit { preferences -> preferences[KEY_MEETING_PREP_REMINDERS] = enabled }
    }

    // --- Weekly review (12.13, D6): the last week written, so a deleted note isn't written again ---
    private val KEY_WEEKLY_REVIEW_WRITTEN = stringPreferencesKey("weekly_review_written")

    val weeklyReviewWritten: Flow<String?> = setting { it[KEY_WEEKLY_REVIEW_WRITTEN] }

    suspend fun setWeeklyReviewWritten(weekKey: String) {
        context.dataStore.edit { preferences -> preferences[KEY_WEEKLY_REVIEW_WRITTEN] = weekKey }
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

    // --- Evening check-in (v12): mood + one line + gratitude, answered from the notification ---
    private val KEY_CHECKIN_ENABLED = booleanPreferencesKey("evening_checkin_enabled")
    private val KEY_CHECKIN_TIME = stringPreferencesKey("evening_checkin_time")

    /** On by default: the owner asked for it; one notification a day, switched off in Reminders. */
    val eveningCheckInEnabled: Flow<Boolean> = setting { it[KEY_CHECKIN_ENABLED] ?: true }
    val eveningCheckInTime: Flow<String> = setting { it[KEY_CHECKIN_TIME] ?: "21:00" }

    suspend fun setEveningCheckInEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_CHECKIN_ENABLED] = enabled }
    }

    suspend fun setEveningCheckInTime(hhmm: String) {
        context.dataStore.edit { it[KEY_CHECKIN_TIME] = hhmm }
    }

    // --- Task rollover (12.11.0): the earlier days' tasks brought or dismissed on this phone ---
    private val KEY_ROLLOVER_HANDLED = androidx.datastore.preferences.core.stringSetPreferencesKey("rollover_handled")

    /** Keys from [cloud.wafflecommons.pixelbrainreader.data.daily.TaskRollover.key]; device-only. */
    val rolloverHandled: Flow<Set<String>> = setting { it[KEY_ROLLOVER_HANDLED] ?: emptySet() }

    /** Adds [keys] and drops the ones too old to be offered again, in one edit. */
    suspend fun markRolloverHandled(keys: Collection<String>, today: java.time.LocalDate) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_ROLLOVER_HANDLED] ?: emptySet()
            prefs[KEY_ROLLOVER_HANDLED] =
                cloud.wafflecommons.pixelbrainreader.data.daily.TaskRollover.prune(current + keys, today)
        }
    }

    // --- Simpler screens (v12): what the Daily screen shows, the fold, pinned tools ---
    private val KEY_DAILY_SHOWN = androidx.datastore.preferences.core.stringSetPreferencesKey("daily_shown_sections")
    private val KEY_DAILY_MORE_EXPANDED = booleanPreferencesKey("daily_more_expanded")
    private val KEY_DAILY_ORDER = stringPreferencesKey("daily_section_order")
    private val KEY_PINNED_TOOLS = androidx.datastore.preferences.core.stringSetPreferencesKey("toolkit_pinned_tools")

    val dailyShownSections: Flow<Set<cloud.wafflecommons.pixelbrainreader.data.model.DailySection>> =
        setting { cloud.wafflecommons.pixelbrainreader.data.model.DailySection.fromKeys(it[KEY_DAILY_SHOWN]) }

    /** Every Daily block in the user's order (Settings → Daily screen); on screen and in the fold, each group follows it. */
    val dailySectionOrder: Flow<List<cloud.wafflecommons.pixelbrainreader.data.model.DailySection>> =
        setting { cloud.wafflecommons.pixelbrainreader.data.model.DailySection.orderFromKeys(it[KEY_DAILY_ORDER]) }

    val dailyMoreExpanded: Flow<Boolean> = setting { it[KEY_DAILY_MORE_EXPANDED] ?: false }

    /** Toolkit tool ids pinned to the top of the hub; null = never chosen (the hub shows its defaults). */
    val pinnedTools: Flow<Set<String>?> = setting { it[KEY_PINNED_TOOLS] }

    suspend fun setDailySectionShown(section: cloud.wafflecommons.pixelbrainreader.data.model.DailySection, shown: Boolean) {
        context.dataStore.edit { prefs ->
            val current = cloud.wafflecommons.pixelbrainreader.data.model.DailySection.fromKeys(prefs[KEY_DAILY_SHOWN])
            val next = if (shown) current + section else current - section
            prefs[KEY_DAILY_SHOWN] = next.map { it.key }.toSet()
        }
    }

    /**
     * Moves [section] one step within its group. The order and the shown set are read inside the
     * same edit, so quick taps on the arrows never compute from a stale order and lose a move.
     */
    suspend fun moveDailySection(section: cloud.wafflecommons.pixelbrainreader.data.model.DailySection, up: Boolean) {
        context.dataStore.edit { prefs ->
            val ds = cloud.wafflecommons.pixelbrainreader.data.model.DailySection
            val order = ds.orderFromKeys(prefs[KEY_DAILY_ORDER])
            val shown = ds.fromKeys(prefs[KEY_DAILY_SHOWN])
            prefs[KEY_DAILY_ORDER] = ds.toKeys(ds.moved(order, shown, section, up))
        }
    }

    suspend fun setDailyMoreExpanded(expanded: Boolean) {
        context.dataStore.edit { it[KEY_DAILY_MORE_EXPANDED] = expanded }
    }

    suspend fun setToolPinned(id: String, pinned: Boolean, defaults: Set<String>) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_PINNED_TOOLS] ?: defaults
            prefs[KEY_PINNED_TOOLS] = if (pinned) current + id else current - id
        }
    }

    companion object {
        /** Canonical order + membership of the reorderable regular nav destinations. */
        // A tab added later joins the end of a saved order (see navBarOrder): "projects" came in 12.7.0.
        val DEFAULT_NAVBAR_ORDER = listOf("home", "habits", "home_os", "chat", "mood", "stats", "projects")
    }
}
