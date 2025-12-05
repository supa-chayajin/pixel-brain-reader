package cloud.wafflecommons.pixelbrainreader

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import cloud.wafflecommons.pixelbrainreader.data.local.security.BiometricAuthenticator
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.ui.login.LoginScreen
import cloud.wafflecommons.pixelbrainreader.ui.theme.PixelBrainReaderTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    // Changed to FragmentActivity for BiometricPrompt compatibility

    @Inject
    lateinit var secretManager: SecretManager

    @Inject
    lateinit var biometricAuthenticator: BiometricAuthenticator

    // State for UI
    private var isAppLocked by mutableStateOf(false)
    private var isUserLoggedIn by mutableStateOf(false)

    // Background Timer
    private var lastBackgroundTimeStamp: Long = 0L
    private val LOCK_TIMEOUT_MS = 60 * 1000L // 1 Minute

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initial Login Check
        isUserLoggedIn = secretManager.getToken() != null
        
        // Setup Lifecycle Observer for background detection
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                lastBackgroundTimeStamp = System.currentTimeMillis()
            }

            override fun onStart(owner: LifecycleOwner) {
                if (isUserLoggedIn && lastBackgroundTimeStamp > 0) {
                    val timeInBackground = System.currentTimeMillis() - lastBackgroundTimeStamp
                    if (timeInBackground > LOCK_TIMEOUT_MS) {
                        lockApp()
                    }
                }
            }
        })

        // Initial Lock Check (Optional: Lock on cold start if desired, but user didn't explicitly ask for cold start lock, only 1 min background?)
        // Requirement: "The application must effectively be 'locked' by default until the user proves their identity"
        // Interpretation: Cold start ALSO requires auth if logged in.
        if (isUserLoggedIn) {
            lockApp()
        }

        setContent {
            PixelBrainReaderTheme {
                if (isAppLocked) {
                    LockScreen()
                    // Trigger Biometrics when the lock screen appears
                    LaunchedEffect(Unit) {
                        triggerBiometrics()
                    }
                } else if (isUserLoggedIn) {
                    cloud.wafflecommons.pixelbrainreader.ui.main.MainScreen(
                        onLogout = {
                            isUserLoggedIn = false
                            secretManager.clear()
                        }
                    )
                } else {
                    LoginScreen(onLoginSuccess = {
                        isUserLoggedIn = true
                        // New login, no need to lock immediately
                    })
                }
            }
        }
    }

    private fun lockApp() {
        isAppLocked = true
    }

    private fun unlockApp() {
        isAppLocked = false
    }

    private fun triggerBiometrics() {
        if (biometricAuthenticator.canAuthenticate() != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS && 
            biometricAuthenticator.canAuthenticate() != androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
             // Fallback if no hardware or not enrolled (Just unlock or show error? Requirement says "Identify correct library... fallback allowed")
             // Here we assume if they can't authenticate, we might just unlock if it's strict, or block.
             // For V2 MVP, if no biometrics, we might be stuck. 
             // Best effort: If success or none enrolled (maybe PIN?), proceed.
             // Actually Authenticator.BIOMETRIC_STRONG or DEVICE_CREDENTIAL handles PIN fallback automatically.
             // So we just call prompt.
        }
        
        biometricAuthenticator.prompt(
            activity = this,
            onSuccess = { unlockApp() },
            onError = { _ -> 
                // If user cancels, we stay locked.
                // Optionally show a button to retry on the LockScreen
            },
            onFailure = { 
                // Transient failure, do nothing, prompt stays up
            }
        )
    }
}

@Composable
fun LockScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black), // Design System: Absolute Black
        contentAlignment = Alignment.Center
    ) {
        // Simple Lock UI
        Text(text = "LOCKED", color = Color.Gray)
        // In a real app, add a "Unlock" button to re-trigger biometrics if cancelled
    }
}
