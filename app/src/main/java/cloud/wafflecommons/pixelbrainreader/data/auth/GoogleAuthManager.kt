package cloud.wafflecommons.pixelbrainreader.data.auth

import android.content.Context
import android.content.Intent
import android.util.Log
import cloud.wafflecommons.pixelbrainreader.R
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.services.calendar.CalendarScopes
import com.google.api.services.tasks.TasksScopes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GoogleAuthDebug"

@Singleton
class GoogleAuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        // IMPORTANT: The clientId MUST be the "Web client" ID from the Google Cloud Console,
        // NOT the "Android client" ID. Using the Android ID causes ApiException code 10.
        .requestIdToken(context.getString(R.string.google_client_id))
        .requestScopes(
            Scope(CalendarScopes.CALENDAR_READONLY),
            Scope(TasksScopes.TASKS_READONLY)
        )
        .build()

    private val googleSignInClient: GoogleSignInClient = GoogleSignIn.getClient(context, gso)

    private val _isAccountLinked = MutableStateFlow(false)
    val isAccountLinked: StateFlow<Boolean> = _isAccountLinked.asStateFlow()

    fun getSignInIntent(): Intent {
        return googleSignInClient.signInIntent
    }

    fun handleSignInResult(intent: Intent?): Result<GoogleSignInAccount> {
        if (intent == null) {
            return Result.failure(Exception("Sign-In intent is null"))
        }
        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(intent)
            val account = task.getResult(ApiException::class.java)
            if (account != null) {
                _isAccountLinked.value = true
                Result.success(account)
            } else {
                Result.failure(Exception("Google Account is null"))
            }
        } catch (e: ApiException) {
            val message = when (e.statusCode) {
                10 -> "DEVELOPER_ERROR: Check if you used the Web Client ID (not Android) and if SHA-1/Package Name match in Google Cloud Console."
                7 -> "NETWORK_ERROR: Check your internet connection."
                12501 -> "SIGN_IN_CANCELLED: The user cancelled the sign-in."
                else -> "Google Sign-In failed: code=${e.statusCode}"
            }
            Log.e(TAG, message, e)
            _isAccountLinked.value = false
            Result.failure(Exception(message, e))
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during Google Sign-In", e)
            _isAccountLinked.value = false
            Result.failure(e)
        }
    }

    suspend fun signOut() {
        try {
            googleSignInClient.signOut()
            _isAccountLinked.value = false
        } catch (e: Exception) {
            Log.e(TAG, "Sign out error", e)
        }
    }

    fun setAccountLinked(linked: Boolean) {
        _isAccountLinked.value = linked
    }
}
