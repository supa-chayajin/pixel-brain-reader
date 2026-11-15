package cloud.wafflecommons.pixelbrainreader.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.HistoryEdu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.model.MemoryEcho
import cloud.wafflecommons.pixelbrainreader.data.repository.SpacedReview

@Composable
fun MemoryEchoSection(
    echoes: List<MemoryEcho>,
    onOpenNote: (String) -> Unit,
    onMarkReviewed: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Spaced echoes (K6): the answer moves the note's next return. */
    onAnswer: (String, SpacedReview.Answer) -> Unit = { _, _ -> },
    /** Opens the folders picked for review; null on a read-only device. */
    onChooseFolders: (() -> Unit)? = null
) {
    if (echoes.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.HistoryEdu,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "From your vault",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (onChooseFolders != null) {
                TextButton(onClick = onChooseFolders) { Text("Review folders") }
            }
        }

        echoes.forEach { echo ->
            var visible by remember(echo.path) { mutableStateOf(true) }
            AnimatedVisibility(
                visible = visible,
                exit = fadeOut(tween(300)) + shrinkVertically(tween(300))
            ) {
                MemoryEchoCard(
                    echo = echo,
                    onOpen = { onOpenNote(echo.path) },
                    onReviewed = {
                        visible = false
                        onMarkReviewed(echo.path)
                    },
                    onAnswer = { answer ->
                        visible = false
                        onAnswer(echo.path, answer)
                    }
                )
            }
        }
    }
}

@Composable
fun MemoryEchoCard(
    echo: MemoryEcho,
    onOpen: () -> Unit,
    onReviewed: () -> Unit,
    modifier: Modifier = Modifier,
    onAnswer: (SpacedReview.Answer) -> Unit = {}
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SuggestionChip(
                        onClick = onOpen,
                        label = {
                            Text(
                                text = echo.categoryBadge,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        border = null
                    )
                    Text(
                        text = echo.urgencyLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                // A spaced echo is answered below; a forgotten one is marked reviewed (read-only: neither).
                if (!echo.spaced && !VaultWriteGate.readOnly) FilledTonalIconButton(
                    onClick = onReviewed,
                    modifier = Modifier.size(32.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Mark as reviewed",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = echo.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (echo.snippet.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = echo.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Open note",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            // Spaced echoes (K6): Again tomorrow, Good later, Easy later still.
            if (echo.spaced && !VaultWriteGate.readOnly) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { onAnswer(SpacedReview.Answer.AGAIN) }) { Text("Again") }
                    FilledTonalButton(onClick = { onAnswer(SpacedReview.Answer.GOOD) }) { Text("Good") }
                    FilledTonalButton(onClick = { onAnswer(SpacedReview.Answer.EASY) }) { Text("Easy") }
                }
            }
        }
    }
}

/** The folders whose notes come back on the review schedule (K6, the owner's D11). */
@Composable
fun EchoFoldersDialog(
    choices: List<String>,
    picked: Set<String>,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var selection by remember(picked) { mutableStateOf(picked) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review folders") },
        text = {
            Column {
                Text(
                    "Their notes come back on a widening schedule, with any note tagged #review.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(choices.size) { index ->
                        val folder = choices[index]
                        val checked = folder in selection
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selection = if (checked) selection - folder else selection + folder }
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { selection = if (it) selection + folder else selection - folder })
                            Text(
                                text = folder,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = if (folder.contains('/')) 16.dp else 0.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(selection) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
