package cloud.wafflecommons.pixelbrainreader.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val PANE_WIDTH_KEY = floatPreferencesKey("list_pane_width")

    val listPaneWidth: Flow<Float> = context.dataStore.data
        .map { preferences ->
            preferences[PANE_WIDTH_KEY] ?: 360f // Default width
        }

    suspend fun setListPaneWidth(width: Float) {
        context.dataStore.edit { preferences ->
            preferences[PANE_WIDTH_KEY] = width
        }
    }
}
