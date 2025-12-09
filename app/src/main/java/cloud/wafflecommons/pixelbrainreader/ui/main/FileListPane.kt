package cloud.wafflecommons.pixelbrainreader.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import cloud.wafflecommons.pixelbrainreader.data.remote.model.GithubFileDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileListPane(
    files: List<GithubFileDto>,
    isLoading: Boolean,
    isRefreshing: Boolean,
    error: String?,
    currentPath: String,
    showMenuIcon: Boolean, // Deprecated? Kept for sig compat
    onFileClick: (GithubFileDto) -> Unit,
    onFolderClick: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onMenuClick: () -> Unit,
    onRefresh: () -> Unit,
    onCreateFile: () -> Unit
) {
    // Haptics
    val haptic = LocalHapticFeedback.current

    if (error != null && !isRefreshing && files.isNotEmpty()) {
         // Side-effect: Vibrate on error if just happened?
         // Hard to track "just happened". Skipping for now to avoid loops.
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Content
        if ((isLoading || isRefreshing) && files.isEmpty()) {
             // Skeleton State
             cloud.wafflecommons.pixelbrainreader.ui.components.SkeletonFileList()
        } else if (error != null && files.isEmpty()) {
             Column(
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Error: $error", // Assuming error is a String, not an object with a 'message' property
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onRefresh()
                }) {
                    Text("Retry")
                }
            }
        } else if (files.isEmpty()) {
             cloud.wafflecommons.pixelbrainreader.ui.components.EmptyState(
                 onActionClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onCreateFile()
                 }
             )
        } else {
            cloud.wafflecommons.pixelbrainreader.ui.components.PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onRefresh()
                },
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    val filteredFiles = files.filter { it.name != "." && it.path != currentPath }
                    items(filteredFiles) { file ->
                        FileItemCard(file = file, onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) // Selection
                            if (file.type == "dir") onFolderClick(file.path) else onFileClick(file)
                        })
                    }
                }
            }
        }
    }
}

@Composable
fun FileItemCard(file: GithubFileDto, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (file.type == "dir") Icons.Default.Folder else Icons.AutoMirrored.Filled.Article,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}


