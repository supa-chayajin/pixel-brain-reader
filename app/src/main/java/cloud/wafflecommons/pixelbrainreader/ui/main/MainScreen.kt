package cloud.wafflecommons.pixelbrainreader.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.window.core.layout.WindowWidthSizeClass
import kotlinx.coroutines.launch

import androidx.compose.material3.ExperimentalMaterial3Api

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onLogout: () -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val navigator = rememberListDetailPaneScaffoldNavigator<Any>()
    val scope = rememberCoroutineScope()

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
            defaultPanePreferredWidth = 280.dp
        )
    }

    LaunchedEffect(uiState.isFocusMode) {
        if (uiState.isFocusMode && isLargeScreen) {
            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail)
        }
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val canNavigateBack = navigator.canNavigateBack()
    val isSubFolder = uiState.currentPath.isNotEmpty()

    BackHandler(enabled = canNavigateBack || isSubFolder) {
        if (canNavigateBack) {
            navigator.navigateBack()
        } else {
            viewModel.navigateBack()
        }
    }

    // AJOUT : Surface globale pour appliquer la couleur de fond du thème (Dynamic ou Sage)
    // Cela corrige le bug du fond blanc derrière la liste transparente
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (isLargeScreen) {
            Row(
                modifier = Modifier.padding(start = if (uiState.isFocusMode) 0.dp else 12.dp)
            ) {
                AnimatedVisibility(
                    visible = !uiState.isFocusMode,
                    enter = slideInHorizontally() + expandHorizontally(),
                    exit = slideOutHorizontally() + shrinkHorizontally()
                ) {
                    NavigationRail(
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        header = { Spacer(Modifier.padding(top = 24.dp)) }
                    ) {
                        NavigationRailItem(
                            selected = true, onClick = { },
                            icon = { Icon(Icons.Filled.Dashboard, null) },
                            label = { Text("Docs") },
                            colors = NavigationRailItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        )

                        // V4.0 AI Toggle
                        NavigationRailItem(
                            selected = uiState.isChatOpen, 
                            onClick = { viewModel.toggleChat() },
                            icon = { Icon(Icons.Outlined.Psychology, contentDescription = "Brain") },
                            label = { Text("Brain") },
                            colors = NavigationRailItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.tertiaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        )
                        Spacer(Modifier.padding(bottom = 24.dp))
                    }
                }

                ListDetailPaneScaffold(
                    modifier = Modifier.weight(1f),
                    directive = finalDirective,
                    value = navigator.scaffoldValue,
                    listPane = {
                        if (!uiState.isFocusMode || !isLargeScreen) {
                            FileListPane(
                                files = uiState.files,
                                isLoading = uiState.isLoading,
                                isRefreshing = uiState.isRefreshing,
                                error = uiState.error,
                                currentPath = uiState.currentPath,
                                showMenuIcon = false,
                                onFileClick = { file ->
                                    viewModel.loadFile(file)
                                    scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail) }
                                },
                                onFolderClick = { path -> viewModel.loadFolder(path) },
                                onNavigateUp = { viewModel.navigateUp() },
                                onMenuClick = { },
                                onRefresh = { viewModel.refresh() }
                            )
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
                            onClose = { viewModel.closeFile() }
                        )
                    }
                )
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState, // ... existing drawer config
                drawerContent = {
                    ModalDrawerSheet(
                        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        drawerContentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(end = 56.dp)
                    ) {
                        Text("Pixel Brain", modifier = Modifier.padding(28.dp), style = MaterialTheme.typography.headlineMedium)
                        NavigationDrawerItem(
                            label = { Text("Dashboard") }, selected = true, onClick = { scope.launch { drawerState.close() } },
                            icon = { Icon(Icons.Filled.Dashboard, null) },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding), shape = RoundedCornerShape(50)
                        )
                        NavigationDrawerItem(
                            label = { Text("Brain") }, selected = uiState.isChatOpen, 
                            onClick = { 
                                scope.launch { drawerState.close() }
                                viewModel.toggleChat() 
                            },
                            icon = { Icon(Icons.Outlined.Psychology, contentDescription = "Brain") },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding), shape = RoundedCornerShape(50)
                        )
                    }
                }
            ) {
                // MOBILE CONTENT with FAB
                androidx.compose.material3.Scaffold(
                    floatingActionButton = {
                         androidx.compose.material3.FloatingActionButton(
                             onClick = { viewModel.toggleChat() },
                             containerColor = MaterialTheme.colorScheme.primaryContainer,
                             contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                         ) {
                             Icon(Icons.Outlined.Psychology, contentDescription = "Brain") 
                         }
                    }
                ) { paddingValues ->
                    // MAIN CONTENT (List + Detail)
                    ListDetailPaneScaffold(
                        modifier = Modifier.padding(paddingValues),
                        directive = finalDirective,
                        value = navigator.scaffoldValue,
                        listPane = {
                            if (!uiState.isFocusMode || !isLargeScreen) {
                                FileListPane(
                                    files = uiState.files,
                                    isLoading = uiState.isLoading,
                                    isRefreshing = uiState.isRefreshing,
                                    error = uiState.error,
                                    currentPath = uiState.currentPath,
                                    showMenuIcon = true,
                                    onFileClick = { file ->
                                        viewModel.loadFile(file)
                                        scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail) }
                                    },
                                    onFolderClick = { path -> viewModel.loadFolder(path) },
                                    onNavigateUp = { viewModel.navigateUp() },
                                    onMenuClick = { scope.launch { drawerState.open() } },
                                    onRefresh = { viewModel.refresh() }
                                )
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
                                 isExpandedScreen = false,
                                 isEditing = uiState.isEditing,
                                 onToggleEditMode = { viewModel.toggleEditMode() },
                                 onSaveContent = { _ -> viewModel.saveFile() },
                                 hasUnsavedChanges = uiState.hasUnsavedChanges,
                                 onClose = {
                                     viewModel.closeFile()
                                     if (navigator.canNavigateBack()) {
                                         navigator.navigateBack()
                                     }
                                 }
                             )
                        }
                    )
                }
            }
        }


        // GLOBAL CHAT BOTTOM SHEET (Consistency for Mobile & Large Screen)
        if (uiState.isChatOpen) {
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { viewModel.toggleChat() },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                 cloud.wafflecommons.pixelbrainreader.ui.ai.ChatPanel(
                    modifier = Modifier.fillMaxSize().padding(bottom = 24.dp),
                    onInsertContent = { text -> 
                        viewModel.onContentChanged((uiState.unsavedContent ?: "") + "\n\n" + text)
                        viewModel.toggleChat() 
                    }
                )
            }
        }

        if (uiState.importState != null) {
             cloud.wafflecommons.pixelbrainreader.ui.components.ImportDialog(
                 initialTitle = uiState.importState!!.title,
                 initialContent = uiState.importState!!.content,
                 onDismiss = { viewModel.dismissImport() },
                 onSave = { name, folder, content -> viewModel.confirmImport(name, folder, content) }
             )
        }
    }
}


