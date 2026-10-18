package cloud.wafflecommons.pixelbrainreader.ui.guard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.PlaylistAddCheck
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.guard.FindingKind
import cloud.wafflecommons.pixelbrainreader.data.guard.GuardFinding
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexIconButton
import cloud.wafflecommons.pixelbrainreader.ui.components.EmptyHint
import cloud.wafflecommons.pixelbrainreader.ui.components.FormScaffold
import cloud.wafflecommons.pixelbrainreader.ui.components.RemovableRow
import cloud.wafflecommons.pixelbrainreader.ui.components.SectionCard
import cloud.wafflecommons.pixelbrainreader.ui.components.TextPromptDialog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The confidentiality guard: what is held back from the git remote and why, the protected names,
 * secret detection and the exceptions the user allowed.
 */
@Composable
fun ConfidentialityScreen(
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
    viewModel: ConfidentialityViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val held by viewModel.held.collectAsStateWithLifecycle()
    val checking by viewModel.checking.collectAsStateWithLifecycle()
    val lastCheck by viewModel.lastCheckMillis.collectAsStateWithLifecycle()
    val settingsWereReset by viewModel.settingsWereReset.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbarHostState.showSnackbar(it) } }

    var addingTerm by remember { mutableStateOf(false) }
    var movingPath by remember { mutableStateOf<String?>(null) }

    FormScaffold(title = "Confidentiality", onBack = onBack, snackbarHostState = snackbarHostState) {
        if (settingsWereReset) {
            SectionCard(
                title = "Settings were reset",
                icon = Icons.Rounded.Warning,
                tint = MaterialTheme.colorScheme.error,
                subtitle = "The guard's settings could not be read on this phone and started again empty. Add your protected names again."
            )
        }

        StatusCard(held = held, checking = checking, lastCheck = lastCheck, onCheck = viewModel::check)

        held.groupBy { it.path }.forEach { (path, findings) ->
            HeldNoteCard(
                path = path,
                findings = findings,
                onOpen = { onOpenNote(path) },
                onMoveToPrivate = { movingPath = path },
                onAllow = viewModel::allow
            )
        }

        SectionCard(
            title = "Protected names",
            icon = Icons.Rounded.Badge,
            subtitle = "Clients, employers, people: names that must never reach your git remote. Matched as whole words, " +
                "ignoring case and accents, in note text and file names. Stored only on this phone, encrypted.",
            trailing = {
                CortexIconButton(onClick = { addingTerm = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add a protected name")
                }
            }
        ) {
            if (settings.terms.isEmpty()) EmptyHint("No protected names yet.")
            settings.terms.forEach { term ->
                RemovableRow(title = term, subtitle = null, onRemove = { viewModel.removeTerm(term) })
            }
        }

        SectionCard(
            title = "Secret detection",
            icon = Icons.Rounded.Key,
            subtitle = "Access tokens and API keys (GitHub, GitLab, AWS, Google, Slack, Stripe, AI providers…), private keys, " +
                "JSON Web Tokens, passwords in URLs, and password or token values that look generated.",
            trailing = { Switch(checked = settings.detectSecrets, onCheckedChange = viewModel::setDetectSecrets) }
        )

        SectionCard(
            title = "Allowed",
            icon = Icons.AutoMirrored.Rounded.PlaylistAddCheck,
            subtitle = when (val count = settings.allowed.size) {
                0 -> "Nothing allowed yet. Allowing a finding lets it through in its note or its folder."
                1 -> "1 finding allowed."
                else -> "$count findings allowed."
            }
        ) {
            if (settings.allowed.isNotEmpty()) {
                TextButton(onClick = viewModel::clearAllowed) { Text("Clear all") }
            }
            Text(
                "The guard stops what a change adds. Earlier versions of a note stay in the git history.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (addingTerm) {
        TextPromptDialog(
            title = "Protected name",
            label = "Name, client or project",
            confirmLabel = "Add",
            onConfirm = { term ->
                if (viewModel.addTerm(term)) addingTerm = false
            },
            onDismiss = { addingTerm = false }
        )
    }

    movingPath?.let { path ->
        MoveToPrivateDialog(
            path = path,
            onConfirm = { title, password ->
                viewModel.moveToPrivate(path, title, password)
                movingPath = null
            },
            onDismiss = { movingPath = null }
        )
    }
}

@Composable
private fun StatusCard(held: List<GuardFinding>, checking: Boolean, lastCheck: Long?, onCheck: () -> Unit) {
    val notes = held.map { it.path }.distinct().size
    SectionCard(
        title = if (held.isEmpty()) "Nothing held" else "Sync held",
        icon = if (held.isEmpty()) Icons.Rounded.VerifiedUser else Icons.Rounded.GppMaybe,
        tint = if (held.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        subtitle = if (held.isEmpty()) {
            "Pending changes are checked before every commit. " + (lastCheck?.let { "Last check at ${it.clockTime()}." } ?: "")
        } else {
            val items = if (held.size == 1) "1 item" else "${held.size} items"
            val where = if (notes == 1) "1 note" else "$notes notes"
            "$items in $where. Nothing reaches your git remote until each one is edited, moved to the private vault, or allowed."
        },
        trailing = {
            if (checking) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onCheck) { Text("Check again") }
            }
        }
    )
}

@Composable
private fun HeldNoteCard(
    path: String,
    findings: List<GuardFinding>,
    onOpen: () -> Unit,
    onMoveToPrivate: () -> Unit,
    onAllow: (GuardFinding, Boolean) -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                path,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (path.endsWith(".md")) {
                    OutlinedButton(onClick = onOpen) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open")
                    }
                    if (!path.startsWith("99_Private/")) {
                        OutlinedButton(onClick = onMoveToPrivate) {
                            Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Move to private")
                        }
                    }
                }
            }
            findings.forEachIndexed { index, finding ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.15f))
                FindingRow(finding = finding, onAllow = { wholeFolder -> onAllow(finding, wholeFolder) })
            }
        }
    }
}

@Composable
private fun FindingRow(finding: GuardFinding, onAllow: (Boolean) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val folder = ConfidentialityViewModel.folderOf(finding.path)
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Icon(
            if (finding.kind == FindingKind.SECRET) Icons.Rounded.Key else Icons.Rounded.Badge,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(top = 2.dp).size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                finding.label + if (finding.line == 0) " · file name" else " · line ${finding.line}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                finding.excerpt,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
        Box {
            TextButton(onClick = { menuOpen = true }) { Text("Allow") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("In this note") },
                    onClick = { menuOpen = false; onAllow(false) }
                )
                if (folder != null) {
                    DropdownMenuItem(
                        text = { Text("Everywhere in $folder/") },
                        onClick = { menuOpen = false; onAllow(true) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MoveToPrivateDialog(path: String, onConfirm: (String, CharArray) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(path.substringAfterLast('/').removeSuffix(".md")) }
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
        title = { Text("Move to the private vault") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "The note is encrypted with your private vault password, then its clear copy is deleted. " +
                        "Earlier versions stay in the git history.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Private vault password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && password.isNotEmpty(),
                onClick = { onConfirm(title, password.toCharArray()) }
            ) { Text("Move") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

private fun Long.clockTime(): String = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(CLOCK)
