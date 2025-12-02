package cloud.wafflecommons.pixelbrainreader.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
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
    viewModel: MainViewModel = hiltViewModel(),
    onLogout: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val navigator = rememberListDetailPaneScaffoldNavigator<Any>()
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    
    val windowAdaptiveInfo = currentWindowAdaptiveInfo()
    val isExpanded = windowAdaptiveInfo.windowSizeClass.windowWidthSizeClass == WindowWidthSizeClass.EXPANDED

    BackHandler(enabled = navigator.canNavigateBack()) {
        scope.launch {
            navigator.navigateBack()
        }
    }

    if (!navigator.canNavigateBack() && uiState.currentPath.isNotEmpty()) {
        BackHandler {
            viewModel.navigateUp()
        }
    }

    // Focus Mode Logic (V6)
    val defaultDirective = navigator.scaffoldDirective
    val finalDirective = if (uiState.isFocusMode && isExpanded) {
        defaultDirective.copy(
            maxHorizontalPartitions = 1,
            horizontalPartitionSpacerSize = 0.dp
        )
    } else {
        defaultDirective
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerContentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(360.dp) // V5: Wider drawer
            ) {
                Text(
                    text = "Pixel Brain Reader",
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                NavigationDrawerItem(
                    label = { Text("Logout") },
                    selected = false,
                    onClick = {
                        viewModel.logout()
                        onLogout()
                    },
                    shape = RoundedCornerShape(50), // V5: Stadium shape
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.Transparent,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    modifier = Modifier.padding(horizontal = 12.dp)
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
                    onFileClick = { file ->
                        viewModel.loadFile(file)
                        scope.launch {
                            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail)
                        }
                    },
                    onFolderClick = { path ->
                        viewModel.loadFolder(path)
                    }
                )
            },
            detailPane = {
                FileDetailPane(
                    content = uiState.selectedFileContent,
                    isLoading = uiState.isLoading,
                    isFocusMode = uiState.isFocusMode,
                    onToggleFocusMode = { viewModel.toggleFocusMode() },
                    isExpandedScreen = isExpanded
                )
            }
        )
    }
}
