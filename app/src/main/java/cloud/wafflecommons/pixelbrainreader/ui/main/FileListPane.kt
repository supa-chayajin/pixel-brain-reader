package cloud.wafflecommons.pixelbrainreader.ui.main

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.ui.theme.NavBarClearance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import cloud.wafflecommons.pixelbrainreader.data.remote.model.GithubFileDto

private enum class FileListState { Loading, Error, Empty, Content }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileListPane(
    files: List<GithubFileDto>,
    isLoading: Boolean,
    isRefreshing: Boolean,
    statusText: String? = null,
    searchQuery: String = "", // Added for Search Feedback
    error: String?,
    currentPath: String,
    showMenuIcon: Boolean,
    onFileClick: (GithubFileDto) -> Unit,
    onFolderClick: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onMenuClick: () -> Unit,
    onRefresh: () -> Unit,
    onCreateFile: () -> Unit,
    onRenameFile: (String, GithubFileDto) -> Unit,
    onDeleteFile: (GithubFileDto) -> Unit,
    onAnalyzeFolder: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    
    // Dialog States
    var showRenameDialog by remember { mutableStateOf<GithubFileDto?>(null) }
    // Rename Dialog
    if (showRenameDialog != null) {
        val file = showRenameDialog!!
        var newName by remember { mutableStateOf(file.name.removeSuffix(".md")) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text("Rename File") },
            shape = MaterialTheme.shapes.extraLarge,
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) {
                        onRenameFile(newName, file)
                        showRenameDialog = null
                    }
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) { Text("Cancel") }
            }
        )
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Content
        val listState = when {
            (isLoading || isRefreshing) && files.isEmpty() -> FileListState.Loading
            error != null && files.isEmpty() -> FileListState.Error
            files.isEmpty() -> FileListState.Empty
            else -> FileListState.Content
        }
        Crossfade(
            targetState = listState,
            label = "file_list_state",
            modifier = Modifier.fillMaxSize()
        ) { state ->
            when (state) {
                FileListState.Loading -> {
                    // Skeleton State
                    cloud.wafflecommons.pixelbrainreader.ui.components.SkeletonFileList()
                }
                FileListState.Error -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
             Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Error: $error",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onRefresh()
                }, shape = CircleShape) {
                    Text("Retry")
                }
            }
                    }
                }
                FileListState.Empty -> {
             if (searchQuery.isNotEmpty()) {
                 // NO RESULTS STATE
                 Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SearchOff, // "no results" reads clearer than a plain magnifier
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "No results found",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "No files match \"$searchQuery\".\nTry a different keyword.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
             } else {
                 // "Ready to Work" Empty State (Default)
                 Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Description, 
                        contentDescription = null, 
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.primaryContainer
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "Ready to work",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Select a file from the list or create a new one to get started.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(32.dp))
                    Button(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onCreateFile()
                    }, shape = CircleShape) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Create new file")
                    }
                }
             }
                }
                FileListState.Content -> {
            cloud.wafflecommons.pixelbrainreader.ui.components.PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onRefresh()
                },
                statusText = statusText,
                modifier = Modifier.fillMaxSize()
            ) {
                val filteredFiles = remember(files, currentPath) { 
                    files.filter { it.name != "." && it.path != currentPath }
                }

                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = NavBarClearance),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Header: Analyze Folder
                    item(key = "analyze_btn") {
                        FilledTonalButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onAnalyzeFolder()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                            ),
                            shape = CircleShape
                        ) {
                             Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(18.dp))
                             Spacer(Modifier.width(8.dp))
                             Text("Analyze Folder")
                        }
                    }

                    items(filteredFiles, key = { it.path }) { file ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { dismissValue ->
                                when (dismissValue) {
                                    SwipeToDismissBoxValue.StartToEnd -> {
                                        // Rename
                                        showRenameDialog = file
                                        false 
                                    }
                                    SwipeToDismissBoxValue.EndToStart -> {
                                        // Delete
                                        onDeleteFile(file)
                                        false
                                    }
                                    else -> false
                                }
                            }
                        )

                        SwipeToDismissBox(
                            state = dismissState,
                            modifier = Modifier.animateItem(),
                            backgroundContent = {
                                // Key the affordance off the LIVE swipe direction, not targetValue.
                                // targetValue stays Settled until the (deliberately deep) trigger
                                // threshold is crossed, so it used to show the wrong (Edit) icon,
                                // left-aligned, during a delete swipe until you passed the trigger.
                                // dismissDirection reflects the drag sign immediately, so the right
                                // icon/colour/side appear from the first pixel — before the trigger.
                                val direction = dismissState.dismissDirection
                                val color by animateColorAsState(
                                    when (direction) {
                                        SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primaryContainer
                                        SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                                        else -> MaterialTheme.colorScheme.surfaceContainerLow
                                    },
                                    label = "swipeBackground"
                                )
                                val icon = when (direction) {
                                    SwipeToDismissBoxValue.StartToEnd -> Icons.Default.Edit
                                    SwipeToDismissBoxValue.EndToStart -> Icons.Default.Delete
                                    else -> null
                                }
                                val alignment = when (direction) {
                                    SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                                    SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                                    else -> Alignment.CenterStart
                                }
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .clip(MaterialTheme.shapes.large)
                                        .background(color)
                                        .padding(horizontal = 24.dp),
                                    contentAlignment = alignment
                                ) {
                                    if (icon != null) {
                                        Icon(
                                            icon,
                                            contentDescription = if (direction == SwipeToDismissBoxValue.EndToStart) "Delete" else "Rename",
                                            tint = if (direction == SwipeToDismissBoxValue.EndToStart) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            }
                        ) {
                            FileItemCard(file = file, onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) 
                                if (file.type == "dir") onFolderClick(file.path) else onFileClick(file)
                            })
                        }
                    }
                }
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
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (file.type == "dir") Icons.Default.Folder else Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = if (file.type == "dir") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (file.lastModified != null) {
                    val dateFormatted = remember(file.lastModified) {
                        java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
                            .format(java.util.Date(file.lastModified))
                    }
                    Text(
                        text = dateFormatted,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}


