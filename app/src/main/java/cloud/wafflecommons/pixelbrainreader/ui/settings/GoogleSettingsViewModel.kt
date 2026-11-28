package cloud.wafflecommons.pixelbrainreader.ui.settings

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.BuildConfig
import cloud.wafflecommons.pixelbrainreader.data.auth.GoogleAuthRepository
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings › Connections › Google Calendar & Tasks (13.4, S-b): the account link (Credential Manager,
 * then AuthorizationClient and its consent) for the read-only import (GoogleScopes.READ_ONLY).
 */
@HiltViewModel
class GoogleSettingsViewModel @Inject constructor(
    private val googleAuthManager: GoogleAuthRepository,
    private val preferences: UserPreferencesRepository
) : ViewModel() {

    /** What the screen does for the link: launch the consent, or say how it went. */
    sealed class GoogleAuthEvent {
        data class ConsentRequired(val intentSender: IntentSender) : GoogleAuthEvent()
        data class Failed(val message: String) : GoogleAuthEvent()
        data object Linked : GoogleAuthEvent()
    }

    /** The import is on (the sync's Google phase runs). */
    val enabled: StateFlow<Boolean> = preferences.isGoogleSyncEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** An account is linked. */
    val linked: StateFlow<Boolean> = googleAuthManager.isAccountLinked

    // Activity-scoped steps are events: the screen launches them and hands the result back.
    private val _events = MutableSharedFlow<GoogleAuthEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<GoogleAuthEvent> = _events.asSharedFlow()

    fun connect(activity: Activity) {
        // Nothing is imported on a read-only device: the switch is off there.
        if (VaultWriteGate.readOnly) return
        viewModelScope.launch {
            // Credential Manager's identity is best-effort. When it refuses (TYPE_NO_CREDENTIAL where
            // the OAuth consent screen or the SHA-1 isn't fully wired), AuthorizationClient follows:
            // another GMS path, with its own account picker in the consent intent.
            val signIn = googleAuthManager.signIn(activity)
            if (signIn.isFailure) {
                val error = signIn.exceptionOrNull()
                Log.w(TAG, "Credential Manager sign-in failed (${error?.javaClass?.simpleName}); trying AuthorizationClient")
                if (BuildConfig.DEBUG) Log.d(TAG, "Credential Manager sign-in failed", error)
            }
            when (val outcome = googleAuthManager.authorize().getOrNull()) {
                is GoogleAuthRepository.AuthorizationOutcome.Authorized -> {
                    preferences.setGoogleSyncEnabled(true)
                    _events.emit(GoogleAuthEvent.Linked)
                }
                is GoogleAuthRepository.AuthorizationOutcome.NeedsUserConsent ->
                    _events.emit(GoogleAuthEvent.ConsentRequired(outcome.intentSender))
                null -> {
                    // Both failed: Credential Manager's error is the more specific one, when there is one.
                    val message = signIn.exceptionOrNull()?.message
                        ?: "Google connection failed; check Cloud Console config"
                    _events.emit(GoogleAuthEvent.Failed(message))
                }
            }
        }
    }

    fun onConsentResolved(data: Intent?) {
        viewModelScope.launch {
            val result = googleAuthManager.completeAuthorization(data)
            if (result.isSuccess) {
                preferences.setGoogleSyncEnabled(true)
                _events.emit(GoogleAuthEvent.Linked)
            } else {
                _events.emit(GoogleAuthEvent.Failed(result.exceptionOrNull()?.message ?: "Consent failed"))
            }
        }
    }

    /** The import off, and the account forgotten. */
    fun disconnect() {
        viewModelScope.launch {
            preferences.setGoogleSyncEnabled(false)
            googleAuthManager.signOut()
        }
    }

    private companion object {
        const val TAG = "GoogleSettingsViewModel"
    }
}
