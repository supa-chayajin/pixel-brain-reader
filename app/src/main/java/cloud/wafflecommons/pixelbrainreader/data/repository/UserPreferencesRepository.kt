package cloud.wafflecommons.pixelbrainreader.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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
     * Whether the private vault reaches the AI: embedded, and handed to Chat, only while the private
     * journal is unlocked (12.18, V3). Off by default (owner, 2026-11-10): a device-only choice.
     */
    val privateNotesInAi: Flow<Boolean> = setting { it[KEY_PRIVATE_NOTES_IN_AI] ?: false }

    suspend fun setPrivateNotesInAi(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_PRIVATE_NOTES_IN_AI] = enabled
        }
    }

    // --- Related while writing (K5, 12.20) ---
    private val KEY_RELATED_WHILE_WRITING = booleanPreferencesKey("related_while_writing")

    /** Whether the editor shows the notes closest to the paragraph being written. On by default. */
    val relatedWhileWriting: Flow<Boolean> = setting { it[KEY_RELATED_WHILE_WRITING] ?: true }

    suspend fun setRelatedWhileWriting(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_RELATED_WHILE_WRITING] = enabled
        }
    }

    // --- The wide window's rail (13.2, the owner's extra) ---
    private val KEY_NAV_RAIL_EXPANDED = booleanPreferencesKey("nav_rail_expanded")

    /** Whether a wide window's rail shows the tabs' names beside their icons. Off by default; this device only. */
    val navRailExpanded: Flow<Boolean> = setting { it[KEY_NAV_RAIL_EXPANDED] ?: false }

    suspend fun setNavRailExpanded(expanded: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_NAV_RAIL_EXPANDED] = expanded
        }
    }

    // --- Hybrid search (R2, 12.20; the owner's D3, 2026-11-15) ---
    private val KEY_HYBRID_SEARCH = booleanPreferencesKey("hybrid_search")

    /**
     * Whether Chat's notes are re-ranked by the words they share with the question
     * (`NoteRetrieval`). On by default; Settings → Knowledge Vault turns it off. Device-only.
     */
    val hybridSearch: Flow<Boolean> = setting { it[KEY_HYBRID_SEARCH] ?: true }

    suspend fun setHybridSearch(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_HYBRID_SEARCH] = enabled
        }
    }

    // --- The private journal's auto-lock (12.18, V2; owner, 2026-11-15) ---
    private val KEY_PRIVATE_AUTO_LOCK = intPreferencesKey("private_auto_lock_minutes")

    /**
     * Minutes after the journal leaves the screen before it locks: 0 (at once), 1, 5 or 15; 5 until
     * the owner picks another (privatevault.AutoLock checks the value). Device-only.
     */
    val privateAutoLockMinutes: Flow<Int> = setting { it[KEY_PRIVATE_AUTO_LOCK] ?: 5 }

    suspend fun setPrivateAutoLockMinutes(minutes: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEY_PRIVATE_AUTO_LOCK] = minutes
        }
    }

    // --- The tech watch's feed statuses (12.18, D5a): device state, never the vault ---
    private val KEY_WATCH_FEED_STATUS = stringPreferencesKey("watch_feed_status")

    /** Each feed's last read and last failure, as JSON (data/watch/FeedStatuses). */
    val watchFeedStatus: Flow<String?> = setting { it[KEY_WATCH_FEED_STATUS] }

    suspend fun setWatchFeedStatus(json: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_WATCH_FEED_STATUS] = json
        }
    }

    // --- People (12.19, D5): meeting series linked to a person, on this phone only, neutral ids ---
    private val KEY_PERSON_LINKS = stringPreferencesKey("person_links")

    /** The links as JSON (data/people/PersonLinks). */
    val personLinks: Flow<String?> = setting { it[KEY_PERSON_LINKS] }

    /** Changes the links in one edit, so two quick changes can't overwrite each other. */
    suspend fun updatePersonLinks(transform: (String?) -> String) {
        context.dataStore.edit { preferences -> preferences[KEY_PERSON_LINKS] = transform(preferences[KEY_PERSON_LINKS]) }
    }

    // --- Meeting prep (12.12, D4): off by default, nothing notifies unless switched on ---
    private val KEY_MEETING_PREP_REMINDERS = booleanPreferencesKey("meeting_prep_reminders")

    val meetingPrepReminders: Flow<Boolean> = setting { it[KEY_MEETING_PREP_REMINDERS] ?: false }

    suspend fun setMeetingPrepReminders(enabled: Boolean) {
        context.dataStore.edit { preferences -> preferences[KEY_MEETING_PREP_REMINDERS] = enabled }
    }

    // --- The 1:1 reminder (12.19, D6): 30 minutes before, on by default ---
    private val KEY_ONE_ON_ONE_REMINDERS = booleanPreferencesKey("one_on_one_reminders")

    val oneOnOneReminders: Flow<Boolean> = setting { it[KEY_ONE_ON_ONE_REMINDERS] ?: true }

    suspend fun setOneOnOneReminders(enabled: Boolean) {
        context.dataStore.edit { preferences -> preferences[KEY_ONE_ON_ONE_REMINDERS] = enabled }
    }

    // --- Weekly review (12.13, D6): the last week written, so a deleted note isn't written again ---
    private val KEY_WEEKLY_REVIEW_WRITTEN = stringPreferencesKey("weekly_review_written")

    val weeklyReviewWritten: Flow<String?> = setting { it[KEY_WEEKLY_REVIEW_WRITTEN] }

    suspend fun setWeeklyReviewWritten(weekKey: String) {
        context.dataStore.edit { preferences -> preferences[KEY_WEEKLY_REVIEW_WRITTEN] = weekKey }
    }

    // --- Monthly reports (12.13, D7): the last month written ("2026-10") ---
    private val KEY_MONTHLY_REPORTS_WRITTEN = stringPreferencesKey("monthly_reports_written")

    val monthlyReportsWritten: Flow<String?> = setting { it[KEY_MONTHLY_REPORTS_WRITTEN] }

    suspend fun setMonthlyReportsWritten(month: String) {
        context.dataStore.edit { preferences -> preferences[KEY_MONTHLY_REPORTS_WRITTEN] = month }
    }

    // --- Focus session (12.14, D9): the running one ("startMillis|minutes"), so it outlives the app ---
    private val KEY_FOCUS_SESSION = stringPreferencesKey("focus_session")

    val focusSession: Flow<String?> = setting { it[KEY_FOCUS_SESSION] }

    /** Keeps [encoded] as the running session; null ends it. */
    suspend fun setFocusSession(encoded: String?) {
        context.dataStore.edit { preferences ->
            if (encoded == null) preferences.remove(KEY_FOCUS_SESSION) else preferences[KEY_FOCUS_SESSION] = encoded
        }
    }

    // --- The workshop under way (12.21, W2): its draft as JSON, on the phone until the synthesis ---
    private val KEY_WORKSHOP_DRAFT = stringPreferencesKey("workshop_draft")

    val workshopDraft: Flow<String?> = setting { it[KEY_WORKSHOP_DRAFT] }

    /** Keeps [encoded] as the workshop under way; null ends it. */
    suspend fun setWorkshopDraft(encoded: String?) {
        context.dataStore.edit { preferences ->
            if (encoded == null) preferences.remove(KEY_WORKSHOP_DRAFT) else preferences[KEY_WORKSHOP_DRAFT] = encoded
        }
    }

    // --- Shutdown ritual (12.14, D8): the last day closed ("2026-11-11"), so its banner stays away ---
    private val KEY_SHUTDOWN_CLOSED_DAY = stringPreferencesKey("shutdown_closed_day")

    val shutdownClosedDay: Flow<String?> = setting { it[KEY_SHUTDOWN_CLOSED_DAY] }

    suspend fun setShutdownClosedDay(day: java.time.LocalDate) {
        context.dataStore.edit { preferences -> preferences[KEY_SHUTDOWN_CLOSED_DAY] = day.toString() }
    }

    // --- Games (12.17, RFC-009): the running session ("startMillis|page"), the day a game night hid the
    // shutdown banner, the "Game night" mode's rule id, and the last PSN sync's outcome. Device-only. ---
    private val KEY_GAME_SESSION = stringPreferencesKey("game_session")
    private val KEY_GAME_NIGHT_DAY = stringPreferencesKey("game_night_day")
    private val KEY_GAME_NIGHT_RULE = stringPreferencesKey("game_night_rule")
    private val KEY_PSN_STATUS = stringPreferencesKey("psn_status")

    val gameSession: Flow<String?> = setting { it[KEY_GAME_SESSION] }

    /** Keeps [encoded] as the running game session; null ends it. */
    suspend fun setGameSession(encoded: String?) {
        context.dataStore.edit { preferences ->
            if (encoded == null) preferences.remove(KEY_GAME_SESSION) else preferences[KEY_GAME_SESSION] = encoded
        }
    }

    // 13.1: the running session's "Still playing?" state (`SessionCheck`: "start|nextAskAt|askedAt").
    private val KEY_GAME_SESSION_CHECK = stringPreferencesKey("game_session_check")

    val gameSessionCheck: Flow<String?> = setting { it[KEY_GAME_SESSION_CHECK] }

    suspend fun setGameSessionCheck(encoded: String?) {
        context.dataStore.edit { preferences ->
            if (encoded == null) preferences.remove(KEY_GAME_SESSION_CHECK) else preferences[KEY_GAME_SESSION_CHECK] = encoded
        }
    }

    val gameNightDay: Flow<String?> = setting { it[KEY_GAME_NIGHT_DAY] }

    suspend fun setGameNightDay(day: java.time.LocalDate) {
        context.dataStore.edit { preferences -> preferences[KEY_GAME_NIGHT_DAY] = day.toString() }
    }

    val gameNightRule: Flow<String?> = setting { it[KEY_GAME_NIGHT_RULE] }

    suspend fun setGameNightRule(id: String?) {
        context.dataStore.edit { preferences ->
            if (id == null) preferences.remove(KEY_GAME_NIGHT_RULE) else preferences[KEY_GAME_NIGHT_RULE] = id
        }
    }

    val psnStatus: Flow<String?> = setting { it[KEY_PSN_STATUS] }

    suspend fun setPsnStatus(encoded: String?) {
        context.dataStore.edit { preferences ->
            if (encoded == null) preferences.remove(KEY_PSN_STATUS) else preferences[KEY_PSN_STATUS] = encoded
        }
    }

    // --- Review notifications (12.13): off by default, nothing notifies unless switched on ---
    private val KEY_REVIEW_NOTIFICATIONS = booleanPreferencesKey("review_notifications")

    val reviewNotifications: Flow<Boolean> = setting { it[KEY_REVIEW_NOTIFICATIONS] ?: false }

    suspend fun setReviewNotifications(enabled: Boolean) {
        context.dataStore.edit { preferences -> preferences[KEY_REVIEW_NOTIFICATIONS] = enabled }
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

    // --- Inbox triage (12.21, E7): notes snoozed until a day, "yyyy-MM-dd|path"; device-only ---
    private val KEY_INBOX_SNOOZED = androidx.datastore.preferences.core.stringSetPreferencesKey("inbox_snoozed")

    val inboxSnoozed: Flow<Set<String>> = setting { it[KEY_INBOX_SNOOZED] ?: emptySet() }

    /** Applies [change] to the snoozed entries, in one edit. */
    suspend fun updateInboxSnoozed(change: (Set<String>) -> Set<String>) {
        context.dataStore.edit { prefs -> prefs[KEY_INBOX_SNOOZED] = change(prefs[KEY_INBOX_SNOOZED] ?: emptySet()) }
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
        // A tab added later joins the end of a saved order (see navBarOrder): "projects" came in 12.7.0,
        // "games" in 12.17.0 (RFC-009, D4: the 8th, nothing existing moves).
        val DEFAULT_NAVBAR_ORDER = listOf("home", "habits", "home_os", "chat", "mood", "stats", "projects", "games")
    }
}
