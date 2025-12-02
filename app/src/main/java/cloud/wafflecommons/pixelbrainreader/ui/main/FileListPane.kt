package cloud.wafflecommons.pixelbrainreader.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.data.remote.model.GithubFileDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileListPane(
    files: List<GithubFileDto>,
    isLoading: Boolean,
    onFileClick: (GithubFileDto) -> Unit,
    onFolderClick: (String) -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface, // V6: Surface background
        topBar = {
            LargeTopAppBar(
                title = { Text("Pixel Brain") },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent, // V6: Transparent
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                ) {
                    items(files) { file ->
                        androidx.compose.material3.Card(
                            onClick = {
                                if (file.type == "dir") {
                                    onFolderClick(file.path)
                                } else {
                                    onFileClick(file)
                                }
                            },
                            shape = RoundedCornerShape(24.dp), // V6: 24dp
                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh // V6: Default
                                // Note: Selection state logic would go here if we tracked selection in this list
                            ),
                            elevation = androidx.compose.material3.CardDefaults.cardElevation(
                                defaultElevation = 0.dp
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = file.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        imageVector = if (file.type == "dir") Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.Article,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                colors = ListItemDefaults.colors(
                                    containerColor = Color.Transparent // Card handles background
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
