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
        private const val LEGACY_PREFS_NAME = "secure_prefs"

        private const val KEY_TOKEN = "github_token"
        private const val KEY_REPO_OWNER = "repo_owner"
        private const val KEY_REPO_NAME = "repo_name"
        const val KEY_PROVIDER = "provider_type"
        private const val KEY_VAULT_PASSWORD = "vault_master_password"

        // V6: Google ecosystem auth tokens (Credential Manager + AuthorizationClient).
        private const val KEY_GOOGLE_EMAIL = "google_email"
        private const val KEY_GOOGLE_ACCESS_TOKEN = "google_access_token"
        private const val KEY_GOOGLE_TOKEN_EXPIRES_AT = "google_token_expires_at"
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            createEncryptedPrefs()
        } catch (e: Exception) {
            // GeneralSecurityException, AEADBadTagException, KeyStoreException
            Log.e("SecretManager", "EncryptedSharedPreferences corrupted. Resetting vault.", e)
            // Delete the corrupted file
            context.deleteSharedPreferences(VAULT_FILENAME)
            try {
                // Try again nicely
                createEncryptedPrefs()
            } catch (retryException: Exception) {
                Log.e(
                    "SecretManager",
                    "Failed to recreate vault after reset. Fallback to unencrypted mode or fatal crash?",
                    retryException
                )
                // If it fails again, we are in trouble. But usually, deleting the file fixes the "Bad Tag" / "Signature Failed" issue.
                throw retryException
            }
        }
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        return EncryptedSharedPreferences.create(
            context,
            VAULT_FILENAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

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
            Log.i(
                "SecretManager",
                "Legacy unencrypted credentials found. Initiating migration protocol."
            )

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

    fun saveProvider(type: String) {
        encryptedPrefs.edit().putString(KEY_PROVIDER, type).apply()
    }

    fun getRepoInfo(): Pair<String?, String?> {
        val owner = encryptedPrefs.getString(KEY_REPO_OWNER, null)
        val repo = encryptedPrefs.getString(KEY_REPO_NAME, null)
        return Pair(owner, repo)
    }

    fun getProvider(): String {
        return encryptedPrefs.getString(KEY_PROVIDER, "github") ?: "github"
    }

    fun clear() {
        encryptedPrefs.edit().clear().apply()
    }

    /**
     * V2.0: Private Vault Password Management
     */
    fun saveVaultPassword(password: String) {
        encryptedPrefs.edit().putString(KEY_VAULT_PASSWORD, password).apply()
    }

    fun getVaultPassword(): String? {
        return encryptedPrefs.getString(KEY_VAULT_PASSWORD, null)
    }

    // --- V6: Google ecosystem credentials -------------------------------------

    fun saveGoogleEmail(email: String) {
        encryptedPrefs.edit().putString(KEY_GOOGLE_EMAIL, email).apply()
    }

    fun getGoogleEmail(): String? = encryptedPrefs.getString(KEY_GOOGLE_EMAIL, null)

    fun saveGoogleAccessToken(token: String, expiresAtMillis: Long) {
        encryptedPrefs.edit()
            .putString(KEY_GOOGLE_ACCESS_TOKEN, token)
            .putLong(KEY_GOOGLE_TOKEN_EXPIRES_AT, expiresAtMillis)
            .apply()
    }

    fun getGoogleAccessToken(): Pair<String, Long>? {
        val token = encryptedPrefs.getString(KEY_GOOGLE_ACCESS_TOKEN, null) ?: return null
        val expiresAt = encryptedPrefs.getLong(KEY_GOOGLE_TOKEN_EXPIRES_AT, 0L)
        return token to expiresAt
    }

    fun clearGoogleAuth() {
        encryptedPrefs.edit()
            .remove(KEY_GOOGLE_EMAIL)
            .remove(KEY_GOOGLE_ACCESS_TOKEN)
            .remove(KEY_GOOGLE_TOKEN_EXPIRES_AT)
            .apply()
    }

    /**
     * Clears ONLY the cached access token + expiry, preserving the linked email.
     * Used by GoogleAuthRepository.invalidateAccessToken so a 401 from the
     * Google APIs forces the next [getValidAccessToken] call to re-authorize
     * silently via AuthorizationClient, without un-linking the account.
     */
    fun clearGoogleAccessToken() {
        encryptedPrefs.edit()
            .remove(KEY_GOOGLE_ACCESS_TOKEN)
            .remove(KEY_GOOGLE_TOKEN_EXPIRES_AT)
            .apply()
    }
}
