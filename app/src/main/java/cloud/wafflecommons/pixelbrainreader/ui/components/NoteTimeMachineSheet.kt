package cloud.wafflecommons.pixelbrainreader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.wafflecommons.pixelbrainreader.data.remote.GitCommitDto
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class DiffLineType { ADDED, REMOVED, UNCHANGED }

data class DiffLine(
    val type: DiffLineType,
    val text: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteTimeMachineSheet(
    filePath: String,
    currentContent: String,
    commits: List<GitCommitDto>,
    isLoading: Boolean,
    onFetchRevisionContent: suspend (String) -> String?,
    onRestoreContent: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedCommit by remember { mutableStateOf<GitCommitDto?>(null) }
    var historicalContent by remember { mutableStateOf<String?>(null) }
    var isLoadingContent by remember { mutableStateOf(false) }

    LaunchedEffect(selectedCommit) {
        val commit = selectedCommit
        if (commit != null) {
            isLoadingContent = true
            historicalContent = onFetchRevisionContent(commit.commitId)
            isLoadingContent = false
        } else {
            historicalContent = null
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selectedCommit != null) {
                    IconButton(onClick = { selectedCommit = null }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Retour aux versions")
                    }
                } else {
                    Icon(
                        imageVector = Icons.Rounded.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (selectedCommit == null) "Time-Machine Git" else "Diff · ${selectedCommit?.shortId}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = filePath.substringAfterLast("/"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (selectedCommit == null) {
                // List of commits
                if (commits.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Aucun historique Git trouvé pour ce fichier.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(commits) { commit ->
                            CommitHistoryItem(
                                commit = commit,
                                onClick = { selectedCommit = commit }
                            )
                        }
                    }
                }
            } else {
                // Diff View
                if (isLoadingContent) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    val history = historicalContent ?: ""
                    val diffLines = remember(currentContent, history) {
                        computeSimpleDiff(history, currentContent)
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Message : ${selectedCommit?.message}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    onRestoreContent(history)
                                    onDismiss()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            ) {
                                Icon(Icons.Rounded.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Restaurer")
                            }
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLowest
                        ) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp)
                            ) {
                                items(diffLines) { line ->
                                    val (bgColor, textColor, prefix) = when (line.type) {
                                        DiffLineType.ADDED -> Triple(
                                            Color(0xFF1B5E20).copy(alpha = 0.2f),
                                            Color(0xFF4CAF50),
                                            "+ "
                                        )
                                        DiffLineType.REMOVED -> Triple(
                                            Color(0xFFB71C1C).copy(alpha = 0.2f),
                                            Color(0xFFE57373),
                                            "- "
                                        )
                                        DiffLineType.UNCHANGED -> Triple(
                                            Color.Transparent,
                                            MaterialTheme.colorScheme.onSurface,
                                            "  "
                                        )
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(bgColor, RoundedCornerShape(4.dp))
                                            .padding(vertical = 2.dp, horizontal = 4.dp)
                                    ) {
                                        Text(
                                            text = prefix + line.text,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            color = textColor
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommitHistoryItem(commit: GitCommitDto, onClick: () -> Unit) {
    val dateStr = remember(commit.timestamp) {
        val instant = Instant.ofEpochMilli(commit.timestamp)
        val zone = ZoneId.systemDefault()
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").format(instant.atZone(zone))
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SuggestionChip(
                    onClick = onClick,
                    label = { Text(commit.shortId, fontFamily = FontFamily.Monospace) },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    border = null
                )
                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = commit.message,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Par ${commit.author}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun computeSimpleDiff(oldText: String, newText: String): List<DiffLine> {
    val oldLines = oldText.lines()
    val newLines = newText.lines()
    val oldSet = oldLines.toSet()
    val newSet = newLines.toSet()

    val result = mutableListOf<DiffLine>()

    // Removed lines (in old but not in new)
    for (line in oldLines) {
        if (line !in newSet) {
            result.add(DiffLine(DiffLineType.REMOVED, line))
        }
    }

    // Added lines (in new but not in old)
    for (line in newLines) {
        if (line !in oldSet) {
            result.add(DiffLine(DiffLineType.ADDED, line))
        } else {
            result.add(DiffLine(DiffLineType.UNCHANGED, line))
        }
    }

    return result
}
