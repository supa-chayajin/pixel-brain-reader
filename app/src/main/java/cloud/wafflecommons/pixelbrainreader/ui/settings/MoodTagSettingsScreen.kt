package cloud.wafflecommons.pixelbrainreader.ui.settings

import cloud.wafflecommons.pixelbrainreader.ui.utils.centeredMaxWidth
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
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodTag
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexIconButton
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexTopAppBar
import cloud.wafflecommons.pixelbrainreader.ui.mood.MoodTagIcons
import cloud.wafflecommons.pixelbrainreader.ui.mood.TagIconPickerSheet
import cloud.wafflecommons.pixelbrainreader.ui.utils.HapticHelper.performHapticClick
import cloud.wafflecommons.pixelbrainreader.ui.utils.HapticHelper.performHapticSuccess
import cloud.wafflecommons.pixelbrainreader.ui.utils.HapticHelper.performHapticTick

/**
 * Curates the canonical mood activity-tag list (add / remove / reorder / icon). The list lives in
 * the vault ([cloud.wafflecommons.pixelbrainreader.data.repository.MoodTagRepository]) and
 * syncs via git; editing it only changes what the mood check-in sheet *offers* going forward
 * — past mood entries keep their own tags. A read-only device shows the list without the controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodTagSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: MoodTagSettingsViewModel = hiltViewModel()
) {
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val readOnly = VaultWriteGate.readOnly
    var newTag by rememberSaveable { mutableStateOf("") }
    // The icon picked for the tag being typed; null = its default.
    var newTagIcon by rememberSaveable { mutableStateOf<String?>(null) }
    // Whose icon the picker is choosing: an existing tag's name, or the tag being typed.
    var pickingFor by rememberSaveable { mutableStateOf<String?>(null) }
    var pickingForNewTag by rememberSaveable { mutableStateOf(false) }
    val view = LocalView.current

    fun commitNewTag() {
        val trimmed = newTag.trim()
        if (trimmed.isNotBlank()) {
            view.performHapticSuccess()
            viewModel.addTag(trimmed, newTagIcon)
            newTag = ""
            newTagIcon = null
        }
    }

    val pickedTag = pickingFor?.let { name -> tags.firstOrNull { it.name == name } }
    // A tag removed while its picker is open (here or by a sync) closes the picker.
    LaunchedEffect(pickingFor, pickedTag) {
        if (pickingFor != null && pickedTag == null) pickingFor = null
    }
    if (pickedTag != null) {
        val name = pickedTag.name
        TagIconPickerSheet(
            title = "Icon for “$name”",
            selectedKey = pickedTag.icon?.takeIf { MoodTagIcons.forKey(it) != null },
            defaultKey = MoodTagIcons.defaultKeyFor(name),
            onPick = { key ->
                view.performHapticTick()
                viewModel.setIcon(name, key)
                pickingFor = null
            },
            onUseDefault = {
                view.performHapticTick()
                viewModel.setIcon(name, null)
                pickingFor = null
            },
            onDismiss = { pickingFor = null }
        )
    }
    if (pickingForNewTag) {
        TagIconPickerSheet(
            title = newTag.trim().takeIf { it.isNotEmpty() }?.let { "Icon for “$it”" } ?: "Icon for the new tag",
            selectedKey = newTagIcon,
            defaultKey = MoodTagIcons.defaultKeyFor(newTag.trim()),
            onPick = { key ->
                view.performHapticTick()
                newTagIcon = key
                pickingForNewTag = false
            },
            onUseDefault = {
                view.performHapticTick()
                newTagIcon = null
                pickingForNewTag = false
            },
            onDismiss = { pickingForNewTag = false }
        )
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
                .centeredMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = if (readOnly) {
                    "Read-only device: these are the tags your phone offers when you log a mood. " +
                        "Change them and their icons on your phone."
                } else {
                    "Activity tags offered when you log a mood. Tap an icon to change it. " +
                        "Changes sync to your vault. Existing mood entries keep the tags they were saved with."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!readOnly) {
                // Add-a-tag row: its icon, its name, the add button.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val newIcon = MoodTagIcons.forKey(newTagIcon) ?: MoodTagIcons.resolve(MoodTag(newTag.trim()))
                    FilledTonalIconButton(onClick = {
                        view.performHapticClick()
                        pickingForNewTag = true
                    }) {
                        Icon(newIcon.image, contentDescription = "Choose the new tag's icon")
                    }
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
            }

            HorizontalDivider()

            if (tags.isEmpty()) {
                Text(
                    text = if (readOnly) "No tags yet." else "No tags yet. Add one above.",
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
                    itemsIndexed(tags, key = { _, tag -> tag.name }) { index, tag ->
                        val icon = MoodTagIcons.resolve(tag)
                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                                leadingContent = {
                                    if (readOnly) {
                                        Icon(icon.image, contentDescription = null)
                                    } else {
                                        FilledTonalIconButton(onClick = {
                                            view.performHapticClick()
                                            pickingFor = tag.name
                                        }) {
                                            Icon(icon.image, contentDescription = "Change the icon of ${tag.name}")
                                        }
                                    }
                                },
                                trailingContent = if (readOnly) null else {
                                    {
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
                                                    viewModel.removeTag(tag.name)
                                                }
                                            ) {
                                                Icon(Icons.Default.Close, contentDescription = "Remove")
                                            }
                                        }
                                    }
                                }
                            ) {
                                Text(tag.name)
                            }
                        }
                    }
                }
            }
        }
    }
}
