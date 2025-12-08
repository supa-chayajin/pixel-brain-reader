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
import cloud.wafflecommons.pixelbrainreader.data.remote.model.GithubFileDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileListPane(
    files: List<GithubFileDto>,
    isLoading: Boolean,
    isRefreshing: Boolean, // New State
    error: String?,
    currentPath: String,
    showMenuIcon: Boolean,
    onFileClick: (GithubFileDto) -> Unit,
    onFolderClick: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onMenuClick: () -> Unit,
    onRefresh: () -> Unit // New Action
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    BreadcrumbRow(currentPath = currentPath, onPathClick = onFolderClick)
                },
                navigationIcon = {},
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer, // Slight contrast on scroll
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        cloud.wafflecommons.pixelbrainreader.ui.components.PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.padding(innerPadding).fillMaxSize()
        ) {
            // Content
            if (isLoading && files.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                     CircularProgressIndicator()
                }
            } else if (error != null && files.isEmpty()) {
                 Column(
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else if (files.isEmpty()) {
                 // Even if empty, show the header title so user knows where they are
                 LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                     item {
                         Text(
                            text = if (currentPath.isEmpty()) "Bibliothèque" else currentPath.substringAfterLast('/'),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 24.dp)
                        )
                     }
                     item {
                         Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                            Text("Aucun fichier trouvé", color = MaterialTheme.colorScheme.onSurfaceVariant)
                         }
                     }
                 }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Header Item: Big Title
                    item {
                        Text(
                            text = if (currentPath.isEmpty()) "Bibliothèque" else currentPath.substringAfterLast('/'),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }

                    val filteredFiles = files.filter { it.name != "." && it.path != currentPath }
                    items(filteredFiles) { file ->
                        FileItemCard(file = file, onClick = {
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

@Composable
fun BreadcrumbRow(currentPath: String, onPathClick: (String) -> Unit) {
    val segments = if (currentPath.isBlank()) emptyList() else currentPath.split("/")
    val accumulatedPaths = segments.runningFold("") { acc, seg ->
        if (acc.isEmpty()) seg else "$acc/$seg"
    }.filter { it.isNotEmpty() }

    androidx.compose.foundation.lazy.LazyRow(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Home Icon Chip
        item {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onPathClick("") }
            ) {
                Icon(
                    imageVector = Icons.Default.Home,
                    contentDescription = "Home",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).size(16.dp)
                )
            }
            if (segments.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        if (segments.size > 2) {
            // Truncated Mode: ... (Dropdown) > Last 2
            item {
                val showDropdown = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                
                Box {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showDropdown.value = true }
                    ) {
                        Text(
                            "...",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    
                    DropdownMenu(
                        expanded = showDropdown.value,
                        onDismissRequest = { showDropdown.value = false }
                    ) {
                        // Show hidden segments (Everything before the last 2)
                        // segments.size > 3. visible is last 2. hidden is everything up to size-2.
                        val hiddenCount = segments.size - 1
                        for (i in 0 until hiddenCount) {
                            val segment = segments[i]
                            val path = accumulatedPaths[i]
                            DropdownMenuItem(
                                text = { Text(segment) },
                                onClick = {
                                    onPathClick(path)
                                    showDropdown.value = false
                                }
                            )
                        }
                    }
                }
                
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }

            val startIndex = segments.size - 1
            val visibleSegments = segments.takeLast(1)

            items(visibleSegments.size) { i ->
                val realIndex = startIndex + i
                val segment = segments[realIndex]
                val path = accumulatedPaths[realIndex]
                val isLast = i == visibleSegments.size - 1

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !isLast) { onPathClick(path) }
                ) {
                    Text(
                        segment,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isLast) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                if (!isLast) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        } else {
            // Full Mode
            items(segments.size) { index ->
                val segment = segments[index]
                val path = accumulatedPaths[index]
                val isLast = index == segments.size - 1

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !isLast) { onPathClick(path) }
                ) {
                    Text(
                        segment,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isLast) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                if (!isLast) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
