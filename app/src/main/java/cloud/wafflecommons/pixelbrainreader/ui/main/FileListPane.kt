package cloud.wafflecommons.pixelbrainreader.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description // Correct import
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import cloud.wafflecommons.pixelbrainreader.data.remote.model.GithubFileDto

@Composable
fun FileListPane(
    files: List<GithubFileDto>,
    isLoading: Boolean,
    onFileClick: (GithubFileDto) -> Unit,
    onFolderClick: (String) -> Unit
) {
    Scaffold { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(files) { file ->
                        ListItem(
                            headlineContent = { Text(file.name) },
                            leadingContent = {
                                Icon(
                                    imageVector = if (file.type == "dir") Icons.Default.Folder else Icons.Default.Description,
                                    contentDescription = null
                                )
                            },
                            modifier = Modifier.clickable {
                                if (file.type == "dir") {
                                    onFolderClick(file.path)
                                } else {
                                    onFileClick(file)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
