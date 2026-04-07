package cloud.wafflecommons.pixelbrainreader.ui.mood

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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MoodCheckInSheet(
    onDismiss: () -> Unit,
    viewModel: MoodViewModel = hiltViewModel()
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
                ActivityItem("Chilling", Icons.Outlined.BeachAccess),
                ActivityItem("Smoking", Icons.Outlined.SmokingRooms),
                ActivityItem("Working", Icons.Outlined.WorkHistory)
            )),
            ActivityCategory("Social & Vibe", listOf(
                ActivityItem("Solo", Icons.Outlined.Person),
                ActivityItem("Family", Icons.Outlined.FamilyRestroom),
                ActivityItem("Friends", Icons.Outlined.Groups)
            )),
            ActivityCategory("Location", listOf(
                ActivityItem("Home", Icons.Outlined.Home),
                ActivityItem("CDS", Icons.Outlined.Work),
                ActivityItem("Out", Icons.Outlined.NaturePeople),
                ActivityItem("Work", Icons.Outlined.HomeWork)
            ))
        )
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
            Text(
                text = "How are you right now?",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            // --- Date/Time Selector ---
            var selectedDateTime by remember { mutableStateOf(java.time.LocalDateTime.now()) }
            val context = androidx.compose.ui.platform.LocalContext.current
            
            val timeFormatter = java.time.format.DateTimeFormatter.ofPattern("dd MMM, HH:mm")
            
            Surface(
                onClick = {
                    val datePickerDialog = android.app.DatePickerDialog(
                        context,
                        { _, year, month, dayOfMonth ->
                             val newDate = java.time.LocalDate.of(year, month + 1, dayOfMonth)
                             
                             val timePickerDialog = android.app.TimePickerDialog(
                                 context,
                                 { _, hourOfDay, minute ->
                                     val newTime = java.time.LocalTime.of(hourOfDay, minute)
                                     selectedDateTime = java.time.LocalDateTime.of(newDate, newTime)
                                 },
                                 selectedDateTime.hour,
                                 selectedDateTime.minute,
                                 true // is24Hour
                             )
                             timePickerDialog.show()
                        },
                        selectedDateTime.year,
                        selectedDateTime.monthValue - 1,
                        selectedDateTime.dayOfMonth
                    )
                    datePickerDialog.show()
                },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(
                        text = if (java.time.Duration.between(selectedDateTime, java.time.LocalDateTime.now()).toMinutes() < 1) "Maintenant" else selectedDateTime.format(timeFormatter),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

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
                            
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    if (isSelected) selectedActivities.remove(item.label)
                                    else selectedActivities.add(item.label)
                                },
                                label = { Text(item.label) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it },
                label = { Text("Quick Note (Optional)") },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("What's on your mind?") },
                shape = MaterialTheme.shapes.large
            )

            Button(
                onClick = {
                    viewModel.addMoodEntry(selectedMood, selectedActivities.toList(), noteText, selectedDateTime)
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Save Mood", style = MaterialTheme.typography.titleMedium)
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

        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {}

        Box(
            modifier = Modifier
                .padding(4.dp)
                .offset(x = indicatorOffset)
                .width(segmentWidth - 8.dp)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer)
        )

        Row(modifier = Modifier.fillMaxSize()) {
            moods.forEachIndexed { index, (score, label) ->
                val isSelected = selectedMood == score
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
                        modifier = Modifier.scale(scale)
                    )
                }
            }
        }
    }
}
