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
import androidx.activity.viewModels
import android.content.Intent
import cloud.wafflecommons.pixelbrainreader.ui.main.MainViewModel

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    // Changed to FragmentActivity for BiometricPrompt compatibility

    @Inject
    lateinit var secretManager: SecretManager

    @Inject
    lateinit var biometricAuthenticator: BiometricAuthenticator

    private val viewModel: MainViewModel by viewModels()

    // State for UI
    private var isAuthenticated by mutableStateOf(false)
    private var isUserLoggedIn by mutableStateOf(false)

    // Background Timer
    private var lastBackgroundTimeStamp: Long = 0L
    private val LOCK_TIMEOUT_MS = 60 * 1000L // 1 Minute

    // Concurrency Guards
    private var isAuthInProgress = false
    // private var hasAutoPromptedSession = false // Removed as we only prompt on creation or manual request

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Privacy Curtain (SecOps)
        // Prevents screenshots and recents preview (Release only)
        if (!BuildConfig.DEBUG) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        // Initial Login Check
        isUserLoggedIn = secretManager.getToken() != null
        
        // If logged in, we start as NOT authenticated (Locked)
        isAuthenticated = !isUserLoggedIn 

        // Setup Lifecycle Observer for background detection
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                lastBackgroundTimeStamp = System.currentTimeMillis()
            }

            override fun onStart(owner: LifecycleOwner) {
                if (isUserLoggedIn && lastBackgroundTimeStamp > 0) {
                    val timeInBackground = System.currentTimeMillis() - lastBackgroundTimeStamp
                    if (timeInBackground > LOCK_TIMEOUT_MS) {
                        isAuthenticated = false // Lock App
                    }
                }
            }
        })

        setContent {
            PixelBrainReaderTheme {
                if (isUserLoggedIn && !isAuthenticated) {
                    cloud.wafflecommons.pixelbrainreader.ui.login.LockedScreen(
                        // Manual Click always triggers
                        onUnlockClick = { 
                            triggerBiometrics() 
                        }
                    )
                } else if (isUserLoggedIn && isAuthenticated) {
                    cloud.wafflecommons.pixelbrainreader.ui.main.MainScreen(
                        viewModel = viewModel,
                        onLogout = {
                            isUserLoggedIn = false
                            isAuthenticated = false
                            secretManager.clear()
                        },
                        onExitApp = {
                            finishAffinity() // Closes App & Task
                        }
                    )
                } else {
                    LoginScreen(onLoginSuccess = {
                        isUserLoggedIn = true
                        isAuthenticated = true // Fresh login is authenticated
                    })
                }
            }
        }

        
        // Handle Share Intent
        viewModel.handleShareIntent(intent)

        // Trigger Biometrics ONCE at creation if locked
        if (isUserLoggedIn && !isAuthenticated) {
            triggerBiometrics()
        }

        // COLD START SYNC:
        // Only trigger initial sync if this is a fresh start (savedInstanceState == null)
        // and user is logged in. 
        if (savedInstanceState == null && isUserLoggedIn) {
            viewModel.performInitialSync()
        }
    }

    override fun onResume() {
        super.onResume()
        // Removed aggressive auto-trigger loop. 
        // Authentication is now triggered in onCreate or via manual 'Unlock' button.
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        viewModel.handleShareIntent(intent)
    }

    private fun triggerBiometrics() {
        if (isAuthInProgress) return // Prevent parallel calls (Fix Binder Crash)
        
        isAuthInProgress = true
        
        biometricAuthenticator.prompt(
            activity = this,
            onSuccess = { 
                isAuthenticated = true 
                isAuthInProgress = false
            },
            onError = { _ -> 
                // User cancelled or error. Stay on LockedScreen.
                isAuthInProgress = false
            },
            onFailure = { 
                // Transient failure. Stay on LockedScreen.
                // Prompt is still active? Usually yes for onFailure (wrong finger).
                // Wait, if prompt is still active, we are technically still in progress.
                // But onFailure callback in Authenticator typically means "Try again", the prompt stays up.
                // So we do NOT reset isAuthInProgress here.
                // However, my BiometricAuthenticator implementation calls onFailure() for transient errors.
                // If the prompt DISMISED, it would call onError.
                // So keeping isAuthInProgress = true is correct here.
            }
        )
    }
}
