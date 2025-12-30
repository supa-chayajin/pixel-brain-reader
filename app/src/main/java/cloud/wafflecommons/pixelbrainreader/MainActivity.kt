package cloud.wafflecommons.pixelbrainreader

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import cloud.wafflecommons.pixelbrainreader.data.local.security.BiometricAuthenticator
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.repository.AppThemeConfig
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.ui.login.LoginScreen
import cloud.wafflecommons.pixelbrainreader.ui.login.LockedScreen
import cloud.wafflecommons.pixelbrainreader.ui.main.MainScreen
import cloud.wafflecommons.pixelbrainreader.ui.main.MainViewModel
import cloud.wafflecommons.pixelbrainreader.ui.theme.PixelBrainReaderTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var secretManager: SecretManager

    @Inject
    lateinit var biometricAuthenticator: BiometricAuthenticator

    @Inject
    lateinit var userPrefs: UserPreferencesRepository

    private val viewModel: MainViewModel by viewModels()

    // State for UI (Refactored to be set before setContent)
    private var isUserLoggedIn by mutableStateOf(false)
    private var isAuthenticated by mutableStateOf(false)

    // Background Timer
    private var lastBackgroundTimeStamp: Long = 0L
    private val LOCK_TIMEOUT_MS = 60 * 1000L // 1 Minute

    // Concurrency Guards
    private var isAuthInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. Synchronous Initialization logic
        isUserLoggedIn = secretManager.getToken() != null
        
        val initialAuthState = if (isUserLoggedIn) {
             val isBioEnabled = runBlocking { userPrefs.isBiometricEnabled.first() }
             !isBioEnabled // Authenticated if biometrics are disabled
        } else {
             false
        }
        
        // Set the state BEFORE setContent to avoid "Locked Screen" flash
        isAuthenticated = initialAuthState

        enableEdgeToEdge()

        // Privacy Curtain (SecOps)
        if (!BuildConfig.DEBUG) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }

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
            // Observe the theme from the repository directly
            val themeConfig by userPrefs.themeConfig.collectAsState(initial = AppThemeConfig.FOLLOW_SYSTEM)
            
            val useDarkTheme = when (themeConfig) {
                AppThemeConfig.DARK -> true
                AppThemeConfig.LIGHT -> false
                AppThemeConfig.FOLLOW_SYSTEM -> isSystemInDarkTheme()
            }
            
            PixelBrainReaderTheme(darkTheme = useDarkTheme) {
                // Use the activity-level states
                if (isUserLoggedIn && !isAuthenticated) {
                    LockedScreen(
                        onUnlockClick = { triggerBiometrics() }
                    )
                } else if (isUserLoggedIn && isAuthenticated) {
                    MainScreen(
                        viewModel = viewModel,
                        onLogout = {
                            isUserLoggedIn = false
                            isAuthenticated = false
                            secretManager.clear()
                        },
                        onExitApp = {
                            finishAffinity()
                        }
                    )
                } else {
                    LoginScreen(onLoginSuccess = {
                        isUserLoggedIn = true
                        isAuthenticated = true
                    })
                }
            }
        }

        viewModel.handleShareIntent(intent)

        // Trigger Biometrics ONCE at creation if locked
        if (isUserLoggedIn && !isAuthenticated) {
            triggerBiometrics()
        }

        if (savedInstanceState == null && isUserLoggedIn) {
            viewModel.performInitialSync()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        viewModel.handleShareIntent(intent)
    }

    private fun triggerBiometrics() {
        if (isAuthInProgress) return
        isAuthInProgress = true
        
        biometricAuthenticator.prompt(
            activity = this,
            onSuccess = { 
                isAuthenticated = true 
                isAuthInProgress = false
            },
            onError = { _ -> 
                isAuthInProgress = false
            },
            onFailure = { 
                // Wait for another attempt or error
            }
        )
    }
}

