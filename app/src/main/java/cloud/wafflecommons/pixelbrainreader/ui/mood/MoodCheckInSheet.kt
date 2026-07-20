package cloud.wafflecommons.pixelbrainreader.ui.mood

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Canonical activity tags, curated in Settings ▸ Mood Tags and synced via the vault.
    val availableTags by viewModel.availableTags.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
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


    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
        ),
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
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
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
                shape = MaterialTheme.shapes.small,
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
                        text = if (java.time.Duration.between(selectedDateTime, java.time.LocalDateTime.now()).toMinutes() < 1) "Now" else selectedDateTime.format(timeFormatter),
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
                
                if (availableTags.isEmpty()) {
                    Text(
                        text = "No tags yet. Add some in Settings ▸ Mood Tags.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        availableTags.forEach { tag ->
                            val isSelected = selectedActivities.contains(tag)

                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    if (isSelected) selectedActivities.remove(tag)
                                    else selectedActivities.add(tag)
                                },
                                label = { Text(tag) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = tagIcon(tag),
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
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.addMoodEntry(selectedMood, selectedActivities.toList(), noteText, selectedDateTime)
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = MaterialTheme.shapes.medium
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

/**
 * Resolves a leading icon for a mood tag. Known/seed tags keep their original glyph;
 * user-added tags fall back to a generic tag icon (icons can't be persisted, so they're
 * derived from the label here rather than stored alongside the tag).
 */
private fun tagIcon(label: String): ImageVector = when (label) {
    "Coding" -> Icons.Outlined.Code
    "Working" -> Icons.Outlined.WorkHistory
    "Gaming" -> Icons.Outlined.SportsEsports
    "Chilling" -> Icons.Outlined.BeachAccess
    "Solo" -> Icons.Outlined.Person
    "Family" -> Icons.Outlined.FamilyRestroom
    "Friends" -> Icons.Outlined.Groups
    "Home" -> Icons.Outlined.Home
    "Work" -> Icons.Outlined.HomeWork
    "CDS" -> Icons.Outlined.Work
    "Out" -> Icons.Outlined.NaturePeople
    else -> Icons.Outlined.Tag
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MoodSelector(
    selectedMood: Int,
    onMoodSelected: (Int) -> Unit,
    moods: List<Pair<Int, String>>
) {
    val haptic = LocalHapticFeedback.current
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
    ) {
        val segmentCount = moods.size
        val segmentWidth = maxWidth / segmentCount
        val indicatorOffset by animateDpAsState(
            targetValue = segmentWidth * (selectedMood - 1),
            animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
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
                    animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                    label = "scale"
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onMoodSelected(score)
                        },
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
