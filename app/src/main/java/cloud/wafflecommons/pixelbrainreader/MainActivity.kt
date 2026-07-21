package cloud.wafflecommons.pixelbrainreader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
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
import cloud.wafflecommons.pixelbrainreader.widget.ui.WidgetNav
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

    /**
     * A widget/shortcut navigation request awaiting login. Held here (not in the ViewModel) because
     * the NavHost only exists post-login — [applyPendingIntents] flushes these once loginState==true.
     */
    private var pendingDestination by mutableStateOf<String?>(null)

    /** A widget/shortcut quick-capture request awaiting login (opens the new-note flow on Home). */
    private var pendingCapture by mutableStateOf(false)

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
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            // Flush any pending widget/shortcut navigation once the NavHost exists (post-login).
            LaunchedEffect(loginState, pendingDestination, pendingCapture) {
                if (loginState == true) {
                    pendingDestination?.let { route ->
                        viewModel.requestNavigation(route)
                        pendingDestination = null
                    }
                    if (pendingCapture) {
                        // The new-note dialog only renders on Home; go there, then open it.
                        viewModel.requestNavigation("home")
                        viewModel.openCreateFileDialog()
                        pendingCapture = false
                    }
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
                        val host = remember(url) { url.toUri().host ?: url }
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { pendingImportUrl = null },
                            title = { androidx.compose.material3.Text("Import an article?") },
                            text = { androidx.compose.material3.Text("From: $host") },
                            confirmButton = {
                                androidx.compose.material3.TextButton(onClick = {
                                    enqueueImport(url)
                                    pendingImportUrl = null
                                }) { androidx.compose.material3.Text("Import") }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(onClick = { pendingImportUrl = null }) {
                                    androidx.compose.material3.Text("Cancel")
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
        
        // pixelbrain:// deep links. `import` is a PUBLIC, BROWSABLE entry point (any web page can
        // fire it) so it stays untrusted: validate the URL (SSRF guard) and require explicit
        // confirmation before writing to the vault. `open`/`capture` are fired only by our own
        // widgets/shortcuts via EXPLICIT (component-targeted) intents — not browsable — and are
        // navigation-only, but we still whitelist the screen key before touching the NavHost.
        if (intent.action == Intent.ACTION_VIEW && intent.scheme == WidgetNav.SCHEME) {
            when (intent.data?.host) {
                WidgetNav.HOST_OPEN -> {
                    val route = WidgetNav.screenToRoute(
                        try { intent.data?.getQueryParameter(WidgetNav.QUERY_SCREEN) } catch (e: Exception) { null }
                    )
                    if (route != null) pendingDestination = route
                }
                WidgetNav.HOST_CAPTURE -> pendingCapture = true
                "import" -> {
                    val url = try { intent.data?.getQueryParameter("url") } catch (e: Exception) { null }
                    if (url != null && isSafeImportUrl(url)) pendingImportUrl = url
                }
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
