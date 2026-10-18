package cloud.wafflecommons.pixelbrainreader.data.guard

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The guard's settings, in encrypted preferences on this device only. The deny-list names exactly
 * what must never reach the git remote, so it can't live in the vault.
 */
@Singleton
class GuardStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** True when the stored settings couldn't be read and the guard started again from defaults. */
    @Volatile
    var wasReset: Boolean = false
        private set

    // Keystore-backed: created on first use (off the main thread), never at injection.
    private val prefs: SharedPreferences by lazy {
        try {
            create()
        } catch (e: Exception) {
            Log.e(TAG, "Guard settings unreadable; starting again from defaults", e)
            context.deleteSharedPreferences(FILE)
            wasReset = true
            create()
        }
    }

    fun load(): GuardSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return GuardSettings()
        return try {
            json.decodeFromString(GuardSettings.serializer(), raw)
        } catch (e: Exception) {
            Log.e(TAG, "Guard settings unparseable; starting again from defaults", e)
            wasReset = true
            GuardSettings()
        }
    }

    fun save(settings: GuardSettings) {
        prefs.edit(commit = true) { putString(KEY_SETTINGS, json.encodeToString(GuardSettings.serializer(), settings)) }
    }

    private fun create(): SharedPreferences = EncryptedSharedPreferences.create(
        context,
        FILE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private companion object {
        const val TAG = "GuardStore"
        const val FILE = "confidentiality_guard"
        const val KEY_SETTINGS = "settings"
    }
}
