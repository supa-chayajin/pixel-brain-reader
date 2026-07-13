package cloud.wafflecommons.pixelbrainreader

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.repository.AppThemeConfig
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.ui.login.LoginScreen
import cloud.wafflecommons.pixelbrainreader.ui.main.MainScreen
import cloud.wafflecommons.pixelbrainreader.ui.main.MainViewModel
import cloud.wafflecommons.pixelbrainreader.ui.theme.PixelBrainReaderTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var secretManager: SecretManager

    @Inject
    lateinit var userPrefs: UserPreferencesRepository

    private val viewModel: MainViewModel by viewModels()

    /**
     * Tri-state login resolution. Null = still resolving (first
     * EncryptedSharedPreferences access takes 50-200 ms on cold start —
     * we don't want to block the main thread for that).
     */
    private var loginState by mutableStateOf<Boolean?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        if (!BuildConfig.DEBUG) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        setContent {
            val themeConfig by userPrefs.themeConfig.collectAsStateWithLifecycle(initialValue = AppThemeConfig.FOLLOW_SYSTEM)

            val useDarkTheme = when (themeConfig) {
                AppThemeConfig.DARK -> true
                AppThemeConfig.LIGHT -> false
                AppThemeConfig.FOLLOW_SYSTEM -> isSystemInDarkTheme()
            }

            // Resolve login state off the main thread. Keystore-backed
            // EncryptedSharedPreferences first-access is non-trivial; doing
            // it on the main thread blocks the first frame.
            LaunchedEffect(Unit) {
                if (loginState == null) {
                    val hasToken = withContext(Dispatchers.IO) {
                        secretManager.getToken() != null
                    }
                    loginState = hasToken
                }
            }

            PixelBrainReaderTheme(darkTheme = useDarkTheme) {
                when (val state = loginState) {
                    null -> SplashScreen()
                    true -> MainScreen(
                        viewModel = viewModel,
                        onLogout = {
                            loginState = false
                            secretManager.clear()
                        },
                        onExitApp = { finishAffinity() }
                    )
                    false -> LoginScreen(onLoginSuccess = { loginState = true })
                }
            }
        }

        handleIntent(intent)
    }

    @androidx.compose.runtime.Composable
    private fun SplashScreen() {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
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
             // ImportWorker fetches this URL server-side (Jsoup), so only accept
             // http(s) targets — reject file://, content://, javascript:, etc. so
             // the public deep link can't become an arbitrary-URL fetch primitive.
             if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                 // Wait for connectivity rather than failing the import when offline.
                 val constraints = androidx.work.Constraints.Builder()
                     .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                     .build()
                 val workRequest = androidx.work.OneTimeWorkRequestBuilder<cloud.wafflecommons.pixelbrainreader.data.workers.ImportWorker>()
                     .setInputData(androidx.work.workDataOf("url" to url))
                     .setConstraints(constraints)
                     .build()
                 androidx.work.WorkManager.getInstance(this).enqueue(workRequest)

                 android.widget.Toast.makeText(this, "Importing Article...", android.widget.Toast.LENGTH_SHORT).show()
             }
        }
    }
}
