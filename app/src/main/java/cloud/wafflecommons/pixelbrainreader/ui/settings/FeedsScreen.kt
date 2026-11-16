package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import cloud.wafflecommons.pixelbrainreader.ui.components.switchThumb
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.utils.ImportUrlValidator
import cloud.wafflecommons.pixelbrainreader.data.watch.Feed
import cloud.wafflecommons.pixelbrainreader.data.watch.FeedConfig
import cloud.wafflecommons.pixelbrainreader.data.watch.FeedConfigRepository
import cloud.wafflecommons.pixelbrainreader.data.watch.FeedStatus
import cloud.wafflecommons.pixelbrainreader.data.watch.WatchRepository
import cloud.wafflecommons.pixelbrainreader.ui.daily.ageOf
import cloud.wafflecommons.pixelbrainreader.ui.components.EmptyHint
import cloud.wafflecommons.pixelbrainreader.ui.components.FormScaffold
import cloud.wafflecommons.pixelbrainreader.ui.components.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.material.icons.rounded.Newspaper

/** The tech watch's feeds (D5), kept in the vault's `feeds.json`: every change reads the file first. */
@HiltViewModel
class FeedsViewModel @Inject constructor(
    private val repository: FeedConfigRepository,
    private val watchRepository: WatchRepository,
    modesRepository: ModesRepository
) : ViewModel() {

    /** The owner's name for Work mode: never "Work" in the copy. */
    val workLabel: StateFlow<String> = modesRepository.visibility
        .map { it.workLabel }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), modesRepository.visibility.value.workLabel)

    private val _config = MutableStateFlow<FeedConfig?>(null)
    val config: StateFlow<FeedConfig?> = _config.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    private val _statuses = MutableStateFlow<Map<String, FeedStatus>>(emptyMap())

    /** Each feed's last read and last failure (12.18, D5a). */
    val statuses: StateFlow<Map<String, FeedStatus>> = _statuses.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    init {
        viewModelScope.launch { _config.value = repository.read() }
        viewModelScope.launch { _statuses.value = watchRepository.statuses() }
    }

    /** Reads every feed again and shows how each one did. */
    fun checkNow() {
        if (_checking.value) return
        _checking.value = true
        viewModelScope.launch {
            try {
                _statuses.value = watchRepository.checkNow()
            } finally {
                _checking.value = false
            }
        }
    }

    /** False when [url] isn't an http(s) address on the internet: the form keeps what was typed. */
    fun add(url: String, name: String): Boolean {
        val address = url.trim()
        if (!ImportUrlValidator.isSafe(address)) {
            _problem.value = "Enter the feed's http(s) address."
            return false
        }
        update { it.add(Feed(url = address, name = name.trim())) }
        return true
    }

    fun remove(url: String) = update { it.remove(url) }

    fun setWork(url: String, work: Boolean) = update { it.setWork(url, work) }

    private fun update(change: (FeedConfig) -> FeedConfig) {
        _problem.value = null
        viewModelScope.launch {
            val saved = repository.update(change)
            if (saved == null) {
                _problem.value = if (VaultWriteGate.readOnly) "Change the feeds on your phone." else "The feeds couldn't be saved."
            } else {
                _config.value = saved
            }
        }
    }
}

/** Settings → Integrations → Tech watch feeds: add, remove, and mark a feed Work (hidden outside Work mode). */
@Composable
fun FeedsScreen(onNavigateBack: () -> Unit, viewModel: FeedsViewModel = hiltViewModel()) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val problem by viewModel.problem.collectAsStateWithLifecycle()
    val workLabel by viewModel.workLabel.collectAsStateWithLifecycle()
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()
    val checking by viewModel.checking.collectAsStateWithLifecycle()
    val readOnly = VaultWriteGate.readOnly
    var address by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }

    FormScaffold(title = "Tech watch feeds", icon = Icons.Rounded.Newspaper, onBack = onNavigateBack) {
        Text(
            text = "The Daily block shows the latest headlines of these feeds. The list lives in your vault, " +
                "so every device reads the same one. A feed marked $workLabel hides outside $workLabel mode.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

        SectionCard(title = "Feeds", icon = Icons.Rounded.RssFeed) {
            val feeds = config?.feeds.orEmpty()
            if (config != null && feeds.isEmpty()) EmptyHint("No feed yet: add one below.")
            feeds.forEachIndexed { index, feed ->
                if (index > 0) HorizontalDivider()
                FeedRow(
                    feed = feed,
                    status = statuses[feed.url],
                    workLabel = workLabel,
                    editable = !readOnly,
                    onWork = { viewModel.setWork(feed.url, it) },
                    onRemove = { viewModel.remove(feed.url) }
                )
            }
            if (feeds.isNotEmpty()) {
                TextButton(onClick = viewModel::checkNow, shapes = ButtonDefaults.shapes(), enabled = !checking) {
                    Text(if (checking) "Checking…" else "Check now")
                }
            }
        }

        if (!readOnly) {
            SectionCard(title = "Add a feed", icon = Icons.Rounded.Add) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Address (RSS or Atom)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        if (viewModel.add(address, name)) {
                            address = ""
                            name = ""
                        }
                    }, shapes = ButtonDefaults.shapes(),
                    enabled = address.isNotBlank()
                ) { Text("Add") }
            }
        }
    }
}

@Composable
private fun FeedRow(
    feed: Feed,
    status: FeedStatus?,
    workLabel: String,
    editable: Boolean,
    onWork: (Boolean) -> Unit,
    onRemove: () -> Unit
) {
    var confirming by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(feed.label, style = MaterialTheme.typography.bodyLarge)
            Text(feed.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            FeedStatusLine(status)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Switch(checked = feed.work, thumbContent = switchThumb(feed.work), onCheckedChange = onWork, enabled = editable)
            Text(workLabel, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        if (editable) {
            TextButton(onClick = { if (confirming) onRemove() else confirming = true }, shapes = ButtonDefaults.shapes()) {
                Text(if (confirming) "Sure?" else "Remove")
            }
        }
    }
}

/** How the feed did last time (12.18, D5a): when it was read, or why its last try failed. */
@Composable
private fun FeedStatusLine(status: FeedStatus?) {
    val now = System.currentTimeMillis()
    val (text, failing) = when {
        status == null -> "Not read yet" to false
        status.failing -> {
            val since = status.readAt?.let { " · last read ${ageOf(it, now).replaceFirstChar { c -> c.lowercase() }}" }.orEmpty()
            "Failed ${ageOf(status.failedAt ?: now, now).replaceFirstChar { it.lowercase() }}: ${status.problem ?: "unknown"}$since" to true
        }
        status.readAt != null -> "Read ${ageOf(status.readAt, now).replaceFirstChar { it.lowercase() }}" to false
        else -> "Not read yet" to false
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (failing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    )
}
