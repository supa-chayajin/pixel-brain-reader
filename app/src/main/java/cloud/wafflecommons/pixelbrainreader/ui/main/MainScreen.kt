package cloud.wafflecommons.pixelbrainreader.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.ui.theme.LocalNavRail
import cloud.wafflecommons.pixelbrainreader.ui.theme.railBottomClearance
import cloud.wafflecommons.pixelbrainreader.ui.utils.isWideLayout
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.rounded.Visibility

import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.SportsEsports
import cloud.wafflecommons.pixelbrainreader.ui.games.GamesRoutes
import cloud.wafflecommons.pixelbrainreader.ui.games.gamesDestinations
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.filled.Mood
import cloud.wafflecommons.pixelbrainreader.ui.settings.SettingsScreen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexIconButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import cloud.wafflecommons.pixelbrainreader.ui.theme.NavBarClearance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.launch

import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import nl.dionsegijn.konfetti.compose.KonfettiView
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.navigationBars

object Screen {
    const val Home = "home"
    const val Chat = "chat"
    const val HomeOS = "home_os"
    const val Settings = "settings"
    const val Import = "import"
    const val DailyNote = "daily_note"
    const val MoodTracker = "mood"
    const val Stats = "stats"
    const val ROUTE_HOME_CONFIG = "home_config"
}

/** Height of the floating [ExpressiveNavBar]'s tab surface (single source of truth). */
internal val ExpressiveNavBarHeight = 64.dp

/**
 * Bottom clearance that scrollable content must reserve so its last lines are not
 * occluded by the floating [ExpressiveNavBar]. The bar floats over content and
 * reserves no layout space, so = bar height + its 16.dp vertical padding + 8.dp
 * breathing room. The system nav-bar inset is NOT included here (it is already
 * applied by the Home Scaffold's content padding). Beside the side rail (a wide window)
 * there is no bar: only the system navigation bar is left to clear.
 */
internal val ExpressiveNavBarClearance: Dp
    @Composable get() = if (LocalNavRail.current) railBottomClearance() else ExpressiveNavBarHeight + 24.dp

@OptIn(
    ExperimentalMaterial3AdaptiveApi::class,
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)
@Composable
fun MainScreen(
    onLogout: () -> Unit,
    onExitApp: () -> Unit,
    launchedForShare: Boolean = false,
    viewModel: MainViewModel = hiltViewModel(),
    shareImportViewModel: cloud.wafflecommons.pixelbrainreader.ui.share.ShareImportViewModel = hiltViewModel(),
    moodViewModel: cloud.wafflecommons.pixelbrainreader.ui.mood.MoodViewModel = hiltViewModel(),
    weeklyCoachViewModel: cloud.wafflecommons.pixelbrainreader.ui.review.WeeklyCoachViewModel = hiltViewModel(),
    chatScopeViewModel: cloud.wafflecommons.pixelbrainreader.ui.ai.ChatScopeViewModel = hiltViewModel(),
    syncMarksViewModel: SyncMarksViewModel = hiltViewModel(),
    navViewModel: NavViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // 13.2: the navigation's live dot (a game session running) and the rail's names (kept on the device).
    val gameRunning by navViewModel.gameRunning.collectAsStateWithLifecycle()
    val railExpanded by navViewModel.railExpanded.collectAsStateWithLifecycle()
    // What the last sync brought (K3, D4): marked in the file list, opened first in a note's history.
    val lastSyncDigest by syncMarksViewModel.lastSync.collectAsStateWithLifecycle()
    val changedBySync = remember(lastSyncDigest) { lastSyncDigest?.brought ?: emptySet() }

    // Window size + pane directive are computed BEFORE the navigator so its
    // scaffoldValue is derived from the SAME (focus-aware) directive the scaffold
    // uses. Otherwise focus mode sets maxHorizontalPartitions=1 on the scaffold only,
    // while the navigator keeps the base 2-partition directive → both panes stay
    // Expanded and the detail never takes the full width.
    val windowAdaptiveInfo: WindowAdaptiveInfo = currentWindowAdaptiveInfo()
    val windowSizeClass = windowAdaptiveInfo.windowSizeClass
    val isLargeScreen = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val baseDirective = calculatePaneScaffoldDirective(windowAdaptiveInfo)
    val finalDirective = if (uiState.isFocusMode && isLargeScreen) {
        baseDirective.copy(
            maxHorizontalPartitions = 1,
            horizontalPartitionSpacerSize = 0.dp,
            verticalPartitionSpacerSize = 0.dp
        )
    } else {
        baseDirective.copy(
            horizontalPartitionSpacerSize = 8.dp,
            defaultPanePreferredWidth = uiState.listPaneWidth.dp // DYNAMIC WIDTH
        )
    }

    val navigator = rememberListDetailPaneScaffoldNavigator<Any>(scaffoldDirective = finalDirective)
    val scope = rememberCoroutineScope()
    // context is used for Toasts and Activity control. Ensure single declaration.
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptic = LocalHapticFeedback.current
    
    // --- Global Sensory Polish (RFC-008) ---
    // Inject via Hilt manually if not provided in constructor, or use EntryPoint. 
    // Since MainScreen is a Composable, better to inject in ViewModel or pass it.
    // However, MainScreen is the root. We can get it from MainViewModel if we inject it there?
    // Or just ask Hilt to provide it if we change signature. 
    // Let's assume we can add it to signature.
    
    // But changing signature breaks preview/callers if any. 
    // It is called from MainActivity. 
    // Let's add it to signature with default value if possible, or better:
    // Observe it from MainViewModel which should inject it.
    
    // Let's modify MainViewModel to expose the effects or just inject it here.
    // Changing MainScreen signature:
    // viewModel: MainViewModel = hiltViewModel(),
    // moodViewModel: ...
    // uiEffectManager: cloud.wafflecommons.pixelbrainreader.ui.utils.UiEffectManager = hiltViewModel() // No, it's not a VM.
    
    // We should inject it in MainViewModel and expose it, OR use an EntryPoint.
    // Simpler: Inject in MainViewModel, expose `effects` flow.
    // Let's modify MainViewModel first or assume it has it.
    // The instructions said "Inject UiEffectManager (via MainViewModel or direct inject)".
    // I will inject it here using a temporary Hilt EntryPoint workaround or just add to MainViewModel.
    // Adding to MainViewModel is cleanest.
    
    // Wait, I can't modify MainViewModel in this `replace_file_content`.
    // I will use a local variable for now or modify the code logic to assume MainViewModel exposes it.
    // Actually, let's look at `MainViewModel`.
    
    // PROPOSAL: I will modify `MainViewModel` to inject `UiEffectManager` and expose `globalEffects`.
    // For this step, I will prepare `MainScreen` to consume it from `viewModel.globalEffects`.
    
    var partyState by remember { mutableStateOf<List<nl.dionsegijn.konfetti.core.Party>>(emptyList()) }
    
    LaunchedEffect(viewModel) {
         viewModel.globalEffects.collect { effect ->
             when(effect) {
                 is cloud.wafflecommons.pixelbrainreader.ui.utils.GlobalEffect.Confetti -> {
                     partyState = when(effect.type) {
                         cloud.wafflecommons.pixelbrainreader.ui.utils.ConfettiType.LEVEL_UP -> {
                             // Reset after 3s
                             scope.launch { 
                                 kotlinx.coroutines.delay(3000)
                                 partyState = emptyList() 
                             }
                             listOf(
                                 nl.dionsegijn.konfetti.core.Party(
                                     speed = 0f,
                                     maxSpeed = 30f,
                                     damping = 0.9f,
                                     spread = 360,
                                     colors = listOf(0xfce18a, 0xff726d, 0xf4306d, 0xb48def),
                                     emitter = nl.dionsegijn.konfetti.core.emitter.Emitter(duration = 100, TimeUnit.MILLISECONDS).max(100),
                                     position = nl.dionsegijn.konfetti.core.Position.Relative(0.5, 0.3)
                                 )
                             )
                         }
                         cloud.wafflecommons.pixelbrainreader.ui.utils.ConfettiType.GOAL_REACHED -> {
                              // Reset after 4s (duration 2000 + buffer)
                             scope.launch { 
                                 kotlinx.coroutines.delay(4000)
                                 partyState = emptyList() 
                             }
                              listOf(
                                 nl.dionsegijn.konfetti.core.Party(
                                     speed = 10f,
                                     maxSpeed = 30f,
                                     damping = 0.9f,
                                     spread = 360,
                                     colors = listOf(0xfce18a, 0xff726d, 0xf4306d, 0xb48def),
                                     emitter = nl.dionsegijn.konfetti.core.emitter.Emitter(duration = 2000, TimeUnit.MILLISECONDS).max(200),
                                     position = nl.dionsegijn.konfetti.core.Position.Relative(0.5, 0.5) // Center
                                 )
                             )
                         }
                         else -> emptyList()
                     }
                 }
             }
         }
    }

    LaunchedEffect(viewModel.uiEvent) {
        viewModel.uiEvent.collect { event ->
            when(event) {
                is cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent.ShowToast -> {
                    android.widget.Toast.makeText(context, event.message, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val navController = androidx.navigation.compose.rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Hide the navigation bar while the keyboard is open. NavigationSuiteScaffold
    // otherwise reserves the app nav-bar height for content, and because imePadding
    // uses the full IME inset, the input floats a nav-bar-height above the keyboard
    // on EVERY text surface. layoutType = None reclaims that space (and gives more
    // room to type); the nav returns when the keyboard closes.
    // Also hide it while editing a file in the detail pane: editor mode wants the full
    // height for writing, and the floating bar would otherwise overlay the editor.
    val isEditingFile = uiState.isEditing && uiState.selectedFileName != null
    // Hidden too on the share screen: an import is one focused flow, not a tab.
    val navLayoutType = if (WindowInsets.isImeVisible || isEditingFile || currentRoute == Screen.Import || GamesRoutes.isMap(currentRoute)) {
        androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType.None
    } else {
        // Custom ExpressiveNavBar: a floating bottom bar (Finance-style) on phones and portrait tablets.
        androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType.NavigationBar
    }
    // A wide window (a tablet in landscape, an unfolded Fold) navigates with the side rail instead.
    // Beside the content, it never covers the keyboard or the editor, so it stays for both; only
    // the share screen (one focused flow, not a tab) hides it.
    val useRail = isWideLayout()
    val railVisible = useRail && currentRoute != Screen.Import && !GamesRoutes.isMap(currentRoute)
    // 13.2 (the owner's extra): the phone's floating bar slides away while a page scrolls down and
    // comes back as it scrolls up, or on a new page. The rail stays.
    val navDensity = LocalDensity.current
    val barHide = remember(navDensity) { NavBarHideOnScroll(thresholdPx = with(navDensity) { 24.dp.toPx() }) }
    LaunchedEffect(currentRoute) { barHide.show() }
    val barHiddenOffset = with(navDensity) { (ExpressiveNavBarHeight + 40.dp).toPx() } +
        WindowInsets.navigationBars.getBottom(navDensity)
    val barOffset by animateFloatAsState(
        targetValue = if (barHide.hidden && !useRail) barHiddenOffset else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "navBarHide"
    )
    val navigateToTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // SAF launcher: export the whole vault as a ZIP to a user-picked location (outside the app).
    val vaultExportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: android.net.Uri? -> uri?.let { viewModel.exportVault(it) } }

    // Live repo-sync step label for the Repo / File-detail pull-to-refresh indicators.
    val repoSyncStatus by viewModel.repoSyncStatus.collectAsStateWithLifecycle()

    // Custom nav-bar order (persisted); drives the order of the regular tabs.
    val navBarOrder by viewModel.navBarOrder.collectAsStateWithLifecycle()
    // The tabs the current mode hides (E1).
    val hiddenTabs by viewModel.hiddenTabs.collectAsStateWithLifecycle()

    // Smart Active State Logic — follows the real day (a date remembered once went stale after midnight).
    val today by viewModel.today.collectAsStateWithLifecycle()
    val todayName = remember(today) { "$today.md" }
    val isViewingDailyNote = uiState.selectedFileName == todayName

    // -- NAVIGATION LOGIC --
    // Mobile (Compact): Default to List. Navigate to Detail only if file selected.
    // Tablet (Expanded): Default to List+Detail.
    
    LaunchedEffect(isLargeScreen, uiState.selectedFileName) {
        if (!isLargeScreen) {
            // Mobile Mode
            if (uiState.selectedFileName != null) {
                navigator.navigateTo(ListDetailPaneScaffoldRole.Detail)
            } else {
                navigator.navigateTo(ListDetailPaneScaffoldRole.List)
            }
        } else {
            // Tablet/Expanded Mode
            if (uiState.isFocusMode) {
                 navigator.navigateTo(ListDetailPaneScaffoldRole.Detail)
            } else {
                 // Even if nothing selected, we show List+Detail (Detail is Welcome Screen)
                 // Ensure navigator understands we want to show both if space allows (Directive handles this),
                 // but we need to ensure we aren't stuck in "List Only" mode if the framework thinks so.
                 // Actually, ListDetailPaneScaffold handles the dual view automatically based on directive.
                 // We only need to force Detail if Focus Mode.
                 if (navigator.currentDestination?.pane == ListDetailPaneScaffoldRole.List && uiState.selectedFileName != null) {
                      navigator.navigateTo(ListDetailPaneScaffoldRole.Detail)
                 }
            }
        }
    }

    LaunchedEffect(uiState.isFocusMode) {
        if (uiState.isFocusMode && isLargeScreen) {
            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail)
        }
    }

    // Folder-insight ready → open the Detail pane. A one-shot event (not a selectedFileName diff)
    // so re-analyzing while an insight is already open still navigates to the fresh result.
    LaunchedEffect(Unit) {
        viewModel.openDetailEvent.collect {
            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail)
        }
    }
    
    // A pull that stopped on a sync conflict, or was paused by one, opens the screen that resolves
    // it (12.15, S1): one-shot events, so a recreation never reopens it.
    val pendingConflicts: cloud.wafflecommons.pixelbrainreader.ui.sync.PendingConflictViewModel = hiltViewModel()
    LaunchedEffect(Unit) {
        pendingConflicts.stops.collect {
            navController.navigate(V11Routes.SYNC_CONFLICTS) { launchSingleTop = true }
        }
    }

    // A share from another app opens the import screen (also when an older one is still parked).
    val shareGeneration by shareImportViewModel.generation.collectAsStateWithLifecycle()
    LaunchedEffect(shareGeneration) {
        if (shareImportViewModel.state.value != null && navController.currentDestination?.route != Screen.Import) {
            navController.navigate(Screen.Import) { launchSingleTop = true }
        }
    }



    // Hotfix: Programmatic Navigation (Daily Note)
    LaunchedEffect(uiState.navigationTrigger) {
        uiState.navigationTrigger?.let { route ->
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
            viewModel.consumeNavigationTrigger()
        }
    }
    
    // Mood Event Listening
    LaunchedEffect(moodViewModel.uiEvent) {
        moodViewModel.uiEvent.collect { event ->
             when(event) {
                is cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent.ShowToast -> {
                    android.widget.Toast.makeText(context, event.message, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Mood Sheet State for Daily Note
    var showMoodSheet by remember { mutableStateOf(false) }


    if (showMoodSheet) {
        cloud.wafflecommons.pixelbrainreader.ui.mood.MoodCheckInSheet(
            onDismiss = { showMoodSheet = false },
            viewModel = moodViewModel
        )
    }
    
    // Delete Confirmation Dialog
    if (uiState.showDeleteConfirmation) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteConfirmation() },
            title = { Text("Delete this file?") },
            text = { Text("This action cannot be undone and will delete the file from the Vault and Git repository.") },
            icon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.confirmDeleteFile()
                    },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { viewModel.dismissDeleteConfirmation() }) {
                    Text("Cancel")
                }
            }
        )
    }

    // The nav bar FLOATS over the content (Finance-style): content fills the whole
    // screen and the bar is overlaid at the bottom, so the page shows through around
    // and behind it — no reserved dark strip. (Content behind the bar is accepted.)
    // On a wide window the rail stands beside the content instead, and the screens
    // learn through LocalNavRail that no bar covers their bottom.
    CompositionLocalProvider(LocalNavRail provides railVisible) {
    Box(modifier = Modifier.fillMaxSize()) {
      Row(modifier = Modifier.fillMaxSize()) {
        if (railVisible) {
            ExpressiveNavRail(
                currentRoute = currentRoute,
                isViewingDailyNote = isViewingDailyNote,
                order = navBarOrder,
                onSelect = navigateToTab,
                hidden = hiddenTabs,
                gameRunning = gameRunning,
                expanded = railExpanded,
                onToggleExpanded = { navViewModel.setRailExpanded(!railExpanded) }
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .nestedScroll(barHide)
                // The rail already keeps clear of the start inset (a cutout, a side nav bar).
                .then(if (railVisible) Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)) else Modifier)
        ) {
            androidx.navigation.compose.NavHost(
            navController = navController,
            startDestination = Screen.DailyNote,
            modifier = Modifier.fillMaxSize(),
            // Expressive fade-through between top-level destinations (fade + subtle scale).
            enterTransition = {
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(280, 40)) +
                    androidx.compose.animation.scaleIn(
                        initialScale = 0.94f,
                        animationSpec = androidx.compose.animation.core.tween(280, 40)
                    )
            },
            exitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180)) },
            popEnterTransition = {
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(280, 40)) +
                    androidx.compose.animation.scaleIn(
                        initialScale = 0.94f,
                        animationSpec = androidx.compose.animation.core.tween(280, 40)
                    )
            },
            popExitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180)) }
        ) {
            composable(Screen.Home) {
                // Home with ListDetailPaneScaffold, Breadcrumbs, and Global TopBar
                
                val canNavigateBack = navigator.canNavigateBack()
                val isSubFolder = uiState.currentPath.isNotEmpty()

                if (uiState.showCreateFileDialog) {
                    cloud.wafflecommons.pixelbrainreader.ui.components.NewFileBottomSheet(
                        availableTemplates = uiState.availableTemplates,
                        onDismiss = { viewModel.dismissCreateFileDialog() },
                        loadVariables = viewModel::templateVariables,
                        onCreate = { name, template, values -> viewModel.createNewFile(name, template, values) }
                    )
                }

                // Back Handler for Home Logic
                val isBackHandlerEnabled = uiState.isFocusMode || canNavigateBack || isSubFolder
                
                BackHandler(enabled = isBackHandlerEnabled) {
                    when {
                        uiState.isFocusMode -> viewModel.toggleFocusMode()
                        canNavigateBack -> scope.launch { navigator.navigateBack() }
                        isSubFolder -> viewModel.navigateUp()
                    }
                }

                val snackbarHostState = remember { SnackbarHostState() }
                
                LaunchedEffect(uiState.userMessage) {
                    uiState.userMessage?.let { message ->
                        snackbarHostState.showSnackbar(message)
                        viewModel.userMessageShown()
                    }
                }

                // A Time Machine restore stays undoable while its snackbar is up.
                val restoreUndo by viewModel.restoreUndo.collectAsStateWithLifecycle()
                LaunchedEffect(restoreUndo) {
                    restoreUndo?.let { message ->
                        val result = snackbarHostState.showSnackbar(
                            message = message,
                            actionLabel = "Undo",
                            duration = androidx.compose.material3.SnackbarDuration.Long
                        )
                        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                            viewModel.undoRestore()
                        } else {
                            viewModel.restoreUndoShown()
                        }
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = {
                        // Lift above the floating ExpressiveNavBar, which would otherwise cover it.
                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier.padding(bottom = NavBarClearance)
                        )
                    },
                    topBar = {
                        // Local state to manage Search Bar visibility (toggles UI mode)
                        var isSearching by remember { mutableStateOf(false) }

                        TopAppBar(
                            title = { 
                                if (isSearching) {
                                    // SEARCH MODE: Input Field
                                    androidx.compose.material3.TextField(
                                        value = uiState.searchQuery,
                                        onValueChange = { viewModel.onSearchQueryChanged(it) },
                                        placeholder = { Text("Search...") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                                        shape = androidx.compose.foundation.shape.CircleShape,
                                        colors = androidx.compose.material3.TextFieldDefaults.colors(
                                            focusedIndicatorColor = Color.Transparent,
                                            unfocusedIndicatorColor = Color.Transparent
                                        ),
                                        // Clear/Close Icon inside the text field
                                        trailingIcon = {
                                             if (uiState.searchQuery.isNotEmpty()) {
                                                 CortexIconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                                                     Icon(Icons.Default.SearchOff, "Clear")
                                                 }
                                             }
                                        }
                                    )
                                } else {
                                    // VIEW MODE: Breadcrumbs
                                    cloud.wafflecommons.pixelbrainreader.ui.components.BreadcrumbBar(
                                        currentPath = uiState.currentPath,
                                        onPathClick = { path -> viewModel.loadFolder(path) },
                                        onHomeClick = { viewModel.loadFolder("") },
                                        isLargeScreen = isLargeScreen
                                    )
                                }
                            },
                            actions = {
                                if (isSearching) {
                                    // SEARCH MODE ACTION: Exit Search
                                    FilledTonalIconButton(
                                        onClick = {
                                            isSearching = false
                                            viewModel.onSearchQueryChanged("")
                                        }, shapes = IconButtonDefaults.shapes(),
                                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    ) {
                                        Icon(Icons.Default.SearchOff, "Close Search Mode")
                                    }
                                } else {
                                    // VIEW MODE ACTIONS
                                    // 1. Existing Actions
                                    if (uiState.selectedFileName != null) {
                                        // Edit/View Toggle — none on a desktop mirror, a worklog or a read-only device.
                                        if (!uiState.isGeneratedNote && !VaultWriteGate.readOnly) {
                                            FilledTonalIconButton(
                                                onClick = { viewModel.toggleEditMode() }, shapes = IconButtonDefaults.shapes(),
                                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            ) {
                                                Icon(
                                                    imageVector = if (uiState.isEditing) Icons.Rounded.Visibility else Icons.Rounded.Edit,
                                                    contentDescription = if (uiState.isEditing) "View" else "Edit"
                                                )
                                            }
                                        }

                                        // Delete button removed from here

                                        // Focus Mode (Large Screen Only)
                                        if (isLargeScreen) {
                                            FilledTonalIconButton(
                                                onClick = { viewModel.toggleFocusMode() }, shapes = IconButtonDefaults.shapes(),
                                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            ) {
                                                Icon(
                                                    imageVector = if (uiState.isFocusMode) Icons.Rounded.CloseFullscreen else Icons.Rounded.OpenInFull,
                                                    contentDescription = "Focus Mode"
                                                )
                                            }
                                        }

                                        // Close File
                                        FilledTonalIconButton(
                                            onClick = {
                                                viewModel.closeFile()
                                                if (navigator.canNavigateBack()) {
                                                    scope.launch { navigator.navigateBack() }
                                                }
                                            }, shapes = IconButtonDefaults.shapes(),
                                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        ) {
                                            Icon(Icons.Rounded.Close, "Close")
                                        }

                                    } else {
                                        // Search Trigger
                                        FilledTonalIconButton(
                                            onClick = { isSearching = true }, shapes = IconButtonDefaults.shapes(),
                                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        ) {
                                            Icon(Icons.Rounded.Search, "Search")
                                        }

                                        // Browser Actions
                                        FilledTonalIconButton(
                                            onClick = { viewModel.openCreateFileDialog() }, shapes = IconButtonDefaults.shapes(),
                                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        ) {
                                            Icon(Icons.Rounded.Add, "New File")
                                        }

                                        // Export the whole vault as a ZIP to phone storage (outside the app).
                                        FilledTonalIconButton(
                                            onClick = {
                                                vaultExportLauncher.launch("PixelBrainVault_${java.time.LocalDate.now()}.zip")
                                            }, shapes = IconButtonDefaults.shapes(),
                                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        ) {
                                            Icon(Icons.Rounded.Download, "Export vault")
                                        }
                                    }
                                }
                            }
                        )
                    },

                    contentWindowInsets = androidx.compose.material3.ScaffoldDefaults.contentWindowInsets
                ) { padding ->
                    Box(
                        modifier = Modifier
                            .padding(padding) // Applies TopBar padding
                            .consumeWindowInsets(padding) 
                            .fillMaxSize()
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Main Content
                            ListDetailPaneScaffold(
                                directive = finalDirective,
                                value = navigator.scaffoldValue,
                                listPane = {
                                    // The scaffold's directive/scaffoldValue now hide this pane
                                    // entirely in focus mode (List = AdaptStrategy.Hide), so the
                                    // AnimatedPane transition is all that's needed — no inner
                                    // AnimatedVisibility (which would double the exit animation).
                                    AnimatedPane {
                                        var fileToMove by remember { mutableStateOf<cloud.wafflecommons.pixelbrainreader.data.remote.model.GithubFileDto?>(null) }
                                        if (fileToMove != null) {
                                            val target = fileToMove!!
                                            androidx.compose.material3.AlertDialog(
                                                onDismissRequest = { fileToMove = null },
                                                title = { Text("Move \"${target.name}\"") },
                                                shape = MaterialTheme.shapes.extraLarge,
                                                text = {
                                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        Text(
                                                            text = "Destination: /${uiState.moveDialogCurrentPath}",
                                                            style = MaterialTheme.typography.labelLarge,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                        if (uiState.moveDialogCurrentPath.isNotEmpty()) {
                                                            androidx.compose.material3.TextButton(
                                                                onClick = { viewModel.navigateUpMoveFolder() }
                                                            ) {
                                                                Text("⬅ Up one folder")
                                                            }
                                                        }
                                                        if (uiState.availableMoveDestinations.isEmpty()) {
                                                            Text(
                                                                text = "No subfolders here.",
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        } else {
                                                            Column(
                                                                modifier = Modifier.height(200.dp),
                                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                                            ) {
                                                                uiState.availableMoveDestinations.forEach { folderPath ->
                                                                    androidx.compose.material3.OutlinedButton(
                                                                        onClick = { viewModel.navigateToMoveFolder(folderPath) },
                                                                        modifier = Modifier.fillMaxWidth()
                                                                    ) {
                                                                        Text(folderPath.substringAfterLast("/"))
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                },
                                                confirmButton = {
                                                    androidx.compose.material3.Button(onClick = {
                                                        viewModel.moveFile(target, uiState.moveDialogCurrentPath)
                                                        fileToMove = null
                                                    }) {
                                                        Text("Move Here")
                                                    }
                                                },
                                                dismissButton = {
                                                    androidx.compose.material3.TextButton(onClick = { fileToMove = null }) {
                                                        Text("Cancel")
                                                    }
                                                }
                                            )
                                        }
                                        Row(modifier = Modifier.fillMaxSize()) {
                                            Box(modifier = Modifier.weight(1f)) {
                                                FileListPane(
                                                    files = uiState.files,
                                                    isLoading = uiState.isLoading,
                                                    isRefreshing = uiState.isRefreshing,
                                                    statusText = repoSyncStatus,
                                                    error = uiState.error,
                                                    currentPath = uiState.currentPath,
                                                    searchQuery = uiState.searchQuery, // PASS QUERY
                                                    showMenuIcon = false,
                                                    onFileClick = { file ->
                                                        viewModel.loadFile(file)
                                                        scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail) }
                                                    },
                                                    onFolderClick = { path -> viewModel.loadFolder(path) },
                                                    onNavigateUp = { viewModel.navigateUp() },
                                                    onMenuClick = { },
                                                    onRefresh = { viewModel.refreshCurrentFolder() },
                                                    onCreateFile = { viewModel.openCreateFileDialog() },
                                                    onRenameFile = { newName, file -> viewModel.renameFile(newName, file) },
                                                    onDeleteFile = { file ->
                                                        // Delete the swiped file directly — do NOT open it first.
                                                        viewModel.requestDeleteFile(file)
                                                    },
                                                    onAnalyzeFolder = { viewModel.analyzeCurrentFolder() },
                                                    onMoveFile = { file ->
                                                        fileToMove = file
                                                        viewModel.prepareMove(file)
                                                    },
                                                    changedBySync = changedBySync,
                                                    // K2 (12.20): Chat on a folder; no AI on a read-only device.
                                                    onAskFolder = if (VaultWriteGate.readOnly) null else { path ->
                                                        cloud.wafflecommons.pixelbrainreader.data.ai.ChatScope.folder(path)?.let { scope ->
                                                            chatScopeViewModel.ask(scope)
                                                            navigateToTab(Screen.Chat)
                                                        }
                                                    }
                                                )
                                            }
                                            
                                            // Resizable Handle (Only Large Screen)
                                            if (isLargeScreen && !uiState.isFocusMode) {
                                                cloud.wafflecommons.pixelbrainreader.ui.components.SplitPaneHandle(
                                                    onDrag = { delta ->
                                                        val newWidth = uiState.listPaneWidth + delta
                                                        viewModel.updateListPaneWidth(newWidth.coerceIn(200f, 600f))
                                                    }
                                                )
                                            }
                                        }
                                    }
                                },
                                detailPane = {
                                    AnimatedPane {
                                    if (uiState.selectedFileName != null) {
                                        FileDetailPane(
                                            content = viewModel.unsavedContent
                                                ?: uiState.selectedFileContent,
                                            onContentChange = { viewModel.onContentChanged(it) },
                                            fileName = uiState.selectedFileName,
                                            isLoading = uiState.isLoading,
                                            isRefreshing = uiState.isRefreshing,
                                            statusText = repoSyncStatus,
                                            onRefresh = { viewModel.refreshCurrentFile() },
                                            isExpandedScreen = isLargeScreen,
                                            isEditing = uiState.isEditing,
                                            isReadOnly = uiState.isGeneratedNote || VaultWriteGate.readOnly,
                                            readOnlyDevice = !uiState.isGeneratedNote && VaultWriteGate.readOnly,
                                            isWorklog = uiState.isWorklogNote,
                                            hasUnsavedChanges = viewModel.hasUnsavedChanges,
                                            saveState = uiState.saveState,
                                            onWikiLinkClick = { target -> viewModel.onWikiLinkClick(target) },
                                            onCreateNew = { viewModel.createNewFile() },
                                            moodViewModel = moodViewModel,
                                            onSave = { viewModel.saveFile() },
                                            // Encrypted notes are ciphertext in git: no history view for them.
                                            onFetchHistory = if (uiState.selectedFileName?.endsWith(".enc") == true) null
                                                else ({ viewModel.getFileCommitHistory() }),
                                            onFetchRevisionContent = { commitId, path ->
                                                if (path != null) viewModel.getFileContentAtCommit(path = path, commitId = commitId)
                                                else viewModel.getFileContentAtCommit(commitId = commitId)
                                            },
                                            lastSync = uiState.selectedFilePath?.let { path -> lastSyncDigest?.changeFor(path) },
                                            onRestoreRevision = { restored -> viewModel.restoreRevision(restored) },
                                            filePath = uiState.selectedFilePath,
                                            // D5 (13.0): the folder in the list beside the note. Where the window shows one
                                            // pane (a phone, and a medium window such as a portrait tablet: 600 dp is not
                                            // yet two panes), step back to the list, or the tap changes nothing in sight.
                                            onOpenFolder = { folder ->
                                                viewModel.loadFolder(folder)
                                                if (uiState.isFocusMode) viewModel.toggleFocusMode()
                                                if (baseDirective.maxHorizontalPartitions < 2 && navigator.canNavigateBack()) {
                                                    scope.launch { navigator.navigateBack() }
                                                }
                                            },
                                            onOpenNote = { path -> viewModel.openVaultFile(path) },
                                            // Hand off this note (C13): a link to it, on its own project when it has one.
                                            // Never a private note: its name would land in a plain file.
                                            onHandOff = uiState.selectedFilePath
                                                ?.takeIf { it.endsWith(".md") && !it.startsWith("99_Private/") && !VaultWriteGate.readOnly }
                                                ?.let { path ->
                                                    {
                                                        val link = "[[" + path.substringAfterLast('/').removeSuffix(".md") + "]] "
                                                        // The note's folder: the composer picks the project holding it (a sub-project first).
                                                        navController.navigate(V11Routes.handoff(path.substringBeforeLast('/'), link))
                                                    }
                                                },
                                            // A weekly review asks the Coach about its week (12.13, D6), then opens Chat.
                                            onAskCoach = uiState.selectedFilePath
                                                ?.takeIf(weeklyCoachViewModel::canAsk)
                                                // Chat opens as its tab: a second Chat pushed over the list left the tabs' saved stacks lost.
                                                ?.let { path -> { weeklyCoachViewModel.askCoach(path) { navigateToTab(Screen.Chat) } } },
                                            // K2 (12.20): Chat on this note alone; never a private note, no AI read-only.
                                            onAskNote = uiState.selectedFilePath
                                                ?.takeUnless { VaultWriteGate.readOnly }
                                                ?.let(cloud.wafflecommons.pixelbrainreader.data.ai.ChatScope::note)
                                                ?.let { scope -> { chatScopeViewModel.ask(scope); navigateToTab(Screen.Chat) } }
                                        )
                                    } else {
                                        cloud.wafflecommons.pixelbrainreader.ui.components.WelcomeScreen()
                                    }
                                    }
                                }
                            )
                        }

                        // Persistent Sync Indicator (Overlay)
                        androidx.compose.animation.AnimatedVisibility(
                            visible = uiState.isSyncing,
                            enter = androidx.compose.animation.expandVertically(),
                            exit = androidx.compose.animation.shrinkVertically(),
                            modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter)
                        ) {
                            androidx.compose.material3.LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp),
                                color = MaterialTheme.colorScheme.tertiary,
                                trackColor = Color.Transparent
                            )
                        }
                    }
                }
            }

            composable(Screen.Chat) {
                val snackbarHostState = remember { SnackbarHostState() }
                
                LaunchedEffect(uiState.userMessage) {
                    uiState.userMessage?.let { message ->
                        snackbarHostState.showSnackbar(message)
                        viewModel.userMessageShown()
                    }
                }
                
                Scaffold(
                    snackbarHost = {
                        // Lift above the floating ExpressiveNavBar, which would otherwise cover it.
                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier.padding(bottom = NavBarClearance)
                        )
                    },
                    contentWindowInsets = WindowInsets(0,0,0,0) // ChatPanel handles its own insets
                ) { padding ->
                    Box(modifier = Modifier.padding(padding)) {
                         cloud.wafflecommons.pixelbrainreader.ui.ai.ChatPanel(
                            onInsertContent = { text, sources -> viewModel.saveChatToInbox(text, sources) },
                            onSourceClick = { path -> viewModel.openVaultFile(path) }
                        )
                    }
                }
            }

            composable(Screen.Settings) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateToDailyLayout = { navController.navigate("daily_layout") },
                    onNavigateToFeeds = { navController.navigate("watch_feeds") },
                    onNavigateToPsn = { navController.navigate(cloud.wafflecommons.pixelbrainreader.ui.games.GamesRoutes.PSN) },
                    onNavigateToPrivacyLedger = { navController.navigate(cloud.wafflecommons.pixelbrainreader.ui.settings.PRIVACY_LEDGER_ROUTE) },
                    onNavigateToHabitConfig = { navController.navigate("habit_config") },
                    onNavigateToHomeConfig = { navController.navigate(Screen.ROUTE_HOME_CONFIG) },
                    onNavigateToReminders = { navController.navigate("reminders") },
                    onNavigateToNavBarReorder = { navController.navigate("navbar_reorder") },
                    onNavigateToMoodTags = { navController.navigate("mood_tags") },
                    onNavigateToGamificationSettings = { navController.navigate("gamification_settings") },
                    onNavigateToModes = { navController.navigate(V11Routes.MODES_SETTINGS) },
                    onNavigateToConfidentiality = { navController.navigate(V11Routes.CONFIDENTIALITY) },
                    onOpenSyncConflicts = { navController.navigate(V11Routes.SYNC_CONFLICTS) },
                    onOpenNote = { path ->
                        viewModel.openVaultFile(path)
                        navController.popBackStack()
                    },
                    onLogout = onLogout
                )
            }

            composable("gamification_settings") {
                cloud.wafflecommons.pixelbrainreader.ui.settings.GamificationSettingsScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable("reminders") {
                cloud.wafflecommons.pixelbrainreader.ui.settings.RemindersScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable("habit_config") {
                cloud.wafflecommons.pixelbrainreader.ui.settings.HabitConfigScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable("daily_layout") {
                cloud.wafflecommons.pixelbrainreader.ui.settings.DailyLayoutScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable("watch_feeds") {
                cloud.wafflecommons.pixelbrainreader.ui.settings.FeedsScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(cloud.wafflecommons.pixelbrainreader.ui.wrapped.WRAPPED_ROUTE) {
                cloud.wafflecommons.pixelbrainreader.ui.wrapped.WrappedScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(cloud.wafflecommons.pixelbrainreader.ui.settings.PRIVACY_LEDGER_ROUTE) {
                cloud.wafflecommons.pixelbrainreader.ui.settings.PrivacyLedgerScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable("navbar_reorder") {
                cloud.wafflecommons.pixelbrainreader.ui.settings.NavBarReorderScreen(
                    order = navBarOrder,
                    onOrderChange = { viewModel.setNavBarOrder(it) },
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable("mood_tags") {
                cloud.wafflecommons.pixelbrainreader.ui.settings.MoodTagSettingsScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }


            composable(Screen.HomeOS) {
                cloud.wafflecommons.pixelbrainreader.ui.homeos.ChoreDashboardScreen(
                    onNavigateToStats = {
                        if (navController.previousBackStackEntry?.destination?.route == Screen.Stats) navController.popBackStack()
                        else navController.navigate(Screen.Stats)
                    }
                )
            }

            composable(Screen.Import) {
                // A share launched from another app goes back to it once saved or cancelled.
                cloud.wafflecommons.pixelbrainreader.ui.share.ShareImportScreen(
                    viewModel = shareImportViewModel,
                    onClose = {
                        if (launchedForShare) (context as? android.app.Activity)?.finish() else navController.popBackStack()
                    },
                    onHandOff = { text -> navController.navigate(V11Routes.handoff(text = text)) },
                    onExplainError = { navController.navigate(V11Routes.EXPLAIN_ERROR) },
                    onSaved = { path ->
                        android.widget.Toast.makeText(context, "Saved to $path", android.widget.Toast.LENGTH_SHORT).show()
                        if (launchedForShare) {
                            (context as? android.app.Activity)?.finish()
                        } else {
                            navController.popBackStack()
                            viewModel.openVaultFile(path)
                        }
                    }
                )
            }

            composable(Screen.DailyNote) {
                // The board, mood and health cards observe Room/vault flows and follow syncs on
                // their own — no manual reload trigger needed.
                cloud.wafflecommons.pixelbrainreader.ui.daily.DailyNoteScreen(
                    onCheckInClicked = { showMoodSheet = true },
                    onNavigateToSettings = { navController.navigate(Screen.Settings) },
                    onOpenNote = { path -> viewModel.openVaultFile(path) },
                    onOpenModesSettings = { navController.navigate(V11Routes.MODES_SETTINGS) },
                    onOpenConfidentiality = { navController.navigate(V11Routes.CONFIDENTIALITY) },
                    onOpenWork = { navController.navigate(V11Routes.WORK_HUB) },
                    onOpenActivity = { navController.navigate(V11Routes.ACTIVITY) },
                    onOpenProject = { root -> navController.navigate(V11Routes.project(root)) },
                    onOpenChores = { navigateToTab(Screen.HomeOS) },
                    onOpenReader = { navController.navigate(Screen.Import) },
                    onOpenMeetingPrep = { id -> navController.navigate(V11Routes.meetingPrep(id)) },
                    onOpenGame = { path -> navController.navigate(cloud.wafflecommons.pixelbrainreader.ui.games.GamesRoutes.game(path)) },
                    onOpenSyncConflicts = { navController.navigate(V11Routes.SYNC_CONFLICTS) },
                    onOpenInbox = { navController.navigate(V11Routes.INBOX) }
                )
            }
            
            composable("habits") {
                cloud.wafflecommons.pixelbrainreader.ui.lifeos.HabitDashboardScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToStats = {
                        if (navController.previousBackStackEntry?.destination?.route == Screen.Stats) navController.popBackStack()
                        else navController.navigate(Screen.Stats)
                    }
                )
            }
            
            composable(Screen.MoodTracker) {
                cloud.wafflecommons.pixelbrainreader.ui.mood.MoodHistoryScreen()
            }
            
            composable(Screen.Stats) {
                 cloud.wafflecommons.pixelbrainreader.ui.lifestats.LifeStatsScreen(
                     onNavigateBack = { navController.popBackStack() },
                     onNavigateToHabits = {
                         if (navController.previousBackStackEntry?.destination?.route == "habits") navController.popBackStack()
                         else navController.navigate("habits")
                     },
                     onNavigateToChores = {
                         if (navController.previousBackStackEntry?.destination?.route == Screen.HomeOS) navController.popBackStack()
                         else navController.navigate(Screen.HomeOS)
                     },
                     onOpenWrapped = { navController.navigate(cloud.wafflecommons.pixelbrainreader.ui.wrapped.WRAPPED_ROUTE) }
                 )
            }
            

            composable(Screen.ROUTE_HOME_CONFIG) {
                cloud.wafflecommons.pixelbrainreader.ui.homeconfig.HomeConfigScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            // Games (12.17, RFC-009): the tab, a game's page, the PlayStation settings.
            gamesDestinations(navController, openNote = { path -> viewModel.openVaultFile(path) })

            v11Destinations(
                navController,
                openNote = { path -> viewModel.openVaultFile(path) },
                onHandoffSent = {
                    android.widget.Toast.makeText(context, "Handed off: your desktop sees it after the next sync", android.widget.Toast.LENGTH_SHORT).show()
                    if (launchedForShare) (context as? android.app.Activity)?.finish() else navController.popBackStack()
                }
            )
        }

        // --- Global Effects Overlay ---
        if (partyState.isNotEmpty()) {
                KonfettiView(
                parties = partyState,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
      }

        // --- Floating nav bar, overlaid on the content (Finance-style) ---
        // Hidden while the keyboard is open (navLayoutType == None) so it doesn't
        // float above the IME on text surfaces. A wide window has the rail instead.
        if (!useRail && navLayoutType != androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType.None) {
            ExpressiveNavBar(
                currentRoute = currentRoute,
                isViewingDailyNote = isViewingDailyNote,
                isLargeScreen = isLargeScreen,
                order = navBarOrder,
                onSelect = navigateToTab,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer { translationY = barOffset },
                hidden = hiddenTabs,
                gameRunning = gameRunning
            )
        }
    }
    }
}


