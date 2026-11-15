package cloud.wafflecommons.pixelbrainreader.data.local.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecretManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val VAULT_FILENAME = "secure_vault"
        private const val LEGACY_PREFS_NAME = "secure_prefs"

        private const val KEY_TOKEN = "github_token"
        private const val KEY_REPO_OWNER = "repo_owner"
        private const val KEY_REPO_NAME = "repo_name"
        const val KEY_PROVIDER = "provider_type"
        // Until 12.17 the private vault's password, readable: only the 12.18 migration reads it, once.
        private const val KEY_VAULT_PASSWORD = "vault_master_password"

        // 12.18 (V3): the password wrapped by the biometric key (VaultPasswordBinding).
        private const val KEY_WRAPPED_VAULT_PASSWORD = "vault_password_wrapped"

        // V6: Google ecosystem auth tokens (Credential Manager + AuthorizationClient).
        private const val KEY_GOOGLE_EMAIL = "google_email"
        private const val KEY_GOOGLE_ACCESS_TOKEN = "google_access_token"
        private const val KEY_GOOGLE_TOKEN_EXPIRES_AT = "google_token_expires_at"

        // 12.17: PlayStation Network tokens (RFC-009). The NPSSO itself is never stored.
        private const val KEY_PSN_ACCESS = "psn_access_token"
        private const val KEY_PSN_ACCESS_EXPIRES_AT = "psn_access_expires_at"
        private const val KEY_PSN_REFRESH = "psn_refresh_token"
        private const val KEY_PSN_REFRESH_EXPIRES_AT = "psn_refresh_expires_at"
    }

    // Everything Keystore-backed is created on FIRST USE, never at injection. SecretManager is
    // injected into MainActivity and the start screen's graph — on the main thread at cold start —
    // where building the MasterKey (and running the legacy migration) blocked the first frame.
    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val encryptedPrefs: SharedPreferences by lazy {
        val prefs = try {
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
        migrateFromLegacy(prefs)
        prefs
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

    /**
     * V2.0 SECURITY MIGRATION
     * Checks for the existence of the legacy unencrypted file.
     * If found, reads the token, executes a secure save, and WANTONLY DESTROYS the old file.
     *
     * Writes straight into [target]: this runs while [encryptedPrefs] is being initialized, so going
     * through the public setters would re-enter that lazy.
     */
    private fun migrateFromLegacy(target: SharedPreferences) {
        // We check if the file exists on disk to avoid creating an empty one by accessing it
        val legacyFile = File(context.filesDir.parent, "shared_prefs/$LEGACY_PREFS_NAME.xml")
        if (!legacyFile.exists()) return

        Log.i(
            "SecretManager",
            "Legacy unencrypted credentials found. Initiating migration protocol."
        )

        val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val token = legacyPrefs.getString(KEY_TOKEN, null)
        val owner = legacyPrefs.getString(KEY_REPO_OWNER, null)
        val repo = legacyPrefs.getString(KEY_REPO_NAME, null)

        // Persisted synchronously BEFORE the legacy copy is destroyed.
        target.edit(commit = true) {
            if (!token.isNullOrEmpty()) putString(KEY_TOKEN, token)
            if (!owner.isNullOrEmpty() && !repo.isNullOrEmpty()) {
                putString(KEY_REPO_OWNER, owner)
                putString(KEY_REPO_NAME, repo)
            }
        }
        if (!token.isNullOrEmpty()) Log.d("SecretManager", "Token migrated to Vault.")

        // WIPE AND DESTROY
        legacyPrefs.edit(commit = true) { clear() } // Clear content
        // The file itself might remain but be empty. In strict environments, we might want to delete the file.
        if (legacyFile.delete()) {
            Log.i("SecretManager", "Legacy file incinerated.")
        } else {
            Log.w("SecretManager", "Failed to delete legacy file path.")
        }
    }

    fun saveToken(token: String) {
        encryptedPrefs.edit { putString(KEY_TOKEN, token) }
    }

    fun getToken(): String? {
        return encryptedPrefs.getString(KEY_TOKEN, null)
    }

    fun saveRepoInfo(owner: String, repo: String) {
        encryptedPrefs.edit {
            putString(KEY_REPO_OWNER, owner)
            putString(KEY_REPO_NAME, repo)
        }
    }

    fun saveProvider(type: String) {
        encryptedPrefs.edit { putString(KEY_PROVIDER, type) }
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
        encryptedPrefs.edit { clear() }
    }

    // --- The private vault's password (12.18, V3) ----------------------------
    // Never stored readable again: only wrapped by a key a strong biometric opens
    // (data/privatevault/VaultPasswordBinding). Settled decision #21.

    /** The readable copy 12.17 kept. Only VaultPasswordBinding's migration reads it. */
    fun legacyVaultPassword(): String? = encryptedPrefs.getString(KEY_VAULT_PASSWORD, null)

    fun clearLegacyVaultPassword() {
        encryptedPrefs.edit { remove(KEY_VAULT_PASSWORD) }
    }

    fun saveWrappedVaultPassword(wrapped: ByteArray) {
        encryptedPrefs.edit { putString(KEY_WRAPPED_VAULT_PASSWORD, java.util.Base64.getEncoder().encodeToString(wrapped)) }
    }

    fun wrappedVaultPassword(): ByteArray? = encryptedPrefs.getString(KEY_WRAPPED_VAULT_PASSWORD, null)?.let {
        runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull()
    }

    fun clearWrappedVaultPassword() {
        encryptedPrefs.edit { remove(KEY_WRAPPED_VAULT_PASSWORD) }
    }

    // --- V6: Google ecosystem credentials -------------------------------------

    fun saveGoogleEmail(email: String) {
        encryptedPrefs.edit { putString(KEY_GOOGLE_EMAIL, email) }
    }

    fun getGoogleEmail(): String? = encryptedPrefs.getString(KEY_GOOGLE_EMAIL, null)

    fun saveGoogleAccessToken(token: String, expiresAtMillis: Long) {
        encryptedPrefs.edit {
            putString(KEY_GOOGLE_ACCESS_TOKEN, token)
            putLong(KEY_GOOGLE_TOKEN_EXPIRES_AT, expiresAtMillis)
        }
    }

    fun getGoogleAccessToken(): Pair<String, Long>? {
        val token = encryptedPrefs.getString(KEY_GOOGLE_ACCESS_TOKEN, null) ?: return null
        val expiresAt = encryptedPrefs.getLong(KEY_GOOGLE_TOKEN_EXPIRES_AT, 0L)
        return token to expiresAt
    }

    fun clearGoogleAuth() {
        encryptedPrefs.edit {
            remove(KEY_GOOGLE_EMAIL)
            remove(KEY_GOOGLE_ACCESS_TOKEN)
            remove(KEY_GOOGLE_TOKEN_EXPIRES_AT)
        }
    }

    /**
     * Clears ONLY the cached access token + expiry, preserving the linked email.
     * Used by GoogleAuthRepository.invalidateAccessToken so a 401 from the
     * Google APIs forces the next [getValidAccessToken] call to re-authorize
     * silently via AuthorizationClient, without un-linking the account.
     */
    fun clearGoogleAccessToken() {
        encryptedPrefs.edit {
            remove(KEY_GOOGLE_ACCESS_TOKEN)
            remove(KEY_GOOGLE_TOKEN_EXPIRES_AT)
        }
    }

    // --- 12.17: PlayStation Network (RFC-009) ----------------------------------

    fun savePsnTokens(tokens: cloud.wafflecommons.pixelbrainreader.data.games.psn.PsnTokens) {
        encryptedPrefs.edit {
            putString(KEY_PSN_ACCESS, tokens.access)
            putLong(KEY_PSN_ACCESS_EXPIRES_AT, tokens.accessExpiresAt)
            putString(KEY_PSN_REFRESH, tokens.refresh)
            putLong(KEY_PSN_REFRESH_EXPIRES_AT, tokens.refreshExpiresAt)
        }
    }

    fun getPsnTokens(): cloud.wafflecommons.pixelbrainreader.data.games.psn.PsnTokens? {
        val access = encryptedPrefs.getString(KEY_PSN_ACCESS, null) ?: return null
        val refresh = encryptedPrefs.getString(KEY_PSN_REFRESH, null) ?: return null
        return cloud.wafflecommons.pixelbrainreader.data.games.psn.PsnTokens(
            access = access,
            accessExpiresAt = encryptedPrefs.getLong(KEY_PSN_ACCESS_EXPIRES_AT, 0L),
            refresh = refresh,
            refreshExpiresAt = encryptedPrefs.getLong(KEY_PSN_REFRESH_EXPIRES_AT, 0L)
        )
    }

    fun clearPsnTokens() {
        encryptedPrefs.edit {
            remove(KEY_PSN_ACCESS)
            remove(KEY_PSN_ACCESS_EXPIRES_AT)
            remove(KEY_PSN_REFRESH)
            remove(KEY_PSN_REFRESH_EXPIRES_AT)
        }
    }
}
