package cloud.wafflecommons.pixelbrainreader.data.local.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cloud.wafflecommons.pixelbrainreader.data.gamification.Attribute
import cloud.wafflecommons.pixelbrainreader.data.mood.MoodEmojiSet
import cloud.wafflecommons.pixelbrainreader.data.mood.MoodEmojiSets
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.gamificationDataStore: DataStore<Preferences> by preferencesDataStore(name = "gamification_prefs")

@Singleton
class GamificationPreferences @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val gson = Gson()

    object Keys {
        val STEP_TARGET = intPreferencesKey("step_target")
        val SLEEP_MIN_MINUTES = intPreferencesKey("sleep_min_minutes")
        val TAG_TO_STAT_MAPPING = stringPreferencesKey("tag_to_stat_mapping")
        val LAST_HEALTH_SYNERGY_APPLIED_DATE = stringPreferencesKey("last_health_synergy_applied_date")
        /** The id of the mood emoji set this device shows (replaced the never-read per-score "mood_emoji_mapping"). */
        val MOOD_EMOJI_SET = stringPreferencesKey("mood_emoji_set")
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

    /**
     * The mood emoji set this device shows everywhere ([MoodEmojiSets]); the default until one is
     * picked, or when the store can't be read — a display setting never stops a mood from showing
     * or saving. Distinct: the store emits on every key change (the step slider writes per tick).
     */
    val moodEmojiSetFlow: Flow<MoodEmojiSet> = context.gamificationDataStore.data
        .map { preferences -> MoodEmojiSets.byId(preferences[Keys.MOOD_EMOJI_SET]) }
        .catch { e -> if (e is IOException) emit(MoodEmojiSets.DEFAULT) else throw e }
        .distinctUntilChanged()

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

    suspend fun setMoodEmojiSet(id: String) {
        context.gamificationDataStore.edit { preferences ->
            preferences[Keys.MOOD_EMOJI_SET] = MoodEmojiSets.byId(id).id
        }
    }
}
