package cloud.wafflecommons.pixelbrainreader.data.local.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cloud.wafflecommons.pixelbrainreader.data.gamification.Attribute
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.gamificationDataStore: DataStore<Preferences> by preferencesDataStore(name = "gamification_prefs")

@Singleton
class GamificationPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()

    object Keys {
        val STEP_TARGET = intPreferencesKey("step_target")
        val SLEEP_MIN_MINUTES = intPreferencesKey("sleep_min_minutes")
        val TAG_TO_STAT_MAPPING = stringPreferencesKey("tag_to_stat_mapping")
        val LAST_HEALTH_SYNERGY_APPLIED_DATE = stringPreferencesKey("last_health_synergy_applied_date")
        val MOOD_EMOJI_MAPPING = stringPreferencesKey("mood_emoji_mapping")
    }

    val stepTargetFlow: Flow<Int> = context.gamificationDataStore.data.map { preferences ->
        preferences[Keys.STEP_TARGET] ?: 10000
    }

    val sleepMinMinutesFlow: Flow<Int> = context.gamificationDataStore.data.map { preferences ->
        preferences[Keys.SLEEP_MIN_MINUTES] ?: 300
    }

    val tagToStatMappingFlow: Flow<Map<String, Attribute>> = context.gamificationDataStore.data.map { preferences ->
        val json = preferences[Keys.TAG_TO_STAT_MAPPING]
        if (json.isNullOrEmpty()) {
            emptyMap()
        } else {
            try {
                val type = object : TypeToken<Map<String, Attribute>>() {}.type
                gson.fromJson(json, type)
            } catch (e: Exception) {
                emptyMap()
            }
        }
    }

    val lastHealthSynergyAppliedDateFlow: Flow<String> = context.gamificationDataStore.data.map { preferences ->
        preferences[Keys.LAST_HEALTH_SYNERGY_APPLIED_DATE] ?: ""
    }

    val moodEmojiMappingFlow: Flow<Map<Int, String>> = context.gamificationDataStore.data.map { preferences ->
        val json = preferences[Keys.MOOD_EMOJI_MAPPING]
        if (json.isNullOrEmpty()) {
            mapOf(1 to "😭", 2 to "😕", 3 to "😐", 4 to "🙂", 5 to "🤩")
        } else {
            try {
                val type = object : TypeToken<Map<Int, String>>() {}.type
                gson.fromJson(json, type)
            } catch (e: Exception) {
                mapOf(1 to "😭", 2 to "😕", 3 to "😐", 4 to "🙂", 5 to "🤩")
            }
        }
    }

    suspend fun setStepTarget(target: Int) {
        context.gamificationDataStore.edit { preferences ->
            preferences[Keys.STEP_TARGET] = target
        }
    }

    suspend fun setSleepMinMinutes(minutes: Int) {
        context.gamificationDataStore.edit { preferences ->
            preferences[Keys.SLEEP_MIN_MINUTES] = minutes
        }
    }

    suspend fun setTagToStatMapping(mapping: Map<String, Attribute>) {
        val json = gson.toJson(mapping)
        context.gamificationDataStore.edit { preferences ->
            preferences[Keys.TAG_TO_STAT_MAPPING] = json
        }
    }

    suspend fun setLastHealthSynergyAppliedDate(date: String) {
        context.gamificationDataStore.edit { preferences ->
            preferences[Keys.LAST_HEALTH_SYNERGY_APPLIED_DATE] = date
        }
    }

    suspend fun setMoodEmojiMapping(mapping: Map<Int, String>) {
        val json = gson.toJson(mapping)
        context.gamificationDataStore.edit { preferences ->
            preferences[Keys.MOOD_EMOJI_MAPPING] = json
        }
    }
}
