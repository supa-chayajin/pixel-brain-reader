package cloud.wafflecommons.pixelbrainreader.ui.mood

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Picks the icon of a mood tag: "Default" (the tag's own default icon, [defaultKey]) or one of the
 * [MoodTagIcons] groups. [selectedKey] is the icon picked so far, null when the tag uses its default.
 * Picking closes nothing by itself: the caller saves and dismisses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagIconPickerSheet(
    title: String,
    selectedKey: String?,
    defaultKey: String,
    onPick: (String) -> Unit,
    onUseDefault: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
        )
    ) {
        TagIconPickerContent(title, selectedKey, defaultKey, onPick, onUseDefault)
    }
}

/** The sheet's body, apart so a device test can draw it without a window of its own. */
@Composable
fun TagIconPickerContent(
    title: String,
    selectedKey: String?,
    defaultKey: String,
    onPick: (String) -> Unit,
    onUseDefault: () -> Unit,
    modifier: Modifier = Modifier
) {
    val defaultIcon = MoodTagIcons.forKey(defaultKey) ?: MoodTagIcons.GENERIC
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        FilterChip(
            selected = selectedKey == null,
            onClick = onUseDefault,
            label = { Text("Default") },
            leadingIcon = { Icon(defaultIcon.image, contentDescription = null, modifier = Modifier.size(18.dp)) },
            modifier = Modifier.testTag(TagIconPickerTags.DEFAULT_CHIP)
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 76.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxWidth().testTag(TagIconPickerTags.GRID)
        ) {
            MoodTagIcons.GROUPS.forEach { group ->
                item(key = "group:${group.title}", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        group.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                    )
                }
                items(group.icons, key = { it.key }) { icon ->
                    IconCell(icon, selected = icon.key == selectedKey, onClick = { onPick(icon.key) })
                }
            }
        }
    }
}

@Composable
private fun IconCell(icon: MoodTagIcon, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .padding(4.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) colors.primaryContainer else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp)
            .testTag(TagIconPickerTags.ICON_PREFIX + icon.key),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            icon.image,
            contentDescription = null,
            tint = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
            modifier = Modifier.size(28.dp)
        )
        Text(
            icon.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Test tags of the picker (device tests). */
object TagIconPickerTags {
    const val DEFAULT_CHIP = "tag-icon-default"
    const val GRID = "tag-icon-grid"
    const val ICON_PREFIX = "tag-icon:"
}
