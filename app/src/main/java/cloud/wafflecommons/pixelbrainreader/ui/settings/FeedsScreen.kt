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
import cloud.wafflecommons.pixelbrainreader.data.utils.ImportUrlValidator
import cloud.wafflecommons.pixelbrainreader.data.watch.Feed
import cloud.wafflecommons.pixelbrainreader.data.watch.FeedConfig
import cloud.wafflecommons.pixelbrainreader.data.watch.FeedConfigRepository
import cloud.wafflecommons.pixelbrainreader.ui.components.EmptyHint
import cloud.wafflecommons.pixelbrainreader.ui.components.FormScaffold
import cloud.wafflecommons.pixelbrainreader.ui.components.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The tech watch's feeds (D5), kept in the vault's `feeds.json`: every change reads the file first. */
@HiltViewModel
class FeedsViewModel @Inject constructor(
    private val repository: FeedConfigRepository
) : ViewModel() {

    private val _config = MutableStateFlow<FeedConfig?>(null)
    val config: StateFlow<FeedConfig?> = _config.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    init {
        viewModelScope.launch { _config.value = repository.read() }
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
    val readOnly = VaultWriteGate.readOnly
    var address by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }

    FormScaffold(title = "Tech watch feeds", onBack = onNavigateBack) {
        Text(
            text = "The Daily block shows the latest headlines of these feeds. The list lives in your vault, " +
                "so every device reads the same one. A Work feed hides outside Work mode.",
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
                    editable = !readOnly,
                    onWork = { viewModel.setWork(feed.url, it) },
                    onRemove = { viewModel.remove(feed.url) }
                )
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
                    },
                    enabled = address.isNotBlank()
                ) { Text("Add") }
            }
        }
    }
}

@Composable
private fun FeedRow(feed: Feed, editable: Boolean, onWork: (Boolean) -> Unit, onRemove: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(feed.label, style = MaterialTheme.typography.bodyLarge)
            Text(feed.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Switch(checked = feed.work, onCheckedChange = onWork, enabled = editable)
            Text("Work", style = MaterialTheme.typography.labelSmall)
        }
        if (editable) {
            TextButton(onClick = { if (confirming) onRemove() else confirming = true }) {
                Text(if (confirming) "Sure?" else "Remove")
            }
        }
    }
}
