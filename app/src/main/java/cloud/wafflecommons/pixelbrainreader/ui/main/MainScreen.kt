package cloud.wafflecommons.pixelbrainreader.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Settings

import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import cloud.wafflecommons.pixelbrainreader.ui.components.PullToRefreshBox
import cloud.wafflecommons.pixelbrainreader.ui.settings.SettingsScreen
import cloud.wafflecommons.pixelbrainreader.ui.mood.MoodHistoryScreen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.window.core.layout.WindowWidthSizeClass
import androidx.window.layout.FoldingFeature
import kotlinx.coroutines.launch

import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import nl.dionsegijn.konfetti.compose.KonfettiView
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import nl.dionsegijn.konfetti.compose.KonfettiView
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.emitter.Emitter

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

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onLogout: () -> Unit,
    onExitApp: () -> Unit,
    viewModel: MainViewModel = hiltViewModel(),
    moodViewModel: cloud.wafflecommons.pixelbrainreader.ui.mood.MoodViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val navigator = rememberListDetailPaneScaffoldNavigator<Any>()
    val scope = rememberCoroutineScope()
    // context is used for Toasts and Activity control. Ensure single declaration.
    val context = androidx.compose.ui.platform.LocalContext.current
    
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

    val windowAdaptiveInfo: WindowAdaptiveInfo = currentWindowAdaptiveInfo()
    val windowSizeClass = windowAdaptiveInfo.windowSizeClass
    val isLargeScreen = windowSizeClass.windowWidthSizeClass != WindowWidthSizeClass.COMPACT

    // Smart Active State Logic
    val todayName = remember {
        val now = LocalDateTime.now()
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        "${now.format(formatter)}.md"
    }
    val isViewingDailyNote = uiState.selectedFileName == todayName

    val baseDirective = calculatePaneScaffoldDirective(windowAdaptiveInfo)
    val finalDirective = if (uiState.isFocusMode && isLargeScreen) {
        baseDirective.copy(
            maxHorizontalPartitions = 1,
            horizontalPartitionSpacerSize = 0.dp,
            verticalPartitionSpacerSize = 0.dp
        )
    } else {
        baseDirective.copy(
            horizontalPartitionSpacerSize = 24.dp,
            defaultPanePreferredWidth = uiState.listPaneWidth.dp // DYNAMIC WIDTH
        )
    }

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
    
    // Auto-navigate to Import if state present
    LaunchedEffect(uiState.importState) {
        if (uiState.importState != null) {
            navController.navigate("import")
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
    
    // Auto-Close logic for External Imports
    LaunchedEffect(uiState.isExitPending) {
        if (uiState.isExitPending) {
            (context as? android.app.Activity)?.finish()
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
            title = { Text("Supprimer ce fichier ?") },
            text = { Text("Cette action est irréversible et supprimera le fichier du coffre-fort et du dépôt Git.") },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { viewModel.confirmDeleteFile() },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Supprimer")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { viewModel.dismissDeleteConfirmation() }) {
                    Text("Annuler")
                }
            }
        )
    }

    androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold(
        navigationSuiteItems = {
            item(
                selected = currentRoute == Screen.Home && !isViewingDailyNote,
                onClick = { 
                    navController.navigate(Screen.Home) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(Icons.Default.Dashboard, contentDescription = "Home") },
                label = { Text("Repo") }
            )
            item(
                selected = currentRoute == Screen.Chat,
                onClick = { 
                    navController.navigate(Screen.Chat) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(Icons.Outlined.Psychology, contentDescription = "Brain") },
                label = { Text("Chat") }
            )
            item(
                 selected = currentRoute == Screen.DailyNote, 
                 onClick = { 
                     navController.navigate(Screen.DailyNote) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                     }
                 },
                 icon = { Icon(Icons.Default.Today, contentDescription = "Today") },
                 label = { Text("Daily") }
            )
            item(
                selected = currentRoute == "habits",
                onClick = { 
                    navController.navigate("habits") {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(Icons.Default.DateRange, contentDescription = "Habits") },
                label = { Text("Habits") }
            )
            item(
                selected = currentRoute == Screen.HomeOS,
                onClick = { 
                    navController.navigate(Screen.HomeOS) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(Icons.Filled.CleaningServices, contentDescription = "Home OS") },
                label = { Text("Home OS") }
            )

            
            if (isLargeScreen) {
                item(
                    selected = currentRoute == Screen.MoodTracker,
                    onClick = {
                        navController.navigate(Screen.MoodTracker) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(Icons.Default.Mood, contentDescription = "Mood") },
                    label = { Text("Mood") }
                )
                item(
                    selected = currentRoute == Screen.Stats,
                    onClick = {
                        navController.navigate(Screen.Stats) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(Icons.Default.Star, contentDescription = "Stats") },
                    label = { Text("Stats") }
                )
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            androidx.navigation.compose.NavHost(
            navController = navController,
            startDestination = Screen.DailyNote,
            modifier = Modifier.fillMaxSize()
        ) {
            composable(Screen.Home) {
                // Home with ListDetailPaneScaffold, Breadcrumbs, and Global TopBar
                
                val canNavigateBack = navigator.canNavigateBack()
                val isSubFolder = uiState.currentPath.isNotEmpty()

                if (uiState.showCreateFileDialog) {
                    cloud.wafflecommons.pixelbrainreader.ui.components.NewFileBottomSheet(
                        availableTemplates = uiState.availableTemplates,
                        onDismiss = { viewModel.dismissCreateFileDialog() },
                        onCreate = { name, template -> viewModel.createNewFile(name, template) }
                    )
                }

                // Back Handler for Home Logic
                val isBackHandlerEnabled = uiState.isFocusMode || canNavigateBack || isSubFolder
                
                BackHandler(enabled = isBackHandlerEnabled) {
                    when {
                        uiState.isFocusMode -> viewModel.toggleFocusMode()
                        canNavigateBack -> navigator.navigateBack()
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

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
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
                                                 IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
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
                                        },
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
                                        // Edit/View Toggle
                                        FilledTonalIconButton(
                                            onClick = { viewModel.toggleEditMode() },
                                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        ) {
                                            Icon(
                                                imageVector = if (uiState.isEditing) Icons.Filled.Visibility else Icons.Filled.Edit,
                                                contentDescription = if (uiState.isEditing) "View" else "Edit"
                                            )
                                        }

                                        // Delete
                                        FilledTonalIconButton(
                                            onClick = { viewModel.requestDeleteFile() },
                                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Delete File",
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }

                                        // Focus Mode (Large Screen Only)
                                        if (isLargeScreen) {
                                            FilledTonalIconButton(
                                                onClick = { viewModel.toggleFocusMode() },
                                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            ) {
                                                Icon(
                                                    imageVector = if (uiState.isFocusMode) Icons.Filled.CloseFullscreen else Icons.Filled.OpenInFull,
                                                    contentDescription = "Focus Mode"
                                                )
                                            }
                                        }

                                        // Close File
                                        FilledTonalIconButton(
                                            onClick = {
                                                viewModel.closeFile()
                                                if (navigator.canNavigateBack()) {
                                                    navigator.navigateBack()
                                                }
                                            },
                                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        ) {
                                            Icon(Icons.Filled.Close, "Close")
                                        }

                                    } else {
                                        // Search Trigger
                                        FilledTonalIconButton(
                                            onClick = { isSearching = true },
                                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        ) {
                                            Icon(Icons.Default.Search, "Search")
                                        }

                                        // Browser Actions
                                        FilledTonalIconButton(
                                            onClick = { viewModel.openCreateFileDialog() },
                                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        ) {
                                            Icon(Icons.Default.Add, "New File")
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
                                    AnimatedVisibility(
                                        visible = !uiState.isFocusMode || !isLargeScreen,
                                        enter = slideInHorizontally(),
                                        exit = slideOutHorizontally()
                                    ) {
                                        Row(modifier = Modifier.fillMaxSize()) {
                                            Box(modifier = Modifier.weight(1f)) {
                                                FileListPane(
                                                    files = uiState.files,
                                                    isLoading = uiState.isLoading,
                                                    isRefreshing = uiState.isRefreshing,
                                                    error = uiState.error,
                                                    currentPath = uiState.currentPath,
                                                    searchQuery = uiState.searchQuery, // PASS QUERY
                                                    showMenuIcon = false,
                                                    availableMoveDestinations = uiState.availableMoveDestinations,
                                                    moveDialogCurrentPath = uiState.moveDialogCurrentPath,
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
                                                    onMoveFile = { file, folder -> viewModel.moveFile(file, folder) },
                                                    onPrepareMove = { file -> viewModel.prepareMove(file) },
                                                    onMoveNavigateTo = { path -> viewModel.navigateToMoveFolder(path) },
                                                    onMoveNavigateUp = { viewModel.navigateUpMoveFolder() },
                                                    onAnalyzeFolder = { viewModel.analyzeCurrentFolder() }
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
                                    if (uiState.selectedFileName != null) {
                                        FileDetailPane(
                                            content = uiState.unsavedContent
                                                ?: uiState.selectedFileContent,
                                            onContentChange = { viewModel.onContentChanged(it) },
                                            fileName = uiState.selectedFileName,
                                            isLoading = uiState.isLoading,
                                            isRefreshing = uiState.isRefreshing,
                                            onRefresh = { viewModel.refreshCurrentFile() },
                                            isExpandedScreen = isLargeScreen,
                                            isEditing = uiState.isEditing,
                                            hasUnsavedChanges = uiState.hasUnsavedChanges,
                                            saveState = uiState.saveState,
                                            onWikiLinkClick = { target -> viewModel.onWikiLinkClick(target) },
                                            onCreateNew = { viewModel.createNewFile() },
                                            moodViewModel = moodViewModel,
                                            onSave = { viewModel.saveFile() }
                                        )
                                    } else {
                                        cloud.wafflecommons.pixelbrainreader.ui.components.WelcomeScreen()
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
                    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                    contentWindowInsets = WindowInsets(0,0,0,0) // ChatPanel handles its own insets
                ) { padding ->
                    Box(modifier = Modifier.padding(padding)) {
                         cloud.wafflecommons.pixelbrainreader.ui.ai.ChatPanel(
                            onInsertContent = { text ->
                                android.util.Log.d("PixelBrain", "ChatPanel onInsertContent triggered. Saving to Inbox.")
                                viewModel.saveChatToInbox(text)
                            }
                        )
                    }
                }
            }

            composable(Screen.Settings) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateToHabitConfig = { navController.navigate("habit_config") },
                    onNavigateToHomeConfig = { navController.navigate(Screen.ROUTE_HOME_CONFIG) }
                )
            }

            composable("habit_config") {
                cloud.wafflecommons.pixelbrainreader.ui.settings.HabitConfigScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }


            composable(Screen.HomeOS) {
                cloud.wafflecommons.pixelbrainreader.ui.homeos.ChoreDashboardScreen()
            }

            composable(Screen.Import) {
                // Intercept System Back to clear state
                BackHandler {
                    viewModel.dismissImport()
                    navController.popBackStack()
                }

                if (uiState.importState != null) {
                    cloud.wafflecommons.pixelbrainreader.ui.components.ImportScreen(
                        initialTitle = uiState.importState!!.title,
                        initialContent = uiState.importState!!.content,
                        onDismiss = { 
                            viewModel.dismissImport()
                            navController.popBackStack()
                        },
                        onSave = { name, folder, content -> 
                            viewModel.confirmImport(name, folder, content)
                            navController.popBackStack()
                        }
                    )
                } else {
                    // Fallback if state lost
                    LaunchedEffect(Unit) { navController.popBackStack() }
                }
            }

            composable(Screen.DailyNote) {
                val dailyViewModel: cloud.wafflecommons.pixelbrainreader.ui.daily.DailyNoteViewModel = hiltViewModel()
                
                // Refresh Trigger: When Sync finishes, reload Daily View
                // We use isSyncing going from True -> False
                val isSyncing = uiState.isSyncing
                val currentIsSyncing by androidx.compose.runtime.rememberUpdatedState(isSyncing)
                
                LaunchedEffect(isSyncing) {
                     if (!isSyncing) { 
                         // Sync Finished. Room Flow naturally updates UI.
                         moodViewModel.refreshData() 
                     }
                }
            
                cloud.wafflecommons.pixelbrainreader.ui.daily.DailyNoteScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onEditClicked = { path ->
                        viewModel.onTodayClicked(path, startEditing = true)
                    },
                    onCheckInClicked = { showMoodSheet = true },
                    isGlobalSyncing = uiState.isSyncing,
                    viewModel = dailyViewModel,
                    onOpenHabits = { navController.navigate("habits") },
                    onNavigateToSettings = { navController.navigate(Screen.Settings) }
                )
            }
            
            composable("habits") {
                cloud.wafflecommons.pixelbrainreader.ui.lifeos.HabitDashboardScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToStats = { navController.navigate(Screen.Stats) }
                )
            }
            
            composable(Screen.Stats) {
                 cloud.wafflecommons.pixelbrainreader.ui.lifestats.LifeStatsScreen(
                     onNavigateBack = { navController.popBackStack() }
                 )
            }
            

            composable(Screen.ROUTE_HOME_CONFIG) {
                cloud.wafflecommons.pixelbrainreader.ui.homeconfig.HomeConfigScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
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
}


