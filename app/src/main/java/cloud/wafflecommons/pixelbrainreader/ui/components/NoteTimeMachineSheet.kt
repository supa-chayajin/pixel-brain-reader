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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.wafflecommons.pixelbrainreader.data.remote.GitCommitDto
import cloud.wafflecommons.pixelbrainreader.data.utils.LineDiff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What the sheet knows about the version picked in the history list. */
private sealed interface RevisionState {
    data object Loading : RevisionState

    /** The note did not exist in that commit (e.g. the commit deleted it), or git could not read it. */
    data object Unreadable : RevisionState

    data class Loaded(val content: String, val rows: List<LineDiff.Row>) : RevisionState
}

private val COMMIT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

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
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    var selectedCommit by remember { mutableStateOf<GitCommitDto?>(null) }
    var revision by remember { mutableStateOf<RevisionState>(RevisionState.Loading) }
    var confirmRestore by remember { mutableStateOf(false) }

    LaunchedEffect(selectedCommit) {
        val commit = selectedCommit ?: return@LaunchedEffect
        revision = RevisionState.Loading
        val content = onFetchRevisionContent(commit.commitId)
        revision = if (content == null) {
            // Never treat "unreadable" as an empty note: restoring it would erase the note.
            RevisionState.Unreadable
        } else {
            val rows = withContext(Dispatchers.Default) {
                LineDiff.fold(LineDiff.compute(old = content, new = currentContent))
            }
            RevisionState.Loaded(content, rows)
        }
    }

    val loaded = revision as? RevisionState.Loaded
    if (confirmRestore && loaded != null) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Restore this version?") },
            text = {
                Text(
                    "The current text of the note is replaced by the version from " +
                        "${selectedCommit?.let { COMMIT_DATE.format(Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault())) }}. " +
                        "You can undo it right after."
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmRestore = false
                    onRestoreContent(loaded.content)
                    onDismiss()
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = false }) { Text("Cancel") }
            }
        )
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
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to versions")
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
                        text = if (selectedCommit == null) "Version history" else "Changes since ${selectedCommit?.shortId}",
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
                if (commits.isEmpty()) {
                    CenteredMessage("No git history for this note yet.")
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
                when (val state = revision) {
                    RevisionState.Loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    RevisionState.Unreadable -> CenteredMessage(
                        "This version can't be restored: the note doesn't exist in that commit."
                    )
                    is RevisionState.Loaded -> Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = selectedCommit?.message.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = { confirmRestore = true },
                                enabled = state.rows.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            ) {
                                Icon(Icons.Rounded.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Restore")
                            }
                        }

                        if (state.rows.isEmpty()) {
                            CenteredMessage("This version is identical to the current note.")
                        } else {
                            DiffList(rows = state.rows, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffList(rows: List<LineDiff.Row>, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            items(rows) { row ->
                when (row) {
                    is LineDiff.Row.Gap -> Text(
                        text = "⋯ ${row.count} unchanged line${if (row.count == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp)
                    )
                    is LineDiff.Row.Line -> {
                        val (bgColor, textColor, prefix) = when (row.kind) {
                            LineDiff.Kind.ADDED -> Triple(
                                Color(0xFF1B5E20).copy(alpha = 0.2f),
                                Color(0xFF4CAF50),
                                "+ "
                            )
                            LineDiff.Kind.REMOVED -> Triple(
                                Color(0xFFB71C1C).copy(alpha = 0.2f),
                                Color(0xFFE57373),
                                "- "
                            )
                            LineDiff.Kind.UNCHANGED -> Triple(
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
                                text = prefix + row.text,
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

@Composable
private fun CenteredMessage(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun CommitHistoryItem(commit: GitCommitDto, onClick: () -> Unit) {
    val dateStr = remember(commit.timestamp) {
        COMMIT_DATE.format(Instant.ofEpochMilli(commit.timestamp).atZone(ZoneId.systemDefault()))
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
                text = "by ${commit.author}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
