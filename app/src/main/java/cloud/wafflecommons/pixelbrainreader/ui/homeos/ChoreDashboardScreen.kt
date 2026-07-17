package cloud.wafflecommons.pixelbrainreader.ui.homeos

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexIconButton
import cloud.wafflecommons.pixelbrainreader.ui.theme.SemanticPalette
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.ui.theme.NavBarClearance
import androidx.hilt.navigation.compose.hiltViewModel
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncState
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ChoreDashboardScreen(
    onNavigateToStats: () -> Unit = {},
    viewModel: ChoreViewModel = hiltViewModel()
) {
    val groupedChores by viewModel.groupedChores.collectAsStateWithLifecycle()
    val syncState by viewModel.isSyncing.collectAsStateWithLifecycle()
    val isRefreshing = syncState is SyncState.Syncing

    val configuration = LocalConfiguration.current

    // Adaptive: More granular columns for foldables/large tablets
    val columns = when {
        configuration.screenWidthDp > 1200 -> 3
        configuration.screenWidthDp > 600 -> 2
        else -> 1
    }

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            cloud.wafflecommons.pixelbrainreader.ui.components.CortexTopAppBar(
                title = "Chores",
                subtitle = "Get something done today",
                actions = {
                    FilledTonalIconButton(
                        onClick = onNavigateToStats,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ShowChart, "View Statistics")
                    }
                }
            )
        }
    ) { padding ->
        cloud.wafflecommons.pixelbrainreader.ui.components.PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.triggerSync() },
            statusText = (syncState as? SyncState.Syncing)?.step?.label,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
        if (groupedChores.isEmpty()) {
            EmptyChoreState(
                modifier = Modifier.fillMaxSize()
            )
        } else {
            val horizontalPadding = if (configuration.screenWidthDp > 840) 32.dp else 16.dp
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(columns),
                contentPadding = PaddingValues(start = horizontalPadding, end = horizontalPadding, top = 16.dp, bottom = NavBarClearance),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalItemSpacing = 16.dp,
                modifier = Modifier
                    .fillMaxSize()
            ) {
                groupedChores.forEach { (roomName, chores) ->
                    item(span = StaggeredGridItemSpan.FullLine) {
                        RoomHeader(roomName = roomName, urgentCount = chores.count { it.statusColor == StatusColor.RED })
                    }

                    if (chores.isEmpty()) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                                ),
                                border = CardDefaults.outlinedCardBorder().copy(
                                    brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
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
                                        tint = SemanticPalette.Success.copy(alpha = 0.6f)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        text = "Everything is clean!",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else {
                        items(chores, key = { it.entity.id }) { choreModel ->
                            Box(Modifier.animateItem()) {
                                ChoreCard(
                                    chore = choreModel,
                                    onDoItClick = {
                                        viewModel.doChore(choreModel.entity.id)
                                    }
                                )
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
fun RoomHeader(roomName: String, urgentCount: Int) {
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 4.dp)
    ) {
        Text(
            text = roomName,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.width(12.dp))
        if (urgentCount > 0) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.extraSmall
            ) {
                Text(
                    text = "$urgentCount URGENT",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChoreCard(chore: ChoreUiModel, onDoItClick: () -> Unit) {
    val barColor = when (chore.statusColor) {
        StatusColor.GREEN -> SemanticPalette.Success
        StatusColor.YELLOW -> SemanticPalette.Warning
        StatusColor.RED -> MaterialTheme.colorScheme.error
    }

    // Animate the progress bar fill
    val targetProgress = (chore.dirtinessPercentage / 100f).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = MaterialTheme.motionScheme.slowSpatialSpec(),
        label = "ProgressAnimation"
    )

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = chore.entity.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Text(
                                text = "+${chore.entity.baseEffort} XP",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Done ${chore.daysElapsed} days ago • Every ${chore.entity.frequencyDays}d",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                CortexIconButton(
                    onClick = onDoItClick,
                    enabled = targetProgress > 0.1f
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "Done",
                        tint = if (targetProgress > 0.1f) barColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.size(32.dp)
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
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Rounded.CleaningServices,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Your home is sparkling.",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Text(
            text = "Configure chores in Settings to start earning XP.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
