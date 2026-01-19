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
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.repository.AppThemeConfig
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.ui.login.LoginScreen
import cloud.wafflecommons.pixelbrainreader.ui.main.MainScreen
import cloud.wafflecommons.pixelbrainreader.ui.main.MainViewModel
import cloud.wafflecommons.pixelbrainreader.ui.theme.PixelBrainReaderTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var secretManager: SecretManager

    @Inject
    lateinit var userPrefs: UserPreferencesRepository

    private val viewModel: MainViewModel by viewModels()

    // State for UI
    private var isUserLoggedIn by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. Synchronous Initialization logic
        isUserLoggedIn = secretManager.getToken() != null
        
        enableEdgeToEdge()

        // Privacy Curtain (SecOps) - Kept as good practice even without bio, but removing Flag Secure might be desired if bio is gone? 
        // User asked to remove Biometric Authentication. Privacy Curtain (FLAG_SECURE) prevents screenshots.
        // It wasn't explicitly asked to be removed, but often goes hand in hand. 
        // I will keep it for now as "Security" != "Biometric Auth". But likely user wants standard app behavior.
        // Re-reading prompt: "COMPLETELY REMOVE the Biometric Authentication feature... This includes the Locked Screen..."
        // I will keep FLAG_SECURE only if !DEBUG, as it was.

        if (!BuildConfig.DEBUG) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        setContent {
            // Observe the theme from the repository directly
            val themeConfig by userPrefs.themeConfig.collectAsState(initial = AppThemeConfig.FOLLOW_SYSTEM)
            
            val useDarkTheme = when (themeConfig) {
                AppThemeConfig.DARK -> true
                AppThemeConfig.LIGHT -> false
                AppThemeConfig.FOLLOW_SYSTEM -> isSystemInDarkTheme()
            }
            
            PixelBrainReaderTheme(darkTheme = useDarkTheme) {
                if (isUserLoggedIn) {
                    MainScreen(
                        viewModel = viewModel,
                        onLogout = {
                            isUserLoggedIn = false
                            secretManager.clear()
                        },
                        onExitApp = {
                            finishAffinity()
                        }
                    )
                } else {
                    LoginScreen(onLoginSuccess = {
                        isUserLoggedIn = true
                    })
                }
            }
        }

        handleIntent(intent)
        
        if (savedInstanceState == null && isUserLoggedIn) {
            viewModel.performInitialSync()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        
        // Share Intent
        if (intent.action == Intent.ACTION_SEND) {
            viewModel.handleShareIntent(intent)
        }
        
        // Deep Link (pixelbrain://import?url=...)
        if (intent.action == Intent.ACTION_VIEW && intent.scheme == "pixelbrain" && intent.data?.host == "import") {
             val url = intent.data?.getQueryParameter("url")
             if (!url.isNullOrBlank()) {
                 val workRequest = androidx.work.OneTimeWorkRequestBuilder<cloud.wafflecommons.pixelbrainreader.data.workers.ImportWorker>()
                     .setInputData(androidx.work.workDataOf("url" to url))
                     .build()
                 androidx.work.WorkManager.getInstance(this).enqueue(workRequest)
                 
                 android.widget.Toast.makeText(this, "Importing Article...", android.widget.Toast.LENGTH_SHORT).show()
             }
        }
    }
}
