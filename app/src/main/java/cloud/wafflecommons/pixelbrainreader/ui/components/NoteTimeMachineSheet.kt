@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package cloud.wafflecommons.pixelbrainreader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.remote.GitCommitDto
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncChange
import cloud.wafflecommons.pixelbrainreader.data.utils.LineDiff
import cloud.wafflecommons.pixelbrainreader.data.utils.NoteDiff
import cloud.wafflecommons.pixelbrainreader.data.utils.WordDiff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What the sheet shows beyond the list: one version, or what the last sync brought (K3, 12.20). */
private sealed interface Pick {
    data class Version(val commit: GitCommitDto) : Pick
    data object LastSync : Pick
}

/** What the sheet knows about the version picked in the history list. */
private sealed interface RevisionState {
    data object Loading : RevisionState

    /** The note did not exist in that commit (e.g. the commit deleted it), or git could not read it. */
    data object Unreadable : RevisionState

    /**
     * The line diff, the words that changed inside its paired lines (K3), and the text Restore
     * puts back: null when there is none (a note the last sync added).
     */
    data class Loaded(
        val restorable: String?,
        val rows: List<LineDiff.Row>,
        val words: Map<Int, List<WordDiff.Piece>>
    ) : RevisionState
}

private val COMMIT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun dateOf(millis: Long): String = COMMIT_DATE.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/** The rows and their word highlights, off the main thread. */
private suspend fun loaded(old: String, new: String, restorable: String?): RevisionState.Loaded = withContext(Dispatchers.Default) {
    val diff = NoteDiff.of(old, new)
    RevisionState.Loaded(restorable, diff.rows, diff.words)
}

/**
 * A note's history (Time Machine). Since 12.20 (K3) it follows the note across renames, marks the
 * words that changed inside each changed line, and opens on what the last sync brought when the
 * last pull changed this note ([lastSync]; the owner's D4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteTimeMachineSheet(
    filePath: String,
    currentContent: String,
    commits: List<GitCommitDto>,
    isLoading: Boolean,
    /** A version's text: at [commitId], under the note's path then (null: today's path). */
    onFetchRevisionContent: suspend (commitId: String, path: String?) -> String?,
    onRestoreContent: (String) -> Unit,
    onDismiss: () -> Unit,
    lastSync: SyncChange? = null
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    var pick by remember { mutableStateOf<Pick?>(if (lastSync != null) Pick.LastSync else null) }
    var revision by remember { mutableStateOf<RevisionState>(RevisionState.Loading) }
    var confirmRestore by remember { mutableStateOf(false) }

    LaunchedEffect(pick) {
        val current = pick ?: return@LaunchedEffect
        revision = RevisionState.Loading
        revision = when (current) {
            is Pick.Version -> {
                val content = onFetchRevisionContent(current.commit.commitId, current.commit.path)
                // Never treat "unreadable" as an empty note: restoring it would erase the note.
                if (content == null) RevisionState.Unreadable else loaded(old = content, new = currentContent, restorable = content)
            }
            Pick.LastSync -> {
                val sync = lastSync ?: return@LaunchedEffect
                val before = if (sync.isNew) "" else onFetchRevisionContent(sync.beforeHead, null)
                val after = onFetchRevisionContent(sync.afterHead, null)
                if (before == null || after == null) RevisionState.Unreadable
                else loaded(old = before, new = after, restorable = before.takeUnless { sync.isNew })
            }
        }
    }

    val loaded = revision as? RevisionState.Loaded
    val restorable = loaded?.restorable
    if (confirmRestore && restorable != null) {
        val versionFrom = when (val current = pick) {
            is Pick.Version -> "the version from ${dateOf(current.commit.timestamp)}"
            Pick.LastSync -> "the version from before your last sync"
            null -> "that version"
        }
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Restore this version?") },
            text = { Text("The current text of the note is replaced by $versionFrom. You can undo it right after.") },
            confirmButton = {
                Button(onClick = {
                    confirmRestore = false
                    onRestoreContent(restorable)
                    onDismiss()
                }, shapes = ButtonDefaults.shapes()) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = false }, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
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
                if (pick != null) {
                    IconButton(onClick = { pick = null }, shapes = IconButtonDefaults.shapes()) {
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
                        text = when (val current = pick) {
                            null -> "Version history"
                            is Pick.Version -> "Changes since ${current.commit.shortId}"
                            Pick.LastSync -> "Since your last sync"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (pick == Pick.LastSync && lastSync != null) {
                            "What GitHub brought on ${dateOf(lastSync.atMillis)} · ${filePath.substringAfterLast("/")}"
                        } else {
                            filePath.substringAfterLast("/")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            val current = pick
            if (current == null && isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
            } else if (current == null) {
                if (commits.isEmpty() && lastSync == null) {
                    CenteredMessage("No git history for this note yet.")
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (lastSync != null) {
                            item(key = "last-sync") {
                                LastSyncItem(sync = lastSync, onClick = { pick = Pick.LastSync })
                            }
                        }
                        items(commits, key = { it.commitId }) { commit ->
                            CommitHistoryItem(
                                commit = commit,
                                onClick = { pick = Pick.Version(commit) }
                            )
                        }
                    }
                }
            } else {
                when (val state = revision) {
                    RevisionState.Loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingIndicator()
                    }
                    RevisionState.Unreadable -> CenteredMessage(
                        if (current == Pick.LastSync) "The note's text from that sync can't be read."
                        else "This version can't be restored: the note doesn't exist in that commit."
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
                                text = when (current) {
                                    is Pick.Version -> current.commit.message
                                    Pick.LastSync -> if (lastSync?.isNew == true) "New in your last sync." else "Changed by your last sync."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            // A read-only device reads the history and restores nothing (12.15, S2).
                            if (!VaultWriteGate.readOnly && state.restorable != null) Button(
                                onClick = { confirmRestore = true }, shapes = ButtonDefaults.shapes(),
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
                            CenteredMessage(
                                if (current == Pick.LastSync) "Your last sync didn't change this note's text."
                                else "This version is identical to the current note."
                            )
                        } else {
                            NoteDiffList(rows = state.rows, words = state.words, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

private val ADDED_TEXT = Color(0xFF4CAF50)
private val REMOVED_TEXT = Color(0xFFE57373)

/** A note's line diff with the changed words marked: the Time Machine's, and a time-travel answer's (K4). */
@Composable
internal fun NoteDiffList(rows: List<LineDiff.Row>, words: Map<Int, List<WordDiff.Piece>>, modifier: Modifier = Modifier) {
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
            itemsIndexed(rows) { index, row ->
                when (row) {
                    is LineDiff.Row.Gap -> Text(
                        text = "⋯ ${row.count} unchanged line${if (row.count == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp)
                    )
                    is LineDiff.Row.Line -> {
                        val (bgColor, textColor, prefix) = when (row.kind) {
                            LineDiff.Kind.ADDED -> Triple(Color(0xFF1B5E20).copy(alpha = 0.2f), ADDED_TEXT, "+ ")
                            LineDiff.Kind.REMOVED -> Triple(Color(0xFFB71C1C).copy(alpha = 0.2f), REMOVED_TEXT, "- ")
                            LineDiff.Kind.UNCHANGED -> Triple(Color.Transparent, MaterialTheme.colorScheme.onSurface, "  ")
                        }
                        // The words that changed inside a changed line stand out (K3).
                        val pieces = words[index]
                        val text = if (pieces == null) {
                            buildAnnotatedString { append(prefix + row.text) }
                        } else {
                            buildAnnotatedString {
                                append(prefix)
                                for (piece in pieces) {
                                    if (piece.changed) {
                                        withStyle(SpanStyle(background = textColor.copy(alpha = 0.32f), fontWeight = FontWeight.Bold)) {
                                            append(piece.text)
                                        }
                                    } else {
                                        append(piece.text)
                                    }
                                }
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(bgColor, RoundedCornerShape(4.dp))
                                .padding(vertical = 2.dp, horizontal = 4.dp)
                        ) {
                            Text(
                                text = text,
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

/** The first entry when the last pull changed this note (K3, D4). */
@Composable
private fun LastSyncItem(sync: SyncChange, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CloudDownload, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Since your last sync", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    text = (if (sync.isNew) "New from GitHub" else "Changed on GitHub") + " · " + dateOf(sync.atMillis),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun CommitHistoryItem(commit: GitCommitDto, onClick: () -> Unit) {
    val dateStr = remember(commit.timestamp) { dateOf(commit.timestamp) }

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
            // Before a rename, the note had another name (K3).
            commit.path?.let { then ->
                Text(
                    text = "Named ${then.substringAfterLast('/').removeSuffix(".md")} then",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}
