@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package cloud.wafflecommons.pixelbrainreader.ui.mood

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodTag
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodTagCategory

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MoodCheckInSheet(
    onDismiss: () -> Unit,
    viewModel: MoodViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Canonical activity tags, curated in Settings ▸ Mood Tags and synced via the vault.
    val availableTags by viewModel.availableTags.collectAsStateWithLifecycle()
    // The faces of the device's mood emoji set (Settings ▸ RPG & Gamification Rules).
    val emojiSet by viewModel.emojiSet.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    var selectedMood by remember { mutableIntStateOf(3) }
    val selectedActivities = remember { mutableStateListOf<String>() }
    var noteText by remember { mutableStateOf("") }

    val moods = remember(emojiSet) { emojiSet.faces.mapIndexed { index, face -> (index + 1) to face } }
    // 12.19 (owner's M5): the mood's color on the picked face, the Save button and a wash at the top.
    val tone = MoodPalette.of(selectedMood, isSystemInDarkTheme())
    val moodColor by animateColorAsState(tone.container, label = "moodColor")
    val onMoodColor by animateColorAsState(tone.content, label = "onMoodColor")
    val surface = MaterialTheme.colorScheme.surface

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
        ),
        // The mood's wash runs from the sheet's top edge, drag handle included, into the surface.
        modifier = Modifier.background(
            Brush.verticalGradient(0f to lerp(surface, moodColor, 0.5f), 0.35f to surface),
            BottomSheetDefaults.ExpandedShape
        ),
        containerColor = Color.Transparent,
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
                moods = moods,
                pillColor = moodColor
            )

            // The tags by category (12.19, owner's M4): a colored card each, empty categories left out.
            if (availableTags.isEmpty()) {
                Text(
                    // 13.4: the tags are on the Moods page (S10).
                    text = "No tags yet. Add some in Settings › Life OS › Moods.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                MoodTagCategories.grouped(availableTags)
                    .filter { (_, tags) -> tags.isNotEmpty() }
                    .forEach { (category, tags) ->
                        TagCategoryCard(
                            category = category,
                            tags = tags,
                            selected = selectedActivities,
                            onToggle = { name ->
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                if (name in selectedActivities) selectedActivities.remove(name) else selectedActivities.add(name)
                            }
                        )
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
                shapes = ButtonDefaults.shapes(shape = MaterialTheme.shapes.medium),
                colors = ButtonDefaults.buttonColors(containerColor = moodColor, contentColor = onMoodColor)
            ) {
                if (uiState.isLoading) {
                    LoadingIndicator(modifier = Modifier.size(24.dp), color = onMoodColor)
                } else {
                    Text("Save Mood", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** One category's tags in a card of its own color (12.19, M4); a picked chip takes the card's strong color. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagCategoryCard(
    category: MoodTagCategory,
    tags: List<MoodTag>,
    selected: List<String>,
    onToggle: (String) -> Unit
) {
    val colors = MoodTagCategories.colors(category, MaterialTheme.colorScheme)
    val picked = tags.count { it.name in selected }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.container,
        contentColor = colors.content
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier.size(32.dp).clip(CircleShape).background(colors.strong),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(MoodTagCategories.icon(category), contentDescription = null, tint = colors.onStrong, modifier = Modifier.size(18.dp))
                }
                Text(MoodTagCategories.title(category), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (picked > 0) {
                    Spacer(Modifier.weight(1f))
                    Text("$picked picked", style = MaterialTheme.typography.labelMedium)
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tags.forEach { tag ->
                    FilterChip(
                        selected = tag.name in selected,
                        onClick = { onToggle(tag.name) },
                        label = { Text(tag.name) },
                        leadingIcon = {
                            Icon(MoodTagIcons.resolve(tag).image, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        shape = CircleShape,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            labelColor = MaterialTheme.colorScheme.onSurface,
                            iconColor = colors.strong,
                            selectedContainerColor = colors.strong,
                            selectedLabelColor = colors.onStrong,
                            selectedLeadingIconColor = colors.onStrong
                        ),
                        border = null
                    )
                }
            }
        }
    }
}

/**
 * The faces on a pill (12.19, M5): bigger faces that bounce, a wiggle when one is picked, and the
 * pill behind the picked face in [pillColor] (the mood's color in the check-in sheet).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MoodSelector(
    selectedMood: Int,
    onMoodSelected: (Int) -> Unit,
    moods: List<Pair<Int, String>>,
    pillColor: Color = MaterialTheme.colorScheme.secondaryContainer
) {
    val haptic = LocalHapticFeedback.current
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
    ) {
        val segmentCount = moods.size
        val segmentWidth = maxWidth / segmentCount
        val indicatorOffset by animateDpAsState(
            targetValue = segmentWidth * (selectedMood - 1),
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            label = "indicatorOffset"
        )

        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {}

        Box(
            modifier = Modifier
                .padding(6.dp)
                .offset { IntOffset(indicatorOffset.roundToPx(), 0) }
                .width(segmentWidth - 12.dp)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(pillColor)
        )

        Row(modifier = Modifier.fillMaxSize()) {
            moods.forEach { (score, face) ->
                val isSelected = selectedMood == score
                val scale by animateFloatAsState(
                    targetValue = if (isSelected) 1.3f else 0.85f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                    label = "faceScale"
                )
                // A little wiggle when a face is picked.
                val tilt = remember { Animatable(0f) }
                LaunchedEffect(isSelected) {
                    if (isSelected) {
                        tilt.snapTo(-16f)
                        tilt.animateTo(0f, spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessLow))
                    }
                }

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
                        text = face,
                        fontSize = 36.sp,
                        modifier = Modifier.graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            rotationZ = tilt.value
                        }
                    )
                }
            }
        }
    }
}
