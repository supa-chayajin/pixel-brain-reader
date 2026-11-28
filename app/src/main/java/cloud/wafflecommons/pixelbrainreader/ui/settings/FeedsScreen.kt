package cloud.wafflecommons.pixelbrainreader.ui.settings

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeAppearance
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.utils.ImportUrlValidator
import cloud.wafflecommons.pixelbrainreader.data.watch.Feed
import cloud.wafflecommons.pixelbrainreader.data.watch.FeedConfig
import cloud.wafflecommons.pixelbrainreader.data.watch.FeedConfigRepository
import cloud.wafflecommons.pixelbrainreader.data.watch.FeedModes
import cloud.wafflecommons.pixelbrainreader.data.watch.FeedStatus
import cloud.wafflecommons.pixelbrainreader.data.watch.WatchRepository
import cloud.wafflecommons.pixelbrainreader.ui.components.EmptyHint
import cloud.wafflecommons.pixelbrainreader.ui.components.ReadOnlyNotice
import cloud.wafflecommons.pixelbrainreader.ui.components.ReorderHandle
import cloud.wafflecommons.pixelbrainreader.ui.components.ReorderMath
import cloud.wafflecommons.pixelbrainreader.ui.components.ReorderState
import cloud.wafflecommons.pixelbrainreader.ui.components.SectionCard
import cloud.wafflecommons.pixelbrainreader.ui.components.ShowsInButtons
import cloud.wafflecommons.pixelbrainreader.ui.components.rememberReorderState
import cloud.wafflecommons.pixelbrainreader.ui.components.reorderActions
import cloud.wafflecommons.pixelbrainreader.ui.components.reorderable
import cloud.wafflecommons.pixelbrainreader.ui.components.segmentShape
import cloud.wafflecommons.pixelbrainreader.ui.daily.ageOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The feeds (D5), kept in the vault's `feeds.json`: every change reads the file first, and a
 * read-only device changes nothing. Each feed picks the modes it shows in (13.4, F4); the order is
 * the owner's, dragged into place and written once, at the drop (F-d).
 */
@HiltViewModel
class FeedsViewModel @Inject constructor(
    private val repository: FeedConfigRepository,
    private val watchRepository: WatchRepository,
    modesRepository: ModesRepository
) : ViewModel() {

    /** The owner's names and icons for the modes: the Shows in buttons wear them, never "Work" in the copy. */
    val appearance: StateFlow<ModeAppearance> = modesRepository.visibility
        .map { it.appearance }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), modesRepository.visibility.value.appearance)

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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The type only: a message can quote a feed's address.
                Log.w(TAG, "The feeds couldn't be checked: ${e.javaClass.simpleName}")
                _problem.value = "The feeds couldn't be read."
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
        // 13.4 (F4): a new feed shows in every mode, its modes written out.
        update { it.add(Feed(url = address, name = name.trim(), modes = FeedModes.ALL_KEYS)) }
        return true
    }

    fun remove(url: String) = update { it.remove(url) }

    /** 13.4 (F4): the modes [url]'s feed shows in; never none (the last one on stays on). */
    fun setModes(url: String, modes: Set<ContextMode>) {
        if (modes.isEmpty()) return
        update { it.withModes(url, modes) }
    }

    /** 13.4 (F-d): the feeds in [urls]' order, written once, at the drop. */
    fun reorder(urls: List<String>) = update { it.reordered(urls) }

    /**
     * [change] shown at once, then written over the file on disk (read first); what the disk then
     * holds is shown. A write that doesn't go through puts the list back as the file has it.
     */
    private fun update(change: (FeedConfig) -> FeedConfig) {
        _problem.value = null
        if (VaultWriteGate.refuses()) {
            _problem.value = READ_ONLY_PROBLEM
            return
        }
        _config.value?.let { _config.value = change(it) }
        viewModelScope.launch {
            val saved = repository.update(change)
            if (saved == null) {
                _problem.value = if (VaultWriteGate.readOnly) READ_ONLY_PROBLEM else "The feeds couldn't be saved."
                _config.value = repository.read()
            } else {
                _config.value = saved
            }
        }
    }

    private companion object {
        const val TAG = "FeedsPage"
        const val READ_ONLY_PROBLEM = "Change the feeds on your phone."
    }
}

/**
 * Settings › Connections › Feeds (route `watch_feeds`): the feeds behind the Feeds tab and Daily,
 * the modes each one shows in, their order (drag a feed's ≡, or a screen reader's Move up / Move
 * down), Check now and Add a feed. On a read-only device the page is readable, its controls off.
 */
@Composable
fun FeedsScreen(onNavigateBack: () -> Unit, viewModel: FeedsViewModel = hiltViewModel()) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val problem by viewModel.problem.collectAsStateWithLifecycle()
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()
    val checking by viewModel.checking.collectAsStateWithLifecycle()
    val readOnly = VaultWriteGate.readOnly
    var address by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }

    // The drag moves a working copy; the order is written once, at the drop.
    val saved = config?.feeds.orEmpty()
    var working by remember(saved) { mutableStateOf(saved) }
    val move: (Int, Int) -> Unit = { from, to -> working = ReorderMath.moved(working, from, to) }
    val drop: () -> Unit = { viewModel.reorder(working.map { it.url }) }

    // 13.4 (S-k): the Settings frame (SettingsPage.FEEDS: its title, emblem and pill), whose scroll a
    // feed dragged near the page's edge moves; the lead a row of its own (S9).
    SettingsPageScaffold(page = SettingsPage.FEEDS, onBack = onNavigateBack) {
        val reorder = rememberReorderState(onMove = move, onDrop = drop, scrollBy = rememberSettingsScrollBy())
        if (readOnly) ReadOnlyNotice()
        SettingsGroup {
            row {
                SettingsNote(
                    "Their headlines show on the Feeds tab and on Daily. The list lives in your vault, " +
                        "so every device reads the same one."
                )
            }
        }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

        if (config != null && working.isEmpty()) {
            EmptyHint(if (readOnly) "No feed yet." else "No feed yet: add one below.")
        }
        if (working.isNotEmpty()) {
            // One segmented group: each feed in its own segment (the group never clips, so a dragged
            // feed keeps its corners), then Check now.
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val segments = working.size + 1
                working.forEachIndexed { index, feed ->
                    key(feed.url) {
                        FeedRow(
                            feed = feed,
                            index = index,
                            feeds = working.size,
                            segments = segments,
                            status = statuses[feed.url],
                            appearance = appearance,
                            editable = !readOnly,
                            reorder = reorder,
                            onMove = move,
                            onDrop = drop,
                            onModes = { viewModel.setModes(feed.url, it) },
                            onRemove = { viewModel.remove(feed.url) }
                        )
                    }
                }
                CheckNowRow(
                    checking = checking,
                    onCheck = viewModel::checkNow,
                    modifier = Modifier.fillMaxWidth(),
                    index = segments - 1,
                    segments = segments
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
                Text(
                    text = "A new feed shows in every mode; choose its modes once it's in the list.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        if (viewModel.add(address, name)) {
                            address = ""
                            name = ""
                        }
                    },
                    shapes = ButtonDefaults.shapes(),
                    enabled = address.isNotBlank()
                ) { Text("Add") }
            }
        }
    }
}

/**
 * A feed: its handle (a writing device), its name, address and last read, Remove; under them the
 * modes it shows in. The row moves whole while dragged; a screen reader moves it a place at a time.
 */
@Composable
private fun FeedRow(
    feed: Feed,
    index: Int,
    feeds: Int,
    segments: Int,
    status: FeedStatus?,
    appearance: ModeAppearance,
    editable: Boolean,
    reorder: ReorderState,
    onMove: (Int, Int) -> Unit,
    onDrop: () -> Unit,
    onModes: (Set<ContextMode>) -> Unit,
    onRemove: () -> Unit
) {
    var confirming by remember { mutableStateOf(false) }
    val lifted = reorder.draggedKey == feed.url
    Surface(
        shape = segmentShape(index, segments),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = if (lifted) 6.dp else 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .reorderable(reorder, feed.url, index)
            .reorderActions(index, feeds, onMove, onDrop, enabled = editable)
    ) {
        Column(
            modifier = Modifier.padding(start = if (editable) 4.dp else 20.dp, end = 12.dp, top = 10.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (editable) ReorderHandle(reorder, feed.url, index)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(feed.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        feed.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    FeedStatusLine(status)
                }
                if (editable) {
                    TextButton(onClick = { if (confirming) onRemove() else confirming = true }, shapes = ButtonDefaults.shapes()) {
                        Text(if (confirming) "Sure?" else "Remove")
                    }
                }
            }
            Column(
                modifier = Modifier.padding(start = if (editable) 48.dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // The group says "Shows in" to a screen reader itself.
                Text(
                    "Shows in",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clearAndSetSemantics {}
                )
                ShowsInButtons(
                    shown = feed.shownIn,
                    appearance = appearance,
                    onChange = onModes,
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** Check now, the group's last segment: every feed read again, each one's line updated. */
@Composable
private fun CheckNowRow(checking: Boolean, onCheck: () -> Unit, index: Int, segments: Int, modifier: Modifier = Modifier) {
    Surface(
        shape = segmentShape(index, segments),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Read every feed again",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCheck, shapes = ButtonDefaults.shapes(), enabled = !checking) {
                Text(if (checking) "Checking…" else "Check now")
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
