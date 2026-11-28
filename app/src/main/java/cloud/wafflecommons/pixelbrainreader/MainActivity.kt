package cloud.wafflecommons.pixelbrainreader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.lifecycle.lifecycleScope
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.games.GameArt
import cloud.wafflecommons.pixelbrainreader.data.games.GamePage
import cloud.wafflecommons.pixelbrainreader.data.games.GamesRepository
import cloud.wafflecommons.pixelbrainreader.data.games.pictures.GamePictureSource
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.repository.AppThemeConfig
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.web.ShareImportRepository
import cloud.wafflecommons.pixelbrainreader.data.web.SharedContent
import cloud.wafflecommons.pixelbrainreader.ui.games.COVER_NOT_SAVED
import cloud.wafflecommons.pixelbrainreader.ui.games.coverMessage
import cloud.wafflecommons.pixelbrainreader.ui.login.LoginScreen
import cloud.wafflecommons.pixelbrainreader.ui.main.MainScreen
import cloud.wafflecommons.pixelbrainreader.ui.main.MainViewModel
import cloud.wafflecommons.pixelbrainreader.ui.share.SharedImageChooser
import cloud.wafflecommons.pixelbrainreader.ui.theme.PixelBrainReaderTheme
import cloud.wafflecommons.pixelbrainreader.widget.ui.WidgetNav
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var secretManager: SecretManager

    @Inject
    lateinit var userPrefs: UserPreferencesRepository

    @Inject
    lateinit var shareImportRepository: ShareImportRepository

    @Inject
    lateinit var captureRepository: cloud.wafflecommons.pixelbrainreader.data.repository.CaptureRepository

    @Inject
    lateinit var photoReader: cloud.wafflecommons.pixelbrainreader.data.capture.PhotoReader

    @Inject
    lateinit var privateVaultSession: cloud.wafflecommons.pixelbrainreader.data.privatevault.PrivateVaultSession

    @Inject
    lateinit var gamesRepository: GamesRepository

    @Inject
    lateinit var gamePictures: GamePictureSource

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

    /**
     * An image shared to the app while the owner chooses what it becomes (13.4, P5): a photo note or
     * a game's own picture. Kept across a recreation ([PENDING_SHARED_IMAGE]) until it is kept or
     * dropped, so a theme switch mid-choice offers it again.
     */
    private var pendingSharedImage by mutableStateOf<Uri?>(null)

    /** The game pages the chooser offers for [pendingSharedImage], with their pictures; null while read. */
    private var sharedImageGames by mutableStateOf<SharedImageGames?>(null)

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
                when (loginState) {
                    null -> SplashScreen()
                    true -> {
                        val attachments by viewModel.attachmentIndex.collectAsStateWithLifecycle()
                        val noteNames by viewModel.noteNames.collectAsStateWithLifecycle()
                        androidx.compose.runtime.CompositionLocalProvider(
                            cloud.wafflecommons.pixelbrainreader.ui.utils.LocalVaultAttachments provides attachments,
                            cloud.wafflecommons.pixelbrainreader.ui.utils.LocalNoteNames provides noteNames
                        ) {
                            MainScreen(
                                viewModel = viewModel,
                                onLogout = {
                                    // Clearing the Keystore-backed prefs is disk + crypto work: keep it off
                                    // the main thread, and only show the login screen once it is done.
                                    lifecycleScope.launch {
                                        privateVaultSession.lock()
                                        withContext(Dispatchers.IO) { secretManager.clear() }
                                        loginState = false
                                    }
                                },
                                onExitApp = { finishAffinity() },
                                launchedForShare = intent?.action == Intent.ACTION_SEND
                            )
                        }
                    }
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

                    // 13.4 (P5): a shared image, once the game pages are read: a photo note or a game's picture.
                    val sharedImage = pendingSharedImage
                    val sharedGames = sharedImageGames
                    if (sharedImage != null && sharedGames != null) {
                        SharedImageChooser(
                            pages = sharedGames.pages,
                            arts = sharedGames.arts,
                            onPhotoNote = {
                                dropSharedImage()
                                importSharedPhoto(sharedImage)
                            },
                            onGamePicture = { page -> saveSharedCover(sharedImage, page) },
                            onDismiss = {
                                dropSharedImage()
                                if (intent?.action == Intent.ACTION_SEND) finish()
                            }
                        )
                    }
                }
            }
        }

        // Only a FRESH launch carries a new request. After a recreation (theme or configuration
        // change, process restore) or a relaunch from Recents the same intent comes back, and
        // re-handling it re-imported the shared content and replayed the widget navigation.
        val launchedFromHistory = (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (savedInstanceState == null && !launchedFromHistory) {
            handleIntent(intent)
        }
        // 13.4 (P5): a shared image still waiting for its choice when the activity was recreated.
        savedInstanceState?.getParcelable(PENDING_SHARED_IMAGE, Uri::class.java)?.let { offerSharedImage(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingSharedImage?.let { outState.putParcelable(PENDING_SHARED_IMAGE, it) }
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

    /**
     * An image shared to the app (X2): read on the phone, kept with its note in the inbox. Only from
     * another app's provider (never a file, never one of ours); a launch for the share ends there.
     */
    private fun importSharedPhoto(uri: android.net.Uri?) {
        val launchedForShare = intent?.action == Intent.ACTION_SEND
        if (uri == null || !cloud.wafflecommons.pixelbrainreader.data.capture.PhotoCapture.isForeign(uri.scheme, uri.authority, ownAuthorities())) {
            android.widget.Toast.makeText(this, "That image can't be read", android.widget.Toast.LENGTH_LONG).show()
            if (launchedForShare) finish()
            return
        }
        lifecycleScope.launch {
            val message = try {
                val photo = photoReader.prepare(uri)
                captureRepository.savePhotoNote(java.time.LocalDateTime.now(), photo.text, photo.jpeg, "share")
                "Saved to your inbox"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: cloud.wafflecommons.pixelbrainreader.data.repository.CaptureRepository.NoVaultYetException) {
                "Log in to Pixel Brain first, then share the image again"
            } catch (e: Exception) {
                "That image couldn't be read"
            }
            android.widget.Toast.makeText(this@MainActivity, message, android.widget.Toast.LENGTH_LONG).show()
            if (launchedForShare) finish()
        }
    }

    /**
     * An image shared to the app (13.4, P5): the owner chooses what it becomes, a photo note (X2) or
     * a game's own picture ([SharedImageChooser]). Only from another app's provider, as a photo note
     * (the same check, refused the same way). Without a login or a game page, a photo note as before.
     */
    private fun offerSharedImage(uri: Uri?) {
        if (uri == null || !isForeignImage(uri)) {
            // Says it can't be read, and ends a share.
            importSharedPhoto(uri)
            return
        }
        pendingSharedImage = uri
        sharedImageGames = null
        lifecycleScope.launch {
            val games = sharedImageGamesOrNull()
            // Another share replaced this one meanwhile, or the choice was already made.
            if (pendingSharedImage != uri) return@launch
            if (games == null) {
                dropSharedImage()
                importSharedPhoto(uri)
            } else {
                sharedImageGames = games
            }
        }
    }

    /** The game pages a shared image can be the picture of, with their pictures; null before a login or without a page. */
    private suspend fun sharedImageGamesOrNull(): SharedImageGames? = try {
        val loggedIn = withContext(Dispatchers.IO) { secretManager.getToken() != null }
        val pages = if (loggedIn) gamesRepository.pages() else emptyList()
        if (pages.isEmpty()) null else SharedImageGames(pages, gamePictures.artFor(pages))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * The shared image as [page]'s own picture (13.4, P5): re-encoded on the phone and kept next to
     * the page ([GamesRepository.setCover], refused on a read-only device), then the share ends. The
     * image stays pending until then: a recreation mid-save offers the choice again.
     */
    private fun saveSharedCover(uri: Uri, page: GamePage) {
        val launchedForShare = intent?.action == Intent.ACTION_SEND
        sharedImageGames = null
        lifecycleScope.launch {
            val message = try {
                val jpeg = photoReader.prepareCover(uri)
                coverMessage(gamesRepository.setCover(page.path, jpeg), done = "Picture saved for ${page.title}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                "That image can't be read"
            } catch (e: Exception) {
                COVER_NOT_SAVED
            }
            dropSharedImage()
            android.widget.Toast.makeText(this@MainActivity, message, android.widget.Toast.LENGTH_LONG).show()
            if (launchedForShare) finish()
        }
    }

    private fun dropSharedImage() {
        pendingSharedImage = null
        sharedImageGames = null
    }

    /** Another app's image: a `content:` address none of this app's providers serves. */
    private fun isForeignImage(uri: Uri): Boolean =
        cloud.wafflecommons.pixelbrainreader.data.capture.PhotoCapture.isForeign(uri.scheme, uri.authority, ownAuthorities())

    /** The authorities of this app's own content providers, its libraries' included. */
    private fun ownAuthorities(): Set<String> =
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PROVIDERS.toLong()))
            .providers.orEmpty()
            .flatMap { it.authority.orEmpty().split(';') }
            .filter { it.isNotBlank() }
            .toSet()

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        // Shared from another app: a page (its link), a highlight with its link, or text.
        if (intent.action == Intent.ACTION_SEND) {
            // A read-only device saves nothing: back to the app that shared, with the notice.
            if (VaultWriteGate.refuses()) {
                finish()
                return
            }
            // 13.0 (X2): an image shared from another app becomes a photo capture in the inbox; 13.4
            // (P5): or a game's own picture, the owner's choice.
            if (intent.type?.startsWith("image/") == true) {
                offerSharedImage(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
                return
            }
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            val html = intent.getStringExtra(Intent.EXTRA_HTML_TEXT)
            if (!text.isNullOrBlank() || !html.isNullOrBlank()) {
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: intent.getStringExtra(Intent.EXTRA_TITLE)
                shareImportRepository.start(SharedContent.of(text, subject), html)
            }
        }

        // Long-press on one of our Quick Settings tiles: open that tile's screen.
        if (intent.action == android.service.quicksettings.TileService.ACTION_QS_TILE_PREFERENCES) {
            val tile = intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME, android.content.ComponentName::class.java)
            val screen = when {
                tile?.className?.endsWith(".MissionLogTileService") == true -> WidgetNav.SCREEN_WORK
                tile?.className?.endsWith(".GameTileService") == true -> WidgetNav.SCREEN_GAMES
                else -> WidgetNav.SCREEN_MODES
            }
            pendingDestination = WidgetNav.screenToRoute(screen)
        }

        // Settings › Modes › Game night opens the app: it is that mode's configuration activity (12.17).
        if (intent.action == android.app.NotificationManager.ACTION_AUTOMATIC_ZEN_RULE) {
            pendingDestination = WidgetNav.screenToRoute(WidgetNav.SCREEN_GAMES)
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
                        try { intent.data?.getQueryParameter(WidgetNav.QUERY_SCREEN) } catch (e: Exception) { null },
                        try { intent.data?.getQueryParameter(WidgetNav.QUERY_ENTRY) } catch (e: Exception) { null }
                    )
                    if (route != null) pendingDestination = route
                }
                WidgetNav.HOST_CAPTURE -> if (!VaultWriteGate.refuses()) pendingCapture = true
                "import" -> if (!VaultWriteGate.refuses()) {
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
        // Waits for connectivity rather than failing the import when offline.
        cloud.wafflecommons.pixelbrainreader.data.workers.ImportWorker.enqueue(this, url)
        android.widget.Toast.makeText(this, "Importing Article...", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** The game pages a shared image's chooser offers, and each one's picture by path (13.4, P5). */
    private class SharedImageGames(val pages: List<GamePage>, val arts: Map<String, GameArt>)

    private companion object {
        /** The saved state's key of a shared image waiting for its choice (13.4): saved state only. */
        const val PENDING_SHARED_IMAGE = "pending_shared_image"
    }
}
