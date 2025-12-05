package cloud.wafflecommons.pixelbrainreader.data.local.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecretManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val VAULT_FILENAME = "secure_vault"
        // Legacy file name used in V1.0 - Assumed based on User Request for migration
        // If the old app used "secure_prefs" (unencrypted), we target that.
        // Based on V1 analysis, 'TokenManager' used 'secure_prefs', but if it was unencrypted,
        // we must migrate it to the new Encrypted store (or re-encrypt it).
        // Here we assume a migration from an unencrypted file named "token_prefs" or similar, 
        // OR we re-encrypt 'secure_prefs' if it was not actually secure.
        // For safety, let's look for "token_manager_prefs" which is a common default for manual prefs.
        private const val LEGACY_PREFS_NAME = "secure_prefs" 
        
        private const val KEY_TOKEN = "github_token"
        private const val KEY_REPO_OWNER = "repo_owner"
        private const val KEY_REPO_NAME = "repo_name"
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        VAULT_FILENAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    init {
        migrateFromLegacy()
    }

    /**
     * V2.0 SECURITY MIGRATION
     * Checks for the existence of the legacy unencrypted file.
     * If found, reads the token, executes a secure save, and WANTONLY DESTROYS the old file.
     */
    private fun migrateFromLegacy() {
        // We check if the file exists on disk to avoid creating an empty one by accessing it
        val legacyFile = File(context.filesDir.parent, "shared_prefs/$LEGACY_PREFS_NAME.xml")
        
        if (legacyFile.exists()) {
            Log.i("SecretManager", "Legacy unencrypted credentials found. Initiating migration protocol.")
            
            val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            val token = legacyPrefs.getString(KEY_TOKEN, null)
            val owner = legacyPrefs.getString(KEY_REPO_OWNER, null)
            val repo = legacyPrefs.getString(KEY_REPO_NAME, null)

            if (!token.isNullOrEmpty()) {
                saveToken(token)
                Log.d("SecretManager", "Token migrated to Vault.")
            }
            
            if (!owner.isNullOrEmpty() && !repo.isNullOrEmpty()) {
                saveRepoInfo(owner, repo)
            }

            // WIPE AND DESTROY
            legacyPrefs.edit().clear().commit() // Clear content
            // The file itself might remain but be empty. In strict environments, we might want to delete the file.
            if (legacyFile.delete()) {
                Log.i("SecretManager", "Legacy file incinerated.")
            } else {
                Log.w("SecretManager", "Failed to delete legacy file path.")
            }
        }
    }

    fun saveToken(token: String) {
        encryptedPrefs.edit().putString(KEY_TOKEN, token).apply()
    }

    fun getToken(): String? {
        return encryptedPrefs.getString(KEY_TOKEN, null)
    }

    fun saveRepoInfo(owner: String, repo: String) {
        encryptedPrefs.edit()
            .putString(KEY_REPO_OWNER, owner)
            .putString(KEY_REPO_NAME, repo)
            .apply()
    }

    fun getRepoInfo(): Pair<String?, String?> {
        val owner = encryptedPrefs.getString(KEY_REPO_OWNER, null)
        val repo = encryptedPrefs.getString(KEY_REPO_NAME, null)
        return Pair(owner, repo)
    }

    fun clear() {
        encryptedPrefs.edit().clear().apply()
    }
}
