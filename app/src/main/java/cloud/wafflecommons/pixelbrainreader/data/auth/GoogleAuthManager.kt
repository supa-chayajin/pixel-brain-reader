package cloud.wafflecommons.pixelbrainreader.data.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import cloud.wafflecommons.pixelbrainreader.R
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.api.services.calendar.CalendarScopes
import com.google.api.services.tasks.TasksScopes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GoogleAuthV2"

// Google OAuth issues ~1h access tokens. We treat them as valid for 55 min and
// refresh 1 min before that, leaving headroom for clock skew between device & Google.
private const val ACCESS_TOKEN_TTL_MS = 55L * 60L * 1000L
private const val TOKEN_SKEW_MS = 60L * 1000L

/**
 * Modern Google auth, two-step:
 *
 *  1. **Identity** via [androidx.credentials.CredentialManager] + [GetGoogleIdOption].
 *     Returns the user's email; persisted to [SecretManager].
 *  2. **Authorization** via [Identity.getAuthorizationClient] requesting Calendar +
 *     Tasks scopes. If the user has previously consented to these scopes for our
 *     Web Client ID, [AuthorizationRequest] returns silently with a fresh access
 *     token — this is the silent-refresh mechanism on mobile (no client_secret,
 *     no backend exchange). Otherwise it returns an [IntentSender] the UI must
 *     launch; the resolved Intent is handed back via [completeAuthorization].
 */
@Singleton
class GoogleAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secretManager: SecretManager
) {
    private val webClientId: String get() = context.getString(R.string.google_client_id)
    private val credentialManager: CredentialManager by lazy { CredentialManager.create(context) }
    private val authClient by lazy { Identity.getAuthorizationClient(context) }

    private val _isAccountLinked = MutableStateFlow(secretManager.getGoogleEmail() != null)
    val isAccountLinked: StateFlow<Boolean> = _isAccountLinked.asStateFlow()

    // --- Step 1: Identity (Credential Manager) -------------------------------

    /**
     * Shows the Google account picker. Requires an Activity because Credential
     * Manager draws UI bound to the host window.
     */
    suspend fun signIn(activity: Activity): Result<String> {
        return try {
            val option = GetGoogleIdOption.Builder()
                .setServerClientId(webClientId)
                .setFilterByAuthorizedAccounts(false) // surface all eligible accounts
                .setAutoSelectEnabled(false)
                .build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val response = credentialManager.getCredential(activity, request)
            val cred = response.credential
            if (cred !is CustomCredential ||
                cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                return Result.failure(
                    IllegalStateException("Unexpected credential type: ${cred::class.java.name}")
                )
            }
            val googleCred = GoogleIdTokenCredential.createFrom(cred.data)
            secretManager.saveGoogleEmail(googleCred.id)
            _isAccountLinked.value = true
            Log.i(TAG, "Identity acquired for ${googleCred.id}")
            Result.success(googleCred.id)
        } catch (e: GetCredentialException) {
            Log.e(TAG, "Credential Manager failure: ${e.type}", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Sign-in failure", e)
            Result.failure(e)
        }
    }

    // --- Step 2: Authorization (Calendar + Tasks scopes) ---------------------

    sealed class AuthorizationOutcome {
        data class Authorized(val accessToken: String, val expiresAtMillis: Long) : AuthorizationOutcome()
        data class NeedsUserConsent(val intentSender: IntentSender) : AuthorizationOutcome()
    }

    suspend fun authorize(): Result<AuthorizationOutcome> {
        return try {
            val request = AuthorizationRequest.Builder()
                .setRequestedScopes(
                    listOf(
                        Scope(CalendarScopes.CALENDAR_READONLY),
                        Scope(TasksScopes.TASKS_READONLY)
                    )
                )
                .build()
            val result = authClient.authorize(request).await()
            val token = result.accessToken
            if (token != null) {
                val expiresAt = System.currentTimeMillis() + ACCESS_TOKEN_TTL_MS
                secretManager.saveGoogleAccessToken(token, expiresAt)
                Result.success(AuthorizationOutcome.Authorized(token, expiresAt))
            } else {
                val pending = result.pendingIntent
                    ?: return Result.failure(
                        IllegalStateException("Authorize returned neither token nor consent intent")
                    )
                Result.success(AuthorizationOutcome.NeedsUserConsent(pending.intentSender))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Authorization failure", e)
            Result.failure(e)
        }
    }

    /** Resolve the consent intent that [authorize] surfaced via [AuthorizationOutcome.NeedsUserConsent]. */
    fun completeAuthorization(data: Intent?): Result<String> {
        return try {
            val result = authClient.getAuthorizationResultFromIntent(data)
            val token = result.accessToken
                ?: return Result.failure(
                    IllegalStateException("No access token after user consent")
                )
            val expiresAt = System.currentTimeMillis() + ACCESS_TOKEN_TTL_MS
            secretManager.saveGoogleAccessToken(token, expiresAt)
            Result.success(token)
        } catch (e: Exception) {
            Log.e(TAG, "Authorization resolution failure", e)
            Result.failure(e)
        }
    }

    // --- Background-safe accessor --------------------------------------------

    /**
     * Returns a valid access token, refreshing silently via [authorize] when
     * the cached one is near expiry. Returns null when a fresh UI consent is
     * required — background callers (SyncOrchestrator, workers) must skip
     * silently in that case; the UI re-triggers consent on next foreground.
     */
    suspend fun getValidAccessToken(): String? {
        val cached = secretManager.getGoogleAccessToken() ?: return null
        val (token, expiresAt) = cached
        if (System.currentTimeMillis() < expiresAt - TOKEN_SKEW_MS) return token
        return when (val outcome = authorize().getOrNull()) {
            is AuthorizationOutcome.Authorized -> outcome.accessToken
            else -> null
        }
    }

    /**
     * Clears local credential state. Note: the AuthorizationClient API has no
     * signOut() — token revocation is server-side via the Google account
     * connections page. We clear our cached token + the Credential Manager
     * state so the next sign-in re-prompts the account chooser.
     */
    suspend fun signOut() {
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            Log.w(TAG, "CredentialManager clear warning", e)
        }
        secretManager.clearGoogleAuth()
        _isAccountLinked.value = false
    }

    fun setAccountLinked(linked: Boolean) {
        _isAccountLinked.value = linked
    }
}
