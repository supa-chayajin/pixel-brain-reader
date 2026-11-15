package cloud.wafflecommons.pixelbrainreader.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.data.vault.NoteLocation

/**
 * The note's name, and under it the folder it lives in (13.0, D5). A deep path gives up its middle
 * first, so the top folder and the note's own folder stay. A tap on either line opens that folder
 * in the file list, where its breadcrumb shows the whole path.
 */
@Composable
internal fun NoteTitle(
    name: String,
    path: String?,
    onOpenFolder: ((String) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val folder = path?.let(NoteLocation::folderOf)
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .then(
                if (folder != null && onOpenFolder != null) {
                    Modifier.clickable(onClickLabel = "Open its folder", role = Role.Button) { onOpenFolder(folder) }
                } else Modifier
            )
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (folder != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = NoteLocation.label(folder),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.semantics { contentDescription = NoteLocation.spoken(folder) }
                )
            }
        }
    }
}
