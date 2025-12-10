package cloud.wafflecommons.pixelbrainreader.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import cloud.wafflecommons.pixelbrainreader.data.remote.model.GithubFileDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileListPane(
    files: List<GithubFileDto>,
    availableMoveDestinations: List<String>, // Passed from VM (Smart Filtered)
    moveDialogCurrentPath: String, // Added state
    isLoading: Boolean,
    isRefreshing: Boolean,
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
    onMoveFile: (GithubFileDto, String) -> Unit,
    onPrepareMove: (GithubFileDto) -> Unit,
    onMoveNavigateTo: (String) -> Unit,
    onMoveNavigateUp: () -> Unit,
    onAnalyzeFolder: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    
    // Dialog States
    var showRenameDialog by remember { mutableStateOf<GithubFileDto?>(null) }
    var showMoveDialog by remember { mutableStateOf<GithubFileDto?>(null) }
    
    // ... (Dialogs Code kept via context matching or skipped if outside chunk) ... Note: I should be careful not to overwrite the dialogs if I don't select them.
    // Actually the logic is split. I will target the Signature and List Content separately.

    // ... To avoid replacing the whole file, I will split this into chunks using multi_replace is better, but I can't use multi_replace for adding parameter AND modifying body if they are far apart. 
    // I'll do two replaces.

    // 1. Signature Update is handled here if I include the function start.
    // 2. Button Addition is inside LazyColumn.

    // Let's assume I can't easily see the middle lines for Dialogs.
    // I will use `replace_file_content` for the signature first.


    // Rename Dialog
    if (showRenameDialog != null) {
        val file = showRenameDialog!!
        var newName by remember { mutableStateOf(file.name.removeSuffix(".md")) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text("Rename File") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true
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

    // Move Dialog
    if (showMoveDialog != null) {
        val file = showMoveDialog!!
        AlertDialog(
            onDismissRequest = { showMoveDialog = null },
            title = { 
                Column {
                    Text("Move to...")
                    if (moveDialogCurrentPath.isNotEmpty()) {
                        Text(
                            text = "📂 $moveDialogCurrentPath",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                         Text(
                            text = "📂 (Root)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp).fillMaxWidth()
                ) {
                    // Back Button Logic in List if not root
                    if (moveDialogCurrentPath.isNotEmpty()) {
                        item {
                            ListItem(
                                headlineContent = { Text(".. (Up)") },
                                leadingContent = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) },
                                modifier = Modifier.clickable { onMoveNavigateUp() }
                            )
                        }
                    }

                    items(availableMoveDestinations) { folderPath ->
                        val folderName = folderPath.substringAfterLast("/")
                         ListItem(
                            headlineContent = { Text("📂 $folderName") },
                            trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                            modifier = Modifier.clickable { 
                                onMoveNavigateTo(folderPath) // Drill Down
                            }
                        )
                    }
                    if (availableMoveDestinations.isEmpty()) {
                         item {
                             Text(
                                 if (moveDialogCurrentPath.isEmpty()) "No folders found." else "No subfolders.", 
                                 modifier = Modifier.padding(16.dp),
                                 style = MaterialTheme.typography.bodyMedium
                             )
                         }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onMoveFile(file, moveDialogCurrentPath)
                    showMoveDialog = null
                }) {
                    Text("Move Here")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMoveDialog = null }) { Text("Cancel") }
            }
        )
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
                    text = "Error: $error",
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
             // "Ready to Work" Empty State
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
                }) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Create new file")
                }
            }
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
                            )
                        ) {
                             Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(18.dp))
                             Spacer(Modifier.width(8.dp))
                             Text("Analyze Folder")
                        }
                    }

                    val filteredFiles = files.filter { it.name != "." && it.path != currentPath }
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
                                        // Move
                                        showMoveDialog = file
                                        onPrepareMove(file) 
                                        false
                                    }
                                    else -> false
                                }
                            }
                        )

                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = {
                                val direction = dismissState.dismissDirection
                                val color by animateColorAsState(
                                    when (dismissState.targetValue) {
                                        SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primaryContainer 
                                        SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.tertiaryContainer 
                                        else -> MaterialTheme.colorScheme.surfaceContainerLow
                                    }
                                )
                                val icon = when (dismissState.targetValue) {
                                    SwipeToDismissBoxValue.StartToEnd -> Icons.Default.Edit
                                    SwipeToDismissBoxValue.EndToStart -> Icons.Default.FolderOpen
                                    else -> Icons.Default.Edit
                                }
                                val alignment = when (direction) {
                                    SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                                    SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                                    else -> Alignment.CenterStart
                                }
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(24.dp))
                                        .background(color)
                                        .padding(horizontal = 24.dp),
                                    contentAlignment = alignment
                                ) {
                                    Icon(
                                        icon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
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


