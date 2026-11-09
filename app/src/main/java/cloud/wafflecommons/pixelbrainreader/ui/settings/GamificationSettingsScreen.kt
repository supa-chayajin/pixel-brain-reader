package cloud.wafflecommons.pixelbrainreader.ui.settings

import cloud.wafflecommons.pixelbrainreader.ui.utils.centeredMaxWidth
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.gamification.Attribute
import cloud.wafflecommons.pixelbrainreader.data.mood.MoodEmojiSet
import cloud.wafflecommons.pixelbrainreader.data.mood.MoodEmojiSets
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexIconButton
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexTopAppBar
import cloud.wafflecommons.pixelbrainreader.ui.utils.StaggeredEntry
import cloud.wafflecommons.pixelbrainreader.ui.utils.HapticHelper.performHapticClick
import cloud.wafflecommons.pixelbrainreader.ui.utils.HapticHelper.performHapticSuccess
import cloud.wafflecommons.pixelbrainreader.ui.utils.HapticHelper.performHapticTick

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun GamificationSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: GamificationSettingsViewModel = hiltViewModel()
) {
    val stepTarget by viewModel.stepTarget.collectAsStateWithLifecycle()
    val sleepMinMinutes by viewModel.sleepMinMinutes.collectAsStateWithLifecycle()
    val tagMappings by viewModel.tagToStatMapping.collectAsStateWithLifecycle()
    val moodEmojiSet by viewModel.moodEmojiSet.collectAsStateWithLifecycle()

    var newTagText by remember { mutableStateOf("") }
    var expandedDropdown by remember { mutableStateOf(false) }
    var selectedAttribute by remember { mutableStateOf(Attribute.VIG) }
    val view = LocalView.current

    Scaffold(
        topBar = {
            CortexTopAppBar(
                title = "RPG Engine Rules",
                navigationIcon = {
                    CortexIconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .centeredMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            
            // Goals Section
            StaggeredEntry(index = 0) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Health Synergy Goals", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    Column {
                        Text("Target Steps: $stepTarget", style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = stepTarget.toFloat(),
                            onValueChange = { 
                                val newVal = it.toInt()
                                if (newVal != stepTarget && newVal % 1000 == 0) view.performHapticTick()
                                viewModel.updateStepTarget(newVal) 
                            },
                            valueRange = 1000f..20000f,
                            steps = 19
                        )
                    }

                    Column {
                        val hours = sleepMinMinutes / 60
                        val mins = sleepMinMinutes % 60
                        val suffix = if (mins > 0) "h $mins" else "h"
                        Text("Minimum Sleep: $hours$suffix", style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = sleepMinMinutes.toFloat(),
                            onValueChange = { 
                                val newVal = it.toInt()
                                if (newVal != sleepMinMinutes && newVal % 30 == 0) view.performHapticTick()
                                viewModel.updateSleepMinMinutes(newVal) 
                            },
                            valueRange = 180f..600f, // 3h to 10h
                            steps = 14 // 30 min intervals
                        )
                    }
                }
            }
            }

            // Mood Emojis Section: one set of faces for every mood this device shows.
            StaggeredEntry(index = 1) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Mood Emojis", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "The faces of your moods on this device: the check-in, the history, the stats and the widgets. " +
                            "Moods you already logged change face too.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    MoodEmojiSetDropdown(
                        selected = moodEmojiSet,
                        onSelect = { set ->
                            view.performHapticTick()
                            viewModel.selectMoodEmojiSet(set.id)
                        }
                    )
                    // The set at a glance: score 1 (worst) to 5 (best).
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        moodEmojiSet.faces.forEachIndexed { index, face ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(face, fontSize = 28.sp)
                                Text(
                                    "${index + 1}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            }

            // Mappings Section
            StaggeredEntry(index = 2) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Tag Mappings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        tagMappings.forEach { (tag, attribute) ->
                            key(tag) {
                                val chipVisibleState = remember {
                                    MutableTransitionState(false).apply { targetState = true }
                                }
                                AnimatedVisibility(
                                    visibleState = chipVisibleState,
                                    enter = fadeIn() + scaleIn(),
                                    exit = fadeOut() + scaleOut()
                                ) {
                                    InputChip(
                                        selected = true,
                                        onClick = { },
                                        label = { Text("$tag ➡️ ${attribute.name}") },
                                        trailingIcon = {
                                            CortexIconButton(
                                                modifier = Modifier.size(16.dp),
                                                onClick = {
                                                    view.performHapticClick()
                                                    viewModel.removeTagMapping(tag)
                                                }
                                            ) {
                                                Icon(Icons.Default.Close, "Remove")
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Add new mapping
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = newTagText,
                            onValueChange = { 
                                if (it.startsWith("#") || it.isEmpty()) {
                                    newTagText = it 
                                } else {
                                    newTagText = "#$it"
                                }
                            },
                            label = { Text("Tag (e.g. #sport)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )

                        ExposedDropdownMenuBox(
                            expanded = expandedDropdown,
                            onExpandedChange = { expandedDropdown = !expandedDropdown }
                        ) {
                            OutlinedTextField(
                                value = selectedAttribute.name,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Stat") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDropdown) },
                                modifier = Modifier
                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                                    .width(120.dp)
                            )
                            ExposedDropdownMenu(
                                expanded = expandedDropdown,
                                onDismissRequest = { expandedDropdown = false }
                            ) {
                                Attribute.entries.forEach { attr ->
                                    DropdownMenuItem(
                                        text = { Text(attr.name) },
                                        onClick = {
                                            selectedAttribute = attr
                                            expandedDropdown = false
                                        }
                                    )
                                }
                            }
                        }

                        CortexIconButton(
                            onClick = {
                                if (newTagText.isNotBlank()) {
                                    view.performHapticSuccess()
                                    viewModel.addTagMapping(newTagText, selectedAttribute)
                                    newTagText = ""
                                }
                            }
                        ) {
                            Icon(Icons.Default.Add, "Add Mapping")
                        }
                    }
                }
            }
            }
        }
    }
}

/** Picks the device's mood emoji set; each entry shows its five faces. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoodEmojiSetDropdown(selected: MoodEmojiSet, onSelect: (MoodEmojiSet) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.name,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Emoji set") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MoodEmojiSets.ALL.forEach { set ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(set.name, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(16.dp))
                            Text(set.faces.joinToString(" "), fontSize = 20.sp)
                        }
                    },
                    leadingIcon = {
                        // Keeps every name aligned; only the current set shows the check.
                        Box(Modifier.size(24.dp)) {
                            if (set.id == selected.id) Icon(Icons.Default.Check, contentDescription = "Current set")
                        }
                    },
                    onClick = {
                        expanded = false
                        if (set.id != selected.id) onSelect(set)
                    }
                )
            }
        }
    }
}
