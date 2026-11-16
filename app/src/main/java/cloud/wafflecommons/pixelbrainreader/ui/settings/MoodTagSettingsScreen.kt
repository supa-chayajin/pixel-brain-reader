package cloud.wafflecommons.pixelbrainreader.ui.settings

import cloud.wafflecommons.pixelbrainreader.ui.utils.centeredMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodTag
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodTagCategory
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexIconButton
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexTopAppBar
import cloud.wafflecommons.pixelbrainreader.ui.mood.MoodTagCategories
import cloud.wafflecommons.pixelbrainreader.ui.mood.MoodTagIcons
import cloud.wafflecommons.pixelbrainreader.ui.mood.TagIconPickerSheet
import cloud.wafflecommons.pixelbrainreader.ui.utils.HapticHelper.performHapticClick
import cloud.wafflecommons.pixelbrainreader.ui.utils.HapticHelper.performHapticSuccess
import cloud.wafflecommons.pixelbrainreader.ui.utils.HapticHelper.performHapticTick
import androidx.compose.material.icons.rounded.Sell

/**
 * Curates the canonical mood tag list: add, remove, reorder, icon and, since 12.19, the category
 * (Activities, People, Places; the owner's M1). The list lives in the vault
 * ([cloud.wafflecommons.pixelbrainreader.data.repository.MoodTagRepository]) and syncs via git;
 * editing it only changes what the mood check-in sheet *offers* going forward — past mood entries
 * keep their own tags. The tags show under their categories, and up and down move a tag within its
 * category. A read-only device shows the list without the controls.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    // The category picked for the tag being typed; null = the guess from its icon (M3).
    var newTagCategory by rememberSaveable { mutableStateOf<String?>(null) }
    // Whose icon the picker is choosing: an existing tag's name, or the tag being typed.
    var pickingFor by rememberSaveable { mutableStateOf<String?>(null) }
    var pickingForNewTag by rememberSaveable { mutableStateOf(false) }
    val view = LocalView.current

    val newIcon = MoodTagIcons.forKey(newTagIcon) ?: MoodTagIcons.resolve(MoodTag(newTag.trim()))
    val newCategory = MoodTagCategory.of(newTagCategory) ?: MoodTagCategories.guess(newIcon.key)

    fun commitNewTag() {
        val trimmed = newTag.trim()
        if (trimmed.isNotBlank()) {
            view.performHapticSuccess()
            viewModel.addTag(trimmed, newTagIcon, newCategory.key)
            newTag = ""
            newTagIcon = null
            newTagCategory = null
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
                icon = Icons.Rounded.Sell,
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
                        "Change them, their icons and their categories on your phone."
                } else {
                    "Tags offered when you log a mood, in three categories. Tap a tag's icon to change it, " +
                        "or its category button to move it. Changes sync to your vault. " +
                        "Existing mood entries keep the tags they were saved with."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!readOnly) {
                // Add-a-tag row: its icon, its name, the add button; its category below.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalIconButton(onClick = {
                            view.performHapticClick()
                            pickingForNewTag = true
                        }, shapes = IconButtonDefaults.shapes()) {
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
                        FilledIconButton(onClick = { commitNewTag() }, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Default.Add, contentDescription = "Add tag")
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MoodTagCategories.ORDER.forEach { category ->
                            FilterChip(
                                selected = category == newCategory,
                                onClick = {
                                    view.performHapticTick()
                                    newTagCategory = category.key
                                },
                                label = { Text(MoodTagCategories.title(category)) },
                                leadingIcon = {
                                    Icon(MoodTagCategories.icon(category), contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            )
                        }
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
                    MoodTagCategories.grouped(tags).forEach { (category, inCategory) ->
                        item(key = "category:${category.key}") {
                            CategoryHeader(category, inCategory.size)
                        }
                        if (inCategory.isEmpty()) {
                            item(key = "empty:${category.key}") {
                                Text(
                                    "No tags here yet.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }
                        items(inCategory, key = { it.name }) { tag ->
                            TagRow(
                                tag = tag,
                                category = category,
                                readOnly = readOnly,
                                canMoveUp = MoodTagCategories.moveWithinCategory(tags, tag.name, up = true) != null,
                                canMoveDown = MoodTagCategories.moveWithinCategory(tags, tag.name, up = false) != null,
                                onPickIcon = {
                                    view.performHapticClick()
                                    pickingFor = tag.name
                                },
                                onCategory = { picked ->
                                    view.performHapticTick()
                                    viewModel.setCategory(tag.name, picked)
                                },
                                onMove = { up ->
                                    view.performHapticTick()
                                    viewModel.moveWithinCategory(tag.name, up)
                                },
                                onRemove = {
                                    view.performHapticClick()
                                    viewModel.removeTag(tag.name)
                                },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A category's title over its tags, in the category's own color, with how many it holds. */
@Composable
private fun CategoryHeader(category: MoodTagCategory, count: Int) {
    val color = MoodTagCategories.colors(category, MaterialTheme.colorScheme).strong
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp, start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(MoodTagCategories.icon(category), contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(
            "${MoodTagCategories.title(category)} · $count",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
private fun TagRow(
    tag: MoodTag,
    category: MoodTagCategory,
    readOnly: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onPickIcon: () -> Unit,
    onCategory: (MoodTagCategory) -> Unit,
    onMove: (up: Boolean) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val icon = MoodTagIcons.resolve(tag)
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            leadingContent = {
                if (readOnly) {
                    Icon(icon.image, contentDescription = null)
                } else {
                    FilledTonalIconButton(onClick = onPickIcon, shapes = IconButtonDefaults.shapes()) {
                        Icon(icon.image, contentDescription = "Change the icon of ${tag.name}")
                    }
                }
            },
            trailingContent = if (readOnly) null else {
                {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CategoryMenu(tag.name, category, onCategory)
                        IconButton(enabled = canMoveUp, shapes = IconButtonDefaults.shapes(), onClick = { onMove(true) }) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                        }
                        IconButton(enabled = canMoveDown, shapes = IconButtonDefaults.shapes(), onClick = { onMove(false) }) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                        }
                        IconButton(onClick = onRemove, shapes = IconButtonDefaults.shapes()) {
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

/** The tag's category as a button: a menu of the three, the current one ticked. */
@Composable
private fun CategoryMenu(tagName: String, current: MoodTagCategory, onPick: (MoodTagCategory) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, shapes = IconButtonDefaults.shapes()) {
            Icon(
                MoodTagCategories.icon(current),
                contentDescription = "Category of $tagName: ${MoodTagCategories.title(current)}",
                tint = MoodTagCategories.colors(current, MaterialTheme.colorScheme).strong
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MoodTagCategories.ORDER.forEach { category ->
                DropdownMenuItem(
                    text = { Text(MoodTagCategories.title(category)) },
                    leadingIcon = { Icon(MoodTagCategories.icon(category), contentDescription = null) },
                    trailingIcon = if (category == current) {
                        { Icon(Icons.Default.Check, contentDescription = "Current") }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        if (category != current) onPick(category)
                    }
                )
            }
        }
    }
}
