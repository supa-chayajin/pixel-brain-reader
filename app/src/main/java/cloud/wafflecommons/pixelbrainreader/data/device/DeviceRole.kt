package cloud.wafflecommons.pixelbrainreader.data.device

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether this device only reads the vault. Chosen on the login screen and changed only by logging
 * out and back in (with a fresh clone), so it never flips under a running sync. Kept in plain
 * preferences: nothing secret. [VaultWriteGate] mirrors it for every writer.
 */
@Singleton
class DeviceRole @Inject constructor(@ApplicationContext private val context: Context) {

    val isReadOnly: Boolean
        get() = VaultWriteGate.readOnly

    /** The role saved for the next start, without changing the running one (see [apply]). */
    fun saved(): Boolean = load(context)

    /** Saves the role and switches every writer to it at once. */
    fun apply(readOnly: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_READ_ONLY, readOnly) }
        VaultWriteGate.readOnly = readOnly
    }

    companion object {
        private const val PREFS = "device_role"
        private const val KEY_READ_ONLY = "read_only"

        /** The saved role. `PixelBrainApplication.onCreate` loads it into [VaultWriteGate] first. */
        fun load(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_READ_ONLY, false)
    }
}
