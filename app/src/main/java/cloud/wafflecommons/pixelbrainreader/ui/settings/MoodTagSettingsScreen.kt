package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexIconButton
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexTopAppBar
import cloud.wafflecommons.pixelbrainreader.ui.utils.HapticHelper.performHapticClick
import cloud.wafflecommons.pixelbrainreader.ui.utils.HapticHelper.performHapticSuccess
import cloud.wafflecommons.pixelbrainreader.ui.utils.HapticHelper.performHapticTick

/**
 * Curates the canonical mood activity-tag list (add / remove / reorder). The list lives in
 * the vault ([cloud.wafflecommons.pixelbrainreader.data.repository.MoodTagRepository]) and
 * syncs via git; editing it only changes what the mood check-in sheet *offers* going forward
 * — past mood entries keep their own tags.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodTagSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: MoodTagSettingsViewModel = hiltViewModel()
) {
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    var newTag by remember { mutableStateOf("") }
    val view = LocalView.current

    fun commitNewTag() {
        val trimmed = newTag.trim()
        if (trimmed.isNotBlank()) {
            view.performHapticSuccess()
            viewModel.addTag(trimmed)
            newTag = ""
        }
    }

    Scaffold(
        topBar = {
            CortexTopAppBar(
                title = "Mood Tags",
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Activity tags offered when you log a mood. Changes sync to your vault. " +
                    "Existing mood entries keep the tags they were saved with.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Add-a-tag row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it },
                    label = { Text("New tag") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { commitNewTag() }),
                    modifier = Modifier.weight(1f)
                )
                FilledIconButton(onClick = { commitNewTag() }) {
                    Icon(Icons.Default.Add, contentDescription = "Add tag")
                }
            }

            HorizontalDivider()

            if (tags.isEmpty()) {
                Text(
                    text = "No tags yet. Add one above.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    itemsIndexed(tags, key = { _, tag -> tag }) { index, tag ->
                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                                headlineContent = { Text(tag) },
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            enabled = index > 0,
                                            onClick = {
                                                view.performHapticTick()
                                                viewModel.moveTag(index, index - 1)
                                            }
                                        ) {
                                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                                        }
                                        IconButton(
                                            enabled = index < tags.lastIndex,
                                            onClick = {
                                                view.performHapticTick()
                                                viewModel.moveTag(index, index + 1)
                                            }
                                        ) {
                                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                                        }
                                        IconButton(
                                            onClick = {
                                                view.performHapticClick()
                                                viewModel.removeTag(tag)
                                            }
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = "Remove")
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
