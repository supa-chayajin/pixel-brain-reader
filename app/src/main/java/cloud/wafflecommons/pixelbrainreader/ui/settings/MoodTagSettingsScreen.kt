@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.mood.MoodEmojiSet
import cloud.wafflecommons.pixelbrainreader.data.mood.MoodEmojiSets
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodTag
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodTagCategory
import cloud.wafflecommons.pixelbrainreader.ui.components.ConnectedButtonRow
import cloud.wafflecommons.pixelbrainreader.ui.components.ExpressiveBadge
import cloud.wafflecommons.pixelbrainreader.ui.components.ReadOnlyNotice
import cloud.wafflecommons.pixelbrainreader.ui.components.ReorderHandle
import cloud.wafflecommons.pixelbrainreader.ui.components.ReorderMath
import cloud.wafflecommons.pixelbrainreader.ui.components.ReorderState
import cloud.wafflecommons.pixelbrainreader.ui.components.connectedShapes
import cloud.wafflecommons.pixelbrainreader.ui.components.rememberReorderState
import cloud.wafflecommons.pixelbrainreader.ui.components.reorderActions
import cloud.wafflecommons.pixelbrainreader.ui.components.reorderable
import cloud.wafflecommons.pixelbrainreader.ui.mood.MoodTagCategories
import cloud.wafflecommons.pixelbrainreader.ui.mood.MoodTagIcons
import cloud.wafflecommons.pixelbrainreader.ui.mood.TagIconPickerSheet
import cloud.wafflecommons.pixelbrainreader.ui.utils.HapticHelper.performHapticClick
import cloud.wafflecommons.pixelbrainreader.ui.utils.HapticHelper.performHapticSuccess
import cloud.wafflecommons.pixelbrainreader.ui.utils.HapticHelper.performHapticTick

/**
 * Settings › Moods (13.4, S10; route `mood_tags`, the 12.x Mood Tags page): the faces of this
 * device's moods, and the mood tags.
 *
 * - **Faces**: the set every mood on this device wears, with its five faces. This device's own:
 *   usable on a read-only device.
 * - **Tags**: the list the check-in sheet offers, in three categories (Activities, People, Places;
 *   the owner's M1). It lives in the vault ([cloud.wafflecommons.pixelbrainreader.data.repository.MoodTagRepository]):
 *   editing it only changes what the check-in *offers* from now on, past moods keep their own tags.
 *   A tag's ≡ drags it within its category (S5), a tap opens it (its icon, its category, Remove).
 *   A read-only device shows the tags without the controls.
 */
@Composable
fun MoodTagSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: MoodTagSettingsViewModel = hiltViewModel()
) {
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val moodEmojiSet by viewModel.moodEmojiSet.collectAsStateWithLifecycle()
    val readOnly = VaultWriteGate.readOnly
    var newTag by rememberSaveable { mutableStateOf("") }
    // The icon picked for the tag being typed; null = its default.
    var newTagIcon by rememberSaveable { mutableStateOf<String?>(null) }
    // The category picked for the tag being typed; null = the guess from its icon (M3).
    var newTagCategory by rememberSaveable { mutableStateOf<String?>(null) }
    // Whose icon the picker is choosing: an existing tag's name, or the tag being typed.
    var pickingFor by rememberSaveable { mutableStateOf<String?>(null) }
    var pickingForNewTag by rememberSaveable { mutableStateOf(false) }
    // The tag whose dialog is open.
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
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
    val editedTag = editing?.let { name -> tags.firstOrNull { it.name == name } }
    // A tag removed while its picker or its dialog is open (here or by a sync) closes them.
    LaunchedEffect(pickingFor, pickedTag) {
        if (pickingFor != null && pickedTag == null) pickingFor = null
    }
    LaunchedEffect(editing, editedTag) {
        if (editing != null && editedTag == null) editing = null
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
    if (editedTag != null && !readOnly) {
        TagDialog(
            tag = editedTag,
            onPickIcon = {
                view.performHapticClick()
                editing = null
                pickingFor = editedTag.name
            },
            onCategory = { picked ->
                view.performHapticTick()
                viewModel.setCategory(editedTag.name, picked)
            },
            onRemove = {
                view.performHapticClick()
                editing = null
                viewModel.removeTag(editedTag.name)
            },
            onDismiss = { editing = null }
        )
    }

    SettingsPageScaffold(page = SettingsPage.MOODS, onBack = onNavigateBack) {
        // Faces: this device's own setting.
        SettingsPageSection("Faces") {
            row {
                SettingsRow(
                    title = "Mood faces",
                    description = "The faces of your moods on this device: the check-in, the history, the stats and the " +
                        "widgets. Moods you already logged change face too.",
                    icon = Icons.Rounded.Mood,
                    anchor = SettingsAnchors.FACES,
                    below = {
                        MoodEmojiSetDropdown(
                            selected = moodEmojiSet,
                            onSelect = { set ->
                                view.performHapticTick()
                                viewModel.selectMoodEmojiSet(set.id)
                            }
                        )
                        FacesPreview(moodEmojiSet)
                    }
                )
            }
        }

        // The tags live in the vault: read only here on a read-only device.
        if (readOnly) ReadOnlyNotice()
        SettingsPageSection("Tags") {
            row {
                SettingsNote(
                    text = if (readOnly) {
                        "These are the tags your phone offers when you log a mood. Change them, their icons and their " +
                            "categories on your phone."
                    } else {
                        "Tags offered when you log a mood, in three categories. Tap a tag to change its icon or its " +
                            "category; long-press its ≡ to move it within its category. Changes sync to your vault. " +
                            "Existing mood entries keep the tags they were saved with."
                    },
                    modifier = Modifier.settingsAnchor(SettingsAnchors.TAGS)
                )
            }
            if (!readOnly) {
                row {
                    AddTagRow(
                        name = newTag,
                        onNameChange = { newTag = it },
                        icon = newIcon.image,
                        category = newCategory,
                        onPickIcon = {
                            view.performHapticClick()
                            pickingForNewTag = true
                        },
                        onCategory = { category ->
                            view.performHapticTick()
                            newTagCategory = category.key
                        },
                        onAdd = ::commitNewTag
                    )
                }
            }
        }

        if (tags.isEmpty()) {
            SettingsGroup { row { SettingsNote(if (readOnly) "No tags yet." else "No tags yet. Add one above.") } }
        } else {
            MoodTagCategories.grouped(tags).forEach { (category, inCategory) ->
                key(category.key) {
                    CategoryGroup(
                        category = category,
                        inCategory = inCategory,
                        readOnly = readOnly,
                        onOpen = { name ->
                            view.performHapticClick()
                            editing = name
                        },
                        onDrop = { name, target -> viewModel.moveWithinCategoryTo(name, target) },
                        onStep = { name, up ->
                            view.performHapticTick()
                            viewModel.moveWithinCategory(name, up)
                        }
                    )
                }
            }
        }
    }
}

/** The five faces of [set], score 1 (worst) to 5 (best). */
@Composable
private fun FacesPreview(set: MoodEmojiSet) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        set.faces.forEachIndexed { index, face ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(face, fontSize = 28.sp)
                Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * A category's tags as a segmented group, under its title: a drag within the group saves the tag
 * at its new place at the drop ([onDrop]); a screen reader's "Move up" / "Move down" steps it past
 * its neighbour ([onStep]).
 */
@Composable
private fun CategoryGroup(
    category: MoodTagCategory,
    inCategory: List<MoodTag>,
    readOnly: Boolean,
    onOpen: (String) -> Unit,
    onDrop: (String, Int) -> Unit,
    onStep: (String, Boolean) -> Unit
) {
    var working by remember(inCategory) { mutableStateOf(inCategory) }
    // The tag this drag moves: the one at the first move's start.
    var moving by remember { mutableStateOf<String?>(null) }
    val reorder = rememberReorderState(
        onMove = { from, to ->
            if (moving == null) moving = working.getOrNull(from)?.name
            working = ReorderMath.moved(working, from, to)
        },
        onDrop = {
            moving?.let { name -> working.indexOfFirst { it.name == name }.takeIf { it >= 0 }?.let { onDrop(name, it) } }
            moving = null
        },
        scrollBy = rememberSettingsScrollBy()
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        CategoryHeader(category, inCategory.size)
        Spacer(Modifier.height(8.dp))
        SettingsGroup {
            if (working.isEmpty()) row { SettingsNote("No tags here yet.") }
            working.forEachIndexed { index, tag ->
                row {
                    TagLine(
                        tag = tag,
                        index = index,
                        count = working.size,
                        reorder = reorder,
                        readOnly = readOnly,
                        onOpen = { onOpen(tag.name) },
                        onStep = { up -> onStep(tag.name, up) }
                    )
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
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp).semantics(mergeDescendants = true) { heading() },
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

/** A tag: its ≡ handle (a writing device only), its icon and its name; a tap opens it. */
@Composable
private fun TagLine(
    tag: MoodTag,
    index: Int,
    count: Int,
    reorder: ReorderState,
    readOnly: Boolean,
    onOpen: () -> Unit,
    onStep: (up: Boolean) -> Unit
) {
    val icon = MoodTagIcons.resolve(tag)
    val dragged = reorder.draggedKey == tag.name
    Surface(
        shape = LocalSegmentPosition.current.shape,
        color = if (dragged) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = if (dragged) 4.dp else 0.dp,
        // reorderable lifts the dragged tag above its neighbours (and while it settles back).
        modifier = Modifier
            .fillMaxWidth()
            .let { if (readOnly) it else it.reorderable(reorder, tag.name, index) }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !readOnly, onClickLabel = "Edit ${tag.name}", role = Role.Button, onClick = onOpen)
                .reorderActions(index, count, onMove = { from, to -> onStep(to < from) }, onDrop = {}, enabled = !readOnly)
                .padding(start = if (readOnly) 20.dp else 4.dp, end = 20.dp, top = 6.dp, bottom = 6.dp)
        ) {
            if (!readOnly) ReorderHandle(state = reorder, key = tag.name, index = index)
            ExpressiveBadge(icon = icon.image, key = tag.name, size = 36.dp)
            Spacer(Modifier.width(16.dp))
            Text(
                tag.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp)
            )
        }
    }
}

/** The add row: the new tag's icon, its name, the add button; its category below, preselected from the icon. */
@Composable
private fun AddTagRow(
    name: String,
    onNameChange: (String) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    category: MoodTagCategory,
    onPickIcon: () -> Unit,
    onCategory: (MoodTagCategory) -> Unit,
    onAdd: () -> Unit
) {
    Surface(
        shape = LocalSegmentPosition.current.shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(SettingsRowPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton(onClick = onPickIcon, shapes = IconButtonDefaults.shapes()) {
                    Icon(icon, contentDescription = "Choose the new tag's icon")
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("New tag") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onAdd() }),
                    modifier = Modifier.weight(1f)
                )
                FilledIconButton(onClick = onAdd, enabled = name.isNotBlank(), shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add tag")
                }
            }
            CategoryChoice(selected = category, onSelect = onCategory)
        }
    }
}

/** A tag's category: Activities, People or Places, as a connected group. */
@Composable
private fun CategoryChoice(selected: MoodTagCategory, onSelect: (MoodTagCategory) -> Unit) {
    val categories = MoodTagCategories.ORDER
    ConnectedButtonRow(modifier = Modifier.fillMaxWidth()) {
        categories.forEachIndexed { index, category ->
            ToggleButton(
                checked = category == selected,
                onCheckedChange = { if (category != selected) onSelect(category) },
                shapes = connectedShapes(index, categories.size),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(MoodTagCategories.title(category), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A tag opened: its category, its icon, Remove. Every change applies at once. */
@Composable
private fun TagDialog(
    tag: MoodTag,
    onPickIcon: () -> Unit,
    onCategory: (MoodTagCategory) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    val icon = MoodTagIcons.resolve(tag)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(icon.image, contentDescription = null) },
        title = { Text(tag.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Category", style = MaterialTheme.typography.labelLarge)
                CategoryChoice(selected = MoodTagCategories.of(tag), onSelect = onCategory)
                OutlinedButton(onClick = onPickIcon, shapes = ButtonDefaults.shapes()) {
                    Icon(icon.image, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Change the icon")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("Done") } },
        dismissButton = {
            TextButton(
                onClick = onRemove,
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("Remove") }
        }
    )
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
                            if (set.id == selected.id) Icon(Icons.Rounded.Check, contentDescription = "Current set")
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
