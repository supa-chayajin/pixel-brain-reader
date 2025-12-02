package cloud.wafflecommons.pixelbrainreader.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel(),
    onLogout: () -> Unit
) {
    val navigator = rememberListDetailPaneScaffoldNavigator<Any>()
    val uiState by viewModel.uiState.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    BackHandler(enabled = navigator.canNavigateBack()) {
        navigator.navigateBack()
    }

    // Handle back navigation for folder structure if we are at the root of the list pane
    // This is a bit tricky with adaptive scaffold, but for now let's assume
    // if we are in List mode and path is not empty, Back should go up.
    // However, BackHandler above captures back press if navigator has history.
    // If navigator history is empty (we are at List view), we check folder depth.
    if (!navigator.canNavigateBack() && uiState.currentPath.isNotEmpty()) {
        BackHandler {
            viewModel.navigateUp()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text("Pixel Brain Reader", modifier = Modifier.padding(all = 16.dp))
                NavigationDrawerItem(
                    label = { Text("Logout") },
                    selected = false,
                    onClick = {
                        viewModel.logout()
                        onLogout()
                    }
                )
            }
        }
    ) {
        ListDetailPaneScaffold(
            directive = navigator.scaffoldDirective,
            value = navigator.scaffoldValue,
            listPane = {
                FileListPane(
                    files = uiState.files,
                    isLoading = uiState.isLoading,
                    onFileClick = { file ->
                        viewModel.loadFile(file)
                        navigator.navigateTo(ListDetailPaneScaffoldRole.Detail)
                    },
                    onFolderClick = { path ->
                        viewModel.loadFolder(path)
                    }
                )
            },
            detailPane = {
                FileDetailPane(
                    content = uiState.selectedFileContent,
                    isLoading = uiState.isLoading
                )
            }
        )
    }
}
