package cloud.wafflecommons.pixelbrainreader.ui.work

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.ViewKanban
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import cloud.wafflecommons.pixelbrainreader.data.repository.VaultDiscoveryRepository
import cloud.wafflecommons.pixelbrainreader.data.work.BoardCard
import cloud.wafflecommons.pixelbrainreader.data.work.BoardRepository
import cloud.wafflecommons.pixelbrainreader.data.work.BoardsConfig
import cloud.wafflecommons.pixelbrainreader.data.work.StatusColumn
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexIconButton
import cloud.wafflecommons.pixelbrainreader.ui.components.EmptyHint
import cloud.wafflecommons.pixelbrainreader.ui.components.FolderPickerDialog
import cloud.wafflecommons.pixelbrainreader.ui.components.FormScaffold
import cloud.wafflecommons.pixelbrainreader.ui.components.PickDateDialog
import cloud.wafflecommons.pixelbrainreader.ui.components.SectionCard
import cloud.wafflecommons.pixelbrainreader.ui.theme.NavBarClearance
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BoardViewModel @Inject constructor(
    private val repository: BoardRepository,
    private val vaultDiscoveryRepository: VaultDiscoveryRepository,
    modesRepository: ModesRepository
) : ViewModel() {

    val config: StateFlow<BoardsConfig> = repository.config
    val visibility: StateFlow<Visibility> = modesRepository.visibility

    private val folder = MutableStateFlow<String?>(null)

    /** The open board's cards that the mode lets through. */
    val cards: StateFlow<List<BoardCard>?> = combine(folder, repository.changes, modesRepository.visibility) { f, _, v -> f to v }
        .mapLatest { (f, v) -> f?.let { repository.cards(it).filter { card -> v.isVisible(card.note.path) } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _folders = MutableStateFlow<List<String>>(emptyList())
    val folders: StateFlow<List<String>> = _folders.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    init {
        viewModelScope.launch { repository.reload() }
    }

    fun open(board: String) {
        folder.value = board
    }

    fun loadFolders() {
        viewModelScope.launch {
            _folders.value = vaultDiscoveryRepository.getAllFolderPaths()
                .filter { path -> !path.startsWith(".") && !path.startsWith("99_") && !path.startsWith("10_Journal") && path.count { it == '/' } <= 2 }
                .sorted()
        }
    }

    fun addBoard(board: String) = run("Couldn't add the board") { repository.addBoard(board) }
    fun removeBoard(board: String) = run("Couldn't remove the board") { repository.removeBoard(board) }
    fun move(card: BoardCard, column: StatusColumn) = run("Couldn't move the card") { repository.move(card, column) }
    fun setDetails(card: BoardCard, nextStep: String?, blockers: String?, target: LocalDate?) =
        run("Couldn't save the card") { repository.setDetails(card, nextStep, blockers, target) }

    fun export(board: String, open: (String) -> Unit) = run("Couldn't export the synthesis") { open(repository.exportSynthesis(board)) }

    private fun run(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("$failure: ${e.message}")
            }
        }
    }
}

/** The folders shown as boards, and a way to add one. */
@Composable
fun BoardsScreen(onBack: () -> Unit, onOpenBoard: (String) -> Unit, viewModel: BoardViewModel = hiltViewModel()) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val visibility by viewModel.visibility.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.loadFolders() }

    FormScaffold(
        title = "Boards",
        onBack = onBack,
        actions = { CortexIconButton(onClick = { adding = true }) { Icon(Icons.Rounded.Add, contentDescription = "Add a board") } }
    ) {
        Text(
            "Any folder can be a board: its notes are cards in the vault's status columns. Moving a card rewrites only its status and date_updated.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val shown = config.boards.filter { visibility.isVisible("$it/") }
        if (shown.isEmpty()) EmptyHint(if (config.boards.isEmpty()) "No board yet: add a folder." else "Your boards are hidden outside Work mode.")
        shown.forEach { board ->
            SectionCard(
                title = board.substringAfterLast('/'),
                icon = Icons.Rounded.ViewKanban,
                subtitle = board,
                modifier = Modifier.clickable { onOpenBoard(board) },
                trailing = { TextButton(onClick = { viewModel.removeBoard(board) }) { Text("Remove") } }
            )
        }
    }
    if (adding) {
        FolderPickerDialog(folders = folders, onPick = { viewModel.addBoard(it); adding = false }, onDismiss = { adding = false })
    }
}

/** One board: columns side by side when there is room, one column per tab otherwise. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(folder: String, onBack: () -> Unit, onOpenNote: (String) -> Unit, viewModel: BoardViewModel = hiltViewModel()) {
    LaunchedEffect(folder) { viewModel.open(folder) }
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbarHostState.showSnackbar(it) } }
    var showArchived by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<BoardCard?>(null) }
    val today = LocalDate.now()

    val columns: List<Pair<String, List<BoardCard>>> = buildList {
        val all = cards.orEmpty()
        StatusColumn.entries.filter { showArchived || it != StatusColumn.ARCHIVE }.forEach { column ->
            add(column.label to all.filter { it.note.column == column })
        }
        all.filter { it.note.column == null }.takeIf { it.isNotEmpty() }?.let { add("No status" to it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(folder.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { CortexIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    CortexIconButton(onClick = { showArchived = !showArchived }) {
                        Icon(Icons.Rounded.Inventory2, contentDescription = if (showArchived) "Hide archived" else "Show archived")
                    }
                    CortexIconButton(onClick = { viewModel.export(folder, onOpenNote) }) {
                        Icon(Icons.Rounded.IosShare, contentDescription = "Export a synthesis")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState, modifier = Modifier.padding(bottom = NavBarClearance)) }
    ) { padding ->
        BoxWithConstraints(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (maxWidth >= 600.dp) {
                Row(
                    modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    columns.forEach { (label, columnCards) ->
                        Card(
                            modifier = Modifier.width(280.dp).fillMaxHeight().padding(bottom = NavBarClearance),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                        ) {
                            Text("$label · ${columnCards.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(12.dp))
                            CardList(columnCards, today, onClick = { selected = it }, modifier = Modifier.weight(1f))
                        }
                    }
                }
            } else {
                var tab by remember { mutableIntStateOf(1) }
                val current = tab.coerceIn(0, (columns.size - 1).coerceAtLeast(0))
                Column {
                    PrimaryScrollableTabRow(selectedTabIndex = current, edgePadding = 12.dp) {
                        columns.forEachIndexed { index, (label, columnCards) ->
                            Tab(selected = current == index, onClick = { tab = index }, text = { Text("$label ${columnCards.size}") })
                        }
                    }
                    CardList(columns.getOrNull(current)?.second.orEmpty(), today, onClick = { selected = it }, modifier = Modifier.weight(1f))
                }
            }
        }
    }

    selected?.let { card ->
        CardSheet(
            card = card,
            onMove = { column -> viewModel.move(card, column); selected = null },
            onSave = { next, blockers, target -> viewModel.setDetails(card, next, blockers, target); selected = null },
            onOpen = { selected = null; onOpenNote(card.note.path) },
            onDismiss = { selected = null }
        )
    }
}

@Composable
private fun CardList(cards: List<BoardCard>, today: LocalDate, onClick: (BoardCard) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = NavBarClearance),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (cards.isEmpty()) item { EmptyHint("No card here.") }
        items(cards, key = { it.note.path }) { card -> BoardCardView(card, today, onClick = { onClick(card) }) }
    }
}

@Composable
private fun BoardCardView(card: BoardCard, today: LocalDate, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(card.note.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            card.nextStep?.let { Text("Next: $it", style = MaterialTheme.typography.bodySmall) }
            card.blockers?.let { Text("Blocked: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            val meta = listOfNotNull(
                card.targetDate?.let { "due ${it.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}" + if (card.overdue(today)) " (late)" else "" },
                card.note.updated?.let { "updated ${it.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}" }
            )
            if (meta.isNotEmpty()) {
                Text(
                    meta.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (card.overdue(today)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CardSheet(
    card: BoardCard,
    onMove: (StatusColumn) -> Unit,
    onSave: (String?, String?, LocalDate?) -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit
) {
    var nextStep by remember { mutableStateOf(card.nextStep.orEmpty()) }
    var blockers by remember { mutableStateOf(card.blockers.orEmpty()) }
    var target by remember { mutableStateOf(card.targetDate) }
    var pickTarget by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(card.note.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(card.note.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Move to", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusColumn.entries.forEach { column ->
                    FilterChip(selected = card.note.column == column, onClick = { if (card.note.column != column) onMove(column) }, label = { Text(column.label) })
                }
            }
            OutlinedTextField(nextStep, { nextStep = it }, label = { Text("Next step") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(blockers, { blockers = it }, label = { Text("Blockers") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { pickTarget = true },
                    label = { Text(target?.let { "Due ${it.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}" } ?: "Set a target date") }
                )
                if (target != null) TextButton(onClick = { target = null }) { Text("Clear") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpen) { Text("Open note") }
                Box(Modifier.weight(1f))
                Button(onClick = { onSave(nextStep, blockers, target) }) { Text("Save") }
            }
        }
    }
    if (pickTarget) {
        PickDateDialog(initial = target, onConfirm = { target = it; pickTarget = false }, onDismiss = { pickTarget = false })
    }
}
