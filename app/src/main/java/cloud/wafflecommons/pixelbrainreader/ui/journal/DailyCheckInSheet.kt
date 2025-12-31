package cloud.wafflecommons.pixelbrainreader.ui.journal

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DailyCheckInSheet(
    onDismiss: () -> Unit,
    viewModel: DailyCheckInViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedMood by remember { mutableIntStateOf(3) }
    val selectedActivities = remember { mutableStateListOf<String>() }
    var noteText by remember { mutableStateOf("") }

    val moods = listOf(
        Pair(1, "😫"),
        Pair(2, "😞"),
        Pair(3, "😐"),
        Pair(4, "🙂"),
        Pair(5, "🤩")
    )

    data class ActivityItem(val label: String, val icon: ImageVector)
    data class ActivityCategory(val title: String, val items: List<ActivityItem>)

    val categorizedActivities = remember {
        listOf(
            ActivityCategory("Hobbies", listOf(
                ActivityItem("Coding", Icons.Outlined.Code),
                ActivityItem("Gaming", Icons.Outlined.SportsEsports),
                ActivityItem("Relaxing", Icons.AutoMirrored.Outlined.MenuBook),
                ActivityItem("Music", Icons.Outlined.MusicNote),
                ActivityItem("Movie", Icons.Outlined.Movie),
                ActivityItem("TV", Icons.Outlined.Tv),
                ActivityItem("Cinema", Icons.Outlined.Theaters)
            )),
            ActivityCategory("Social & Vibe", listOf(
                ActivityItem("Solo", Icons.Outlined.Person),
                ActivityItem("Family", Icons.Outlined.FamilyRestroom),
                ActivityItem("Friends", Icons.Outlined.Groups),
                ActivityItem("Relaxing", Icons.Outlined.SelfImprovement)
            )),
            ActivityCategory("Location", listOf(
                ActivityItem("House", Icons.Outlined.Home),
                ActivityItem("Office", Icons.Outlined.Work),
                ActivityItem("Outside", Icons.Outlined.NaturePeople)
            ))
        )
    }

    val context = LocalContext.current
    LaunchedEffect(uiState.success) {
        if (uiState.success) {
            uiState.message?.let {
                android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            }
            onDismiss()
            viewModel.resetState()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            // Header
//            Text(
//                text = "How are you right now?",
//                style = MaterialTheme.typography.headlineSmall,
//                fontWeight = FontWeight.Bold
//            )

            // Stadium Mood Selector (Floating Track)
            MoodSelector(
                selectedMood = selectedMood,
                onMoodSelected = { selectedMood = it },
                moods = moods
            )

            // Activities Grid
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = "Activities",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 4.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                categorizedActivities.forEach { category ->
                    Text(
                        text = category.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 12.dp, top = 8.dp)
                    )
                    
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        category.items.forEach { item ->
                            val isSelected = selectedActivities.contains(item.label)
                            
                            ElevatedFilterChip(
                                selected = isSelected,
                                onClick = {
                                    if (isSelected) selectedActivities.remove(item.label)
                                    else selectedActivities.add(item.label)
                                },
                                label = { 
                                    Text(
                                        text = item.label,
                                        style = MaterialTheme.typography.labelLarge
                                    ) 
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                shape = CircleShape,
                                colors = FilterChipDefaults.elevatedFilterChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    labelColor = MaterialTheme.colorScheme.onSurface,
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                elevation = FilterChipDefaults.elevatedFilterChipElevation(elevation = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // Quick Note
//            OutlinedTextField(
//                value = noteText,
//                onValueChange = { noteText = it },
//                label = { Text("Quick Note (Optional)") },
//                modifier = Modifier.fillMaxWidth(),
//                placeholder = { Text("What's on your mind?") },
//                shape = MaterialTheme.shapes.large,
//                colors = OutlinedTextFieldDefaults.colors(
//                    focusedBorderColor = MaterialTheme.colorScheme.primary,
//                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
//                )
//            )

            // Action Button
            Button(
                onClick = {
                    val moodLabel = moods.find { it.first == selectedMood }?.second ?: "😐"
                    viewModel.submitCheckIn(selectedMood, moodLabel, selectedActivities.toList(), noteText)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                enabled = !uiState.isLoading,
                shape = CircleShape
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = "Save",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun MoodSelector(
    selectedMood: Int,
    onMoodSelected: (Int) -> Unit,
    moods: List<Pair<Int, String>>
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
    ) {
        val segmentCount = moods.size
        val segmentWidth = maxWidth / segmentCount
        val indicatorOffset by animateDpAsState(
            targetValue = segmentWidth * (selectedMood - 1),
            animationSpec = spring(stiffness = Spring.StiffnessLow),
            label = "indicatorOffset"
        )

        // Track Background
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {}

        // Animated Indicator (The "Floating Track")
        Box(
            modifier = Modifier
                .padding(4.dp)
                .offset(x = indicatorOffset)
                .width(segmentWidth - 8.dp)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer)
        )

        // Icons Overlay
        Row(modifier = Modifier.fillMaxSize()) {
            moods.forEachIndexed { index, (score, label) ->
                val isSelected = selectedMood == score
                val contentColor by animateColorAsState(
                    targetValue = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer 
                                  else MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "contentColor"
                )
                
                // ZOOM ANIMATION: Scale up selected, slightly scale down others
                val scale by animateFloatAsState(
                    targetValue = if (isSelected) 1.5f else 0.9f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                    label = "scale"
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onMoodSelected(score) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 24.sp,
                        modifier = Modifier.scale(scale),
                        color = contentColor
                    )
                }
            }
        }
    }
}
