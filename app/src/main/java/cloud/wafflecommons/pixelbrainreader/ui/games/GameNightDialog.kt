package cloud.wafflecommons.pixelbrainreader.ui.games

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cloud.wafflecommons.pixelbrainreader.R

/**
 * Shown once a game night would start without Do Not Disturb access (D7), in the app's and the
 * mode's own names (13.4): the app as the system lists it in Do Not Disturb access (`app_name`), and
 * the Personal mode as the owner named it in Modes settings ([personalLabel]), never a fixed word.
 */
@Composable
fun GameNightDialog(personalLabel: String, onGrant: () -> Unit, onDismiss: () -> Unit) {
    val appName = stringResource(R.string.app_name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Game night needs Do Not Disturb access") },
        text = { Text(gameNightText(personalLabel, appName)) },
        confirmButton = { TextButton(onClick = onGrant, shapes = ButtonDefaults.shapes()) { Text("Open settings") } },
        dismissButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("Not now") } }
    )
}

/** The dialog's text with the mode's and the app's own names (pure, so a JVM test reads it). */
internal fun gameNightText(personalLabel: String, appName: String): String =
    "On a $personalLabel evening, a game session turns on the app's own \"Game night\" mode, with the exceptions " +
        "you choose in Settings › Modes. Allow $appName in Do Not Disturb access to use it. The session runs either way."
