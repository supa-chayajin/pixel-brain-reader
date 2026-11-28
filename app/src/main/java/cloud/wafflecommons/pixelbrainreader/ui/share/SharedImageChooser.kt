package cloud.wafflecommons.pixelbrainreader.ui.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.games.GameArt
import cloud.wafflecommons.pixelbrainreader.data.games.GamePage
import cloud.wafflecommons.pixelbrainreader.ui.games.GameChooserDialog

/**
 * What an image shared to the app becomes (13.4, P5), the owner's choice: a photo note in the inbox
 * (X2, as before), or a game's own picture, kept next to its page and shown wherever the game shows.
 * "Game picture" then asks which game, each with its picture ([arts], by page path); leaving that
 * list comes back to the choice. [onDismiss]: neither. Never on a read-only device: a share ends
 * there before it gets here, and this asks nothing if it did.
 */
@Composable
fun SharedImageChooser(
    pages: List<GamePage>,
    arts: Map<String, GameArt>,
    onPhotoNote: () -> Unit,
    onGamePicture: (GamePage) -> Unit,
    onDismiss: () -> Unit
) {
    if (VaultWriteGate.readOnly) return
    var choosingGame by rememberSaveable { mutableStateOf(false) }
    if (choosingGame) {
        GameChooserDialog(
            pages = pages,
            arts = arts,
            onPick = { page, _ -> onGamePicture(page) },
            onDismiss = { choosingGame = false },
            title = "Picture for"
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Image, contentDescription = null) },
        title = { Text("Keep this image as") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(
                    icon = Icons.AutoMirrored.Rounded.Notes,
                    label = "Photo note",
                    detail = "A note in your inbox, with the text the phone reads on the image.",
                    onClick = onPhotoNote
                )
                Choice(
                    icon = Icons.Rounded.SportsEsports,
                    label = "Game picture",
                    detail = "The picture a game shows in the app, kept next to its page.",
                    onClick = { choosingGame = true }
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") } }
    )
}

/** One of the two answers: a tonal button, and what it keeps under it. */
@Composable
private fun Choice(icon: ImageVector, label: String, detail: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(label)
    }
    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
