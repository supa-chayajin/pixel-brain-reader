package cloud.wafflecommons.pixelbrainreader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LoadingIndicator
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

    /** A deep-linked import URL awaiting explicit user confirmation (null = none pending). */
    private var pendingImportUrl by mutableStateOf<String?>(null)

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

            // Ask for POST_NOTIFICATIONS once the user is in (API 33+). Reminders
            // degrade gracefully if denied — NotificationHelper checks the grant
            // before every post.
            val notifPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { /* result ignored */ }
            LaunchedEffect(loginState) {
                if (loginState == true &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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

                // Untrusted deep-link import: confirm with the user (showing the source
                // host) before fetching + writing + pushing a note. Surfaced only once
                // logged in so it always overlays the main UI.
                if (loginState == true) {
                    pendingImportUrl?.let { url ->
                        val host = remember(url) { android.net.Uri.parse(url).host ?: url }
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { pendingImportUrl = null },
                            title = { androidx.compose.material3.Text("Importer un article ?") },
                            text = { androidx.compose.material3.Text("Depuis : $host") },
                            confirmButton = {
                                androidx.compose.material3.TextButton(onClick = {
                                    enqueueImport(url)
                                    pendingImportUrl = null
                                }) { androidx.compose.material3.Text("Importer") }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(onClick = { pendingImportUrl = null }) {
                                    androidx.compose.material3.Text("Annuler")
                                }
                            }
                        )
                    }
                }
            }
        }

        handleIntent(intent)
    }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
    @androidx.compose.runtime.Composable
    private fun SplashScreen() {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator()
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
        
        // Deep Link (pixelbrain://import?url=...). This is a PUBLIC, BROWSABLE entry point
        // (any web page can fire it), so it must be treated as untrusted:
        //  - validate the URL (http/https, public host, bounded length) to stop it being an
        //    SSRF / arbitrary-fetch primitive into the LAN or cloud metadata endpoints, and
        //  - require explicit user confirmation before importing + pushing a note, so a
        //    tapped link can't silently write to the vault.
        if (intent.action == Intent.ACTION_VIEW && intent.scheme == "pixelbrain" && intent.data?.host == "import") {
             val url = try { intent.data?.getQueryParameter("url") } catch (e: Exception) { null }
             if (url != null && isSafeImportUrl(url)) {
                 pendingImportUrl = url
             }
        }
    }

    /** Delegates to the pure, unit-tested [ImportUrlValidator]. */
    private fun isSafeImportUrl(raw: String): Boolean =
        cloud.wafflecommons.pixelbrainreader.data.utils.ImportUrlValidator.isSafe(raw)

    /** Enqueue the confirmed import. Kept separate from validation so the dialog gates it. */
    private fun enqueueImport(url: String) {
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
