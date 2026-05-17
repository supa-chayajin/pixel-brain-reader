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
import androidx.credentials.exceptions.NoCredentialException
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
class GoogleAuthRepository @Inject constructor(
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
     *
     * Two-step pattern (matches Google's official samples):
     *  1. filterByAuthorizedAccounts = true → silent return if the user has
     *     previously authorized this app's Web Client ID with a Google account.
     *  2. On [NoCredentialException], retry with filter = false to surface the
     *     full picker for first-time sign-in.
     *
     * If both steps return [NoCredentialException], the device genuinely has
     * no Google account configured at the OS level — surface an actionable
     * error so the user knows to add one in Settings → Accounts.
     */
    suspend fun signIn(activity: Activity): Result<String> {
        return try {
            tryGetCredential(activity, filterAuthorized = true)
        } catch (silent: NoCredentialException) {
            Log.i(TAG, "Silent attempt returned NoCredentialException; showing full picker", silent)
            try {
                tryGetCredential(activity, filterAuthorized = false)
            } catch (full: NoCredentialException) {
                // NoCredentialException with filter=false on a device that HAS Google accounts
                // almost always indicates Cloud Console misconfiguration:
                //  - Web Client ID in strings.xml doesn't match a Web OAuth client in the project
                //  - Android OAuth client missing this app's package + SHA-1
                //  - Web and Android clients live in different Cloud projects
                // Run `./gradlew signingReport` and verify against Cloud Console → Credentials.
                Log.e(TAG, "Credential Manager refused with filter=false. " +
                        "Type=${full.type}; errorMessage=${full.errorMessage}", full)
                Result.failure(
                    IllegalStateException(
                        "Credential Manager refused (${full.type}). " +
                        "Likely cause: Web Client ID or SHA-1 not registered in Cloud Console."
                    )
                )
            } catch (e: GetCredentialException) {
                Log.e(TAG, "Credential Manager failure (full): type=${e.type}; msg=${e.errorMessage}", e)
                Result.failure(e)
            }
        } catch (e: GetCredentialException) {
            Log.e(TAG, "Credential Manager failure (silent): type=${e.type}; msg=${e.errorMessage}", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Sign-in failure", e)
            Result.failure(e)
        }
    }

    private suspend fun tryGetCredential(
        activity: Activity,
        filterAuthorized: Boolean
    ): Result<String> {
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(webClientId)
            .setFilterByAuthorizedAccounts(filterAuthorized)
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
        Log.i(TAG, "Identity acquired for ${googleCred.id} (filtered=$filterAuthorized)")
        return Result.success(googleCred.id)
    }

    // --- Step 2: Authorization (Calendar + Tasks scopes) ---------------------

    sealed class AuthorizationOutcome {
        data class Authorized(val accessToken: String, val expiresAtMillis: Long) : AuthorizationOutcome()
        data class NeedsUserConsent(val intentSender: IntentSender) : AuthorizationOutcome()
    }

    suspend fun authorize(): Result<AuthorizationOutcome> {
        return try {
            // V6 bidirectional sync needs read+write scopes.
            // Users who consented to *_READONLY in V5 will be re-prompted for the wider scope.
            val request = AuthorizationRequest.Builder()
                .setRequestedScopes(
                    listOf(
                        Scope(CalendarScopes.CALENDAR),
                        Scope(TasksScopes.TASKS)
                    )
                )
                .build()
            val result = authClient.authorize(request).await()
            val token = result.accessToken
            if (token != null) {
                // Opportunistically capture the email from the authorized account
                // so we don't depend on the Credential Manager identity step at all.
                rememberEmailIfPresent(result)
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

    /**
     * AuthorizationResult.toGoogleSignInAccount() is deprecated but remains the
     * supported way to read the email of the account the user picked during
     * authorize(). Lets us skip the Credential Manager identity step when it
     * refuses with TYPE_NO_CREDENTIAL (misconfigured OAuth consent screen,
     * test-mode without the user as a test user, etc.).
     */
    @Suppress("DEPRECATION")
    private fun rememberEmailIfPresent(
        result: com.google.android.gms.auth.api.identity.AuthorizationResult
    ) {
        val email = result.toGoogleSignInAccount()?.email
        if (!email.isNullOrBlank() && secretManager.getGoogleEmail() != email) {
            secretManager.saveGoogleEmail(email)
            _isAccountLinked.value = true
            Log.i(TAG, "Captured email from AuthorizationResult: $email")
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
            rememberEmailIfPresent(result)
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
     * Forgets the cached access token (keeps the linked email + scopes). Use
     * after a 401 from a Google API to force the next [getValidAccessToken]
     * call to re-authorize via AuthorizationClient. Silent — the user does not
     * see UI unless consent was revoked server-side.
     */
    fun invalidateAccessToken() {
        secretManager.clearGoogleAccessToken()
    }

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
