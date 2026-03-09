package cloud.wafflecommons.pixelbrainreader.ui.homeos

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun ChoreDashboardScreen(
    viewModel: ChoreViewModel = hiltViewModel()
) {
    val groupedChores by viewModel.groupedChores.collectAsState()
    val configuration = LocalConfiguration.current
    val haptic = LocalHapticFeedback.current

    var showAddSheet by remember { mutableStateOf(false) }

    // Adaptive: 1 column on phones, 2 columns on tablets, 3 columns on expanded folds/large tablets
    val columns = when {
        configuration.screenWidthDp > 840 -> 3
        configuration.screenWidthDp > 600 -> 2
        else -> 1
    }

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text("Home OS", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddSheet = true },
                icon = { Icon(Icons.Rounded.Add, contentDescription = "Add Chore") },
                text = { Text("Add Chore") }
            )
        }
    ) { padding ->
        if (groupedChores.isEmpty()) {
            EmptyChoreState(
                modifier = Modifier.padding(padding),
                onAddClick = { showAddSheet = true }
            )
        } else {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(columns),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalItemSpacing = 16.dp,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                groupedChores.forEach { (roomName, chores) ->
                    item(span = StaggeredGridItemSpan.FullLine) {
                        RoomHeader(roomName = roomName, urgentCount = chores.count { it.statusColor == StatusColor.RED })
                    }
                    
                    if (chores.isEmpty()) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                ),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Rounded.CheckCircle, 
                                        contentDescription = null, 
                                        tint = Color(0xFF4CAF50).copy(alpha = 0.6f)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        text = "Tout est propre !",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else {
                        items(chores, key = { it.entity.id }) { choreModel ->
                            ChoreCard(
                                chore = choreModel,
                                onDoItClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.doChore(choreModel.entity.id)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddSheet) {
        AddChoreBottomSheet(
            onDismiss = { showAddSheet = false },
            viewModel = viewModel
        )
    }
}

@Composable
fun RoomHeader(roomName: String, urgentCount: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp)
    ) {
        Text(
            text = roomName,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        if (urgentCount > 0) {
            Spacer(modifier = Modifier.width(8.dp))
            Badge(containerColor = MaterialTheme.colorScheme.error) {
                Text(
                    text = "$urgentCount Urgent",
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}

@Composable
fun ChoreCard(chore: ChoreUiModel, onDoItClick: () -> Unit) {
    val barColor = when (chore.statusColor) {
        StatusColor.GREEN -> Color(0xFF4CAF50)
        StatusColor.YELLOW -> Color(0xFFFFC107)
        StatusColor.RED -> MaterialTheme.colorScheme.error
    }

    // Animate the progress bar fill
    val targetProgress = (chore.dirtinessPercentage / 100f).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(1000),
        label = "ProgressAnimation"
    )

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = chore.entity.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Last done ${chore.daysElapsed} days ago • Every ${chore.entity.frequencyDays}d",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onDoItClick,
                    enabled = targetProgress > 0.1f // Very simple throttle if it's too clean
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "Do It",
                        tint = if (targetProgress > 0.1f) barColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Entropy Visualizer
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(MaterialTheme.shapes.small),
                color = barColor,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
            )
            
            // Text representation of urgency if critically dirty
            if (chore.statusColor == StatusColor.RED) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Requires urgent attention",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun EmptyChoreState(
    modifier: Modifier = Modifier,
    onAddClick: () -> Unit
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Rounded.CleaningServices,
            contentDescription = null,
            modifier = Modifier.size(120.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Your household is pristine.",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Configure chores to start earning XP for physical labor.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onAddClick) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Create First Chore")
        }
    }
}
