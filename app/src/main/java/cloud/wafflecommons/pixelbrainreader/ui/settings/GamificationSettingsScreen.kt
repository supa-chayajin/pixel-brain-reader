package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.gamification.Attribute
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexTopAppBar
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
    val moodEmojiMapping by viewModel.moodEmojiMapping.collectAsStateWithLifecycle()

    var newTagText by remember { mutableStateOf("") }
    var expandedDropdown by remember { mutableStateOf(false) }
    var selectedAttribute by remember { mutableStateOf(Attribute.VIG) }
    val view = LocalView.current

    Scaffold(
        topBar = {
            CortexTopAppBar(
                title = "RPG Engine Rules",
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            
            // Goals Section
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
                                viewModel.updateStepTarget(newVal) 
                                viewModel.updateSleepMinMinutes(newVal) 
                            },
                            valueRange = 180f..600f, // 3h to 10h
                            steps = 14 // 30 min intervals
                        )
                    }
                }
            }

            // Mood Emojis Section
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Mood Emojis", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        (1..5).forEach { score ->
                            var currentEmoji by remember(moodEmojiMapping) { mutableStateOf(moodEmojiMapping[score] ?: "") }
                            OutlinedTextField(
                                value = currentEmoji,
                                onValueChange = { 
                                    currentEmoji = it.take(2)
                                    viewModel.updateMoodEmojiMapping(score, currentEmoji)
                                },
                                modifier = Modifier.weight(1f),
                                label = { Text("$score") },
                                textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
                                singleLine = true
                            )
                        }
                    }
                }
            }

            // Mappings Section
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
                            InputChip(
                                selected = true,
                                onClick = { },
                                label = { Text("$tag ➡️ ${attribute.name}") },
                                trailingIcon = {
                                    IconButton(
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
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
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

                        IconButton(
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
