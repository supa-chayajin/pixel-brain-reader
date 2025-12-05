package cloud.wafflecommons.pixelbrainreader.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Settings
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

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
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
            horizontalPartitionSpacerSize = 24.dp
        )
    }

    LaunchedEffect(uiState.isFocusMode) {
        if (uiState.isFocusMode && isLargeScreen) {
            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail)
        }
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    BackHandler(enabled = navigator.canNavigateBack()) {
        navigator.navigateBack()
    }

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
                    NavigationRailItem(
                        selected = false, onClick = { },
                        icon = { Icon(Icons.Outlined.Settings, null) },
                        label = { Text("Config") }
                    )
                    Spacer(Modifier.weight(1f))
                    NavigationRailItem(
                        selected = false, onClick = { viewModel.logout(); onLogout() },
                        icon = { Icon(Icons.AutoMirrored.Outlined.Logout, null) },
                        label = { Text("Sortir") }
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
                            error = uiState.error,
                            currentPath = uiState.currentPath,
                            showMenuIcon = false,
                            onFileClick = { file ->
                                viewModel.loadFile(file)
                                scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail) }
                            },
                            onFolderClick = { path -> viewModel.loadFolder(path) },
                            onNavigateUp = { viewModel.navigateUp() }, // CONNEXION DU RETOUR
                            onMenuClick = { }
                        )
                    }
                },
                detailPane = {
                    FileDetailPane(
                        content = uiState.selectedFileContent,
                        fileName = uiState.selectedFileName,
                        isLoading = uiState.isLoading,
                        isFocusMode = uiState.isFocusMode,
                        onToggleFocusMode = { viewModel.toggleFocusMode() },
                        isExpandedScreen = isLargeScreen
                    )
                }
            )
        }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    drawerContentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(end = 56.dp)
                ) {
                    Text("Pixel Brain", modifier = Modifier.padding(28.dp), style = MaterialTheme.typography.headlineMedium)
                    NavigationDrawerItem(
                        label = { Text("Dashboard") }, selected = true, onClick = { scope.launch { drawerState.close() } },
                        icon = { Icon(Icons.Outlined.Dashboard, null) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding), shape = RoundedCornerShape(50)
                    )
                    NavigationDrawerItem(
                        label = { Text("Déconnexion") }, selected = false, onClick = { viewModel.logout(); onLogout() },
                        icon = { Icon(Icons.AutoMirrored.Outlined.Logout, null) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding), shape = RoundedCornerShape(50)
                    )
                }
            }
        ) {
            ListDetailPaneScaffold(
                directive = finalDirective,
                value = navigator.scaffoldValue,
                listPane = {
                    FileListPane(
                        files = uiState.files,
                        isLoading = uiState.isLoading,
                        error = uiState.error,
                        currentPath = uiState.currentPath,
                        showMenuIcon = true,
                        onFileClick = { file ->
                            viewModel.loadFile(file)
                            scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail) }
                        },
                        onFolderClick = { path -> viewModel.loadFolder(path) },
                        onNavigateUp = { viewModel.navigateUp() }, // CONNEXION DU RETOUR
                        onMenuClick = { scope.launch { drawerState.open() } }
                    )
                },
                detailPane = {
                    FileDetailPane(
                        content = uiState.selectedFileContent,
                        fileName = uiState.selectedFileName,
                        isLoading = uiState.isLoading,
                        isFocusMode = uiState.isFocusMode,
                        onToggleFocusMode = { viewModel.toggleFocusMode() },
                        isExpandedScreen = false
                    )
                }
            )
        }
    }
}
