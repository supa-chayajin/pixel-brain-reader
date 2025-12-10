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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.DrawerValue
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.window.core.layout.WindowWidthSizeClass
import kotlinx.coroutines.launch

import androidx.compose.material3.ExperimentalMaterial3Api

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onLogout: () -> Unit,
    onExitApp: () -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val navigator = rememberListDetailPaneScaffoldNavigator<Any>()
    val scope = rememberCoroutineScope()
    val navController = androidx.navigation.compose.rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val windowAdaptiveInfo: WindowAdaptiveInfo = currentWindowAdaptiveInfo()
    val windowSizeClass = windowAdaptiveInfo.windowSizeClass
    val isLargeScreen = windowSizeClass.windowWidthSizeClass != WindowWidthSizeClass.COMPACT

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

    androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold(
        navigationSuiteItems = {
            item(
                selected = currentRoute == "home",
                onClick = { 
                    navController.navigate("home") {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(Icons.Default.Dashboard, contentDescription = "Home") },
                label = { Text("Docs") }
            )
            item(
                selected = currentRoute == "chat",
                onClick = { 
                    navController.navigate("chat") {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(Icons.Outlined.Psychology, contentDescription = "Brain") },
                label = { Text("Brain") }
            )
        }
    ) {
        androidx.navigation.compose.NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.fillMaxSize()
        ) {
            composable("home") {
                // Home with ListDetailPaneScaffold, Breadcrumbs, and Global TopBar
                
                val canNavigateBack = navigator.canNavigateBack()
                val isSubFolder = uiState.currentPath.isNotEmpty()

                val showExitDialog = remember { mutableStateOf(false) }

                if (showExitDialog.value) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showExitDialog.value = false },
                        title = { Text("Exit & Lock App?") },
                        text = { Text("This will close the application. You will need to authenticate again to open it.") },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = { 
                                showExitDialog.value = false
                                onExitApp() 
                            }) {
                                Text("Exit")
                            }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { showExitDialog.value = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }

                // Back Handler for Home Logic
                BackHandler(enabled = true) {
                    when {
                        uiState.isFocusMode -> viewModel.toggleFocusMode()
                        canNavigateBack -> navigator.navigateBack()
                        isSubFolder -> viewModel.navigateUp()
                        else -> {
                           // Show Exit Dialog instead of default finish
                           showExitDialog.value = true
                        }
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
                        TopAppBar(
                            title = { 
                                cloud.wafflecommons.pixelbrainreader.ui.components.BreadcrumbBar(
                                    currentPath = uiState.currentPath,
                                    onPathClick = { path -> viewModel.loadFolder(path) },
                                    onHomeClick = { viewModel.loadFolder("") },
                                    isLargeScreen = isLargeScreen
                                )
                            },
                            actions = {
                                if (uiState.selectedFileName != null) {
                                    // -- Editor Actions --
                                    
                                    // Save
                                    IconButton(
                                        onClick = { viewModel.saveFile() },
                                        enabled = uiState.hasUnsavedChanges
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Save,
                                            contentDescription = "Save",
                                            tint = if (uiState.hasUnsavedChanges) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                        )
                                    }
                                    
                                    // Edit/View Toggle
                                    IconButton(onClick = { viewModel.toggleEditMode() }) {
                                        Icon(
                                            imageVector = if (uiState.isEditing) Icons.Filled.Visibility else Icons.Filled.Edit,
                                            contentDescription = if (uiState.isEditing) "View" else "Edit"
                                        )
                                    }

                                    // Focus Mode (Large Screen Only)
                                    if (isLargeScreen) {
                                        IconButton(onClick = { viewModel.toggleFocusMode() }) {
                                            Icon(
                                                imageVector = if (uiState.isFocusMode) Icons.Filled.CloseFullscreen else Icons.Filled.OpenInFull,
                                                contentDescription = "Focus Mode"
                                            )
                                        }
                                    }

                                    // Close File
                                    IconButton(onClick = {
                                        viewModel.closeFile()
                                        if (navigator.canNavigateBack()) {
                                            navigator.navigateBack()
                                        }
                                    }) {
                                        Icon(Icons.Filled.Close, "Close")
                                    }

                                } else {
                                    // -- Browser Actions --
                                    IconButton(onClick = { viewModel.createNewFile() }) {
                                        Icon(Icons.Default.Add, "New File")
                                    }
                                }
                            }
                        )
                    },

                    contentWindowInsets = WindowInsets(0, 0, 0, 0) // We handle insets in content (List/Detail)
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
                                                    onRefresh = { viewModel.refresh() },
                                                    onCreateFile = { viewModel.createNewFile() },
                                                    onRenameFile = { newName, file -> viewModel.renameFile(newName, file) },
                                                    onMoveFile = { file, folder -> viewModel.moveFile(file, folder) },
                                                    onPrepareMove = { file -> viewModel.prepareMove(file) },
                                                    onMoveNavigateTo = { path -> viewModel.navigateToMoveFolder(path) },
                                                    onMoveNavigateUp = { viewModel.navigateUpMoveFolder() }
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
                                    FileDetailPane(
                                        content = uiState.unsavedContent ?: uiState.selectedFileContent,
                                        onContentChange = { viewModel.onContentChanged(it) },
                                        fileName = uiState.selectedFileName,
                                        isLoading = uiState.isLoading,
                                        isRefreshing = uiState.isRefreshing,
                                        onRefresh = { viewModel.refreshCurrentFile() },
                                        isFocusMode = uiState.isFocusMode,
                                        onToggleFocusMode = { viewModel.toggleFocusMode() },
                                        isExpandedScreen = isLargeScreen,
                                        isEditing = uiState.isEditing,
                                        onToggleEditMode = { viewModel.toggleEditMode() },
                                        onSaveContent = { _ -> viewModel.saveFile() },
                                        hasUnsavedChanges = uiState.hasUnsavedChanges,
                                        onClose = {
                                            viewModel.closeFile()
                                            if (navigator.canNavigateBack()) {
                                                navigator.navigateBack()
                                            }
                                        },
                                        onRename = { newName -> viewModel.renameFile(newName) },
                                        onCreateNew = { viewModel.createNewFile() }
                                    )
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

            composable("chat") {
                 cloud.wafflecommons.pixelbrainreader.ui.ai.ChatPanel(
                    onInsertContent = { text ->
                        // Handle insert: Navigate to Home and insert?
                        // Or just copy to clipboard?
                        // For now, let's append to currently open file if any in ViewModel
                        // This logic might need refinement since we are on a different screen.
                        // Assuming ViewModel holds state of "Open File" globally.
                         viewModel.onContentChanged((uiState.unsavedContent ?: "") + "\n\n" + text)
                         navController.navigate("home")
                    }
                )
            }

            composable("import") {
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
        }
    }
}


