package cloud.wafflecommons.pixelbrainreader.ui.daily

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.data.capture.CaptureGuess
import cloud.wafflecommons.pixelbrainreader.data.capture.CaptureKind
import cloud.wafflecommons.pixelbrainreader.data.capture.CaptureRouter
import java.time.format.DateTimeFormatter

/**
 * The single Daily input: type one line (or tap the mic), see where it will go, tap the chip to
 * send it somewhere else, then send. The text lives in local state (never a combined StateFlow).
 */
@Composable
fun QuickAddBar(
    onCapture: (CaptureGuess) -> Unit,
    onVoice: () -> Unit,
    modifier: Modifier = Modifier
) {
    var text by rememberSaveable { mutableStateOf("") }
    // The user's correction of the guess, cleared whenever the text is cleared.
    var override by rememberSaveable { mutableStateOf<CaptureKind?>(null) }
    val guess = remember(text) { CaptureRouter.guess(text) }
    val target = override?.let { CaptureRouter.retarget(guess, it) } ?: guess

    fun send() {
        if (target.text.isBlank()) return
        onCapture(target)
        text = ""
        override = null
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { new ->
                // A correction holds while the line keeps meaning the same thing; once the
                // router reads it differently (or it is emptied), the chip follows the line again.
                if (new.isBlank() || CaptureRouter.guess(new).kind != guess.kind) override = null
                text = new
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Add a task, moment, idea, thanks…") },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            trailingIcon = {
                if (text.isBlank()) {
                    IconButton(onClick = onVoice) {
                        Icon(Icons.Rounded.Mic, contentDescription = "Voice note")
                    }
                } else {
                    IconButton(onClick = ::send) {
                        Icon(
                            Icons.AutoMirrored.Rounded.Send,
                            contentDescription = "Add",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        )
        AnimatedVisibility(visible = text.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = { override = target.kind.next() },
                    label = {
                        val time = target.time?.format(DateTimeFormatter.ofPattern("HH:mm"))
                        Text(if (time != null) "${target.kind.label} · $time" else target.kind.label)
                    },
                    leadingIcon = {
                        Icon(iconFor(target.kind), contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                )
                Text(
                    "  Tap to change",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun iconFor(kind: CaptureKind): ImageVector = when (kind) {
    CaptureKind.TASK -> Icons.Rounded.Checklist
    CaptureKind.MOMENT -> Icons.Rounded.Event
    CaptureKind.GRATITUDE -> Icons.Rounded.Favorite
    CaptureKind.IDEA -> Icons.Rounded.Lightbulb
    CaptureKind.SCRAP -> Icons.Rounded.TextFields
}
