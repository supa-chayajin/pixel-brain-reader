package cloud.wafflecommons.pixelbrainreader.ui.main

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.ai.GeminiRagManager
import cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationRepository
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileEntity
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.remote.GitCommitDto
import cloud.wafflecommons.pixelbrainreader.data.remote.model.GithubFileDto
import cloud.wafflecommons.pixelbrainreader.data.repository.AppThemeConfig
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyNoteRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.TemplateRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.VaultDiscoveryRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.VaultExportRepository
import cloud.wafflecommons.pixelbrainreader.data.utils.VaultFileIndex
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncOrchestrator
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncState
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import cloud.wafflecommons.pixelbrainreader.data.utils.ContentSanitizer
import cloud.wafflecommons.pixelbrainreader.data.utils.SoundEffectManager
import cloud.wafflecommons.pixelbrainreader.data.utils.TaskCompletionDetector
import cloud.wafflecommons.pixelbrainreader.data.utils.TemplateEngine
import cloud.wafflecommons.pixelbrainreader.di.ApplicationScope
import cloud.wafflecommons.pixelbrainreader.ui.components.SaveState
import cloud.wafflecommons.pixelbrainreader.ui.utils.ConfettiType
import cloud.wafflecommons.pixelbrainreader.ui.utils.GlobalEffect
import cloud.wafflecommons.pixelbrainreader.ui.utils.UiEffectManager
import cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent
import cloud.wafflecommons.pixelbrainreader.widget.manager.WidgetSnapshotManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: FileRepository,
    private val dailyNoteRepository: DailyNoteRepository,
    private val templateRepository: TemplateRepository,
    private val secretManager: SecretManager,
    private val userPrefs: UserPreferencesRepository,
    private val geminiRagManager: GeminiRagManager,
    private val widgetSnapshotManager: WidgetSnapshotManager,
    uiEffectManager: UiEffectManager,
    private val gamificationRepository: GamificationRepository,
    private val syncOrchestrator: SyncOrchestrator,
    private val vaultExportRepository: VaultExportRepository,
    private val pushScheduler: VaultPushScheduler,
    soundEffectManager: SoundEffectManager,
    appClock: AppClock,
    @param:ApplicationScope private val appScope: CoroutineScope,
    vaultDiscoveryRepository: VaultDiscoveryRepository
) : ViewModel() {

    private val _currentPath = MutableStateFlow("")
    private val _searchQuery = MutableStateFlow("")
    private val _selectedFilePath = MutableStateFlow<String?>(null)
    private val _selectedFileName = MutableStateFlow<String?>(null)
    private val _isEditing = MutableStateFlow(false)
    private val _isLoading = MutableStateFlow(false)
    private val _isRefreshing = MutableStateFlow(false)
    private val _isSyncing = MutableStateFlow(false)
    private val _saveState = MutableStateFlow(SaveState.IDLE)
    private val _userMessage = MutableStateFlow<String?>(null)
    private val _error = MutableStateFlow<String?>(null)
    private val _importState = MutableStateFlow<ImportState?>(null)
    private val _isFocusMode = MutableStateFlow(false)
    private val _isExitPending = MutableStateFlow(false)
    private val _showDeleteConfirmation = MutableStateFlow(false)

    private val _isIndexing = MutableStateFlow(false)
    private val _analysisResult = MutableStateFlow<String?>(null)
    private val _availableMoveDestinations = MutableStateFlow<List<String>>(emptyList())
    private val _moveDialogCurrentPath = MutableStateFlow("")
    private val _availableTemplates = MutableStateFlow<List<String>>(emptyList())
    private val _showCreateFileDialog = MutableStateFlow(false)
    private val _navigationTrigger = MutableStateFlow<String?>(null)

    /** Bumped to re-read the open file from disk: re-opened, pulled, or changed by a sync cycle. */
    private val _contentRefresh = MutableStateFlow(0L)

    /**
     * The editor's working copy of the open note (null = not being edited). Compose snapshot state,
     * updated synchronously and kept OUT of [uiState]: as part of that combine, every keystroke
     * re-emitted the whole screen state and recomposed the entire main screen.
     */
    var unsavedContent by mutableStateOf<String?>(null)
        private set

    val hasUnsavedChanges: Boolean
        get() = unsavedContent != null

    /** Last content written to disk per note path — skips redundant saves. Main thread only. */
    private val savedContents = mutableMapOf<String, String>()

    /** The real current day (follows midnight), for "is this today's note?" checks. */
    val today: StateFlow<LocalDate> = appClock.today

    /** Where `![[image.png]]` embeds point, resolved in memory from the Room file index. */
    val attachmentIndex: StateFlow<VaultFileIndex> = vaultDiscoveryRepository.observeAttachmentIndex()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VaultFileIndex.EMPTY)

    // Expose Theme Preference
    val themeConfig: StateFlow<AppThemeConfig> = userPrefs.themeConfig
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppThemeConfig.FOLLOW_SYSTEM)

    // Global Effects (One-shot)
    val globalEffects = uiEffectManager.effects

    init {
        // XP can be granted anywhere (habits, widgets, health bonuses); the domain only reports the
        // level-up, the UI decides to celebrate it.
        gamificationRepository.levelUps
            .onEach { uiEffectManager.tryTriggerEffect(GlobalEffect.Confetti(ConfettiType.LEVEL_UP)) }
            .launchIn(viewModelScope)
    }

    // Global Sync State (observable by UI)
    val globalSyncState = syncOrchestrator.syncState

    // Live label of the repo-sync sub-step (pull/push/index…) for the Repo & File-detail
    // pull-to-refresh indicators. Null when no repo sync is running.
    val repoSyncStatus: StateFlow<String?> = repository.syncStatus

    // Persisted custom order of the regular nav-bar destinations (the "Daily" button is fixed).
    val navBarOrder: StateFlow<List<String>> = userPrefs.navBarOrder
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000L),
            UserPreferencesRepository.DEFAULT_NAVBAR_ORDER
        )

    // Event Channel
    private val _uiEvent = MutableSharedFlow<UiEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val uiEvent = _uiEvent.asSharedFlow()

    /**
     * One-shot signal to force-open the Detail pane. Needed for folder-insight: its pseudo-filename
     * ("Folder_Insight.md") doesn't change between runs, so the selectedFileName-diff navigation
     * effect wouldn't re-open the pane when re-analyzing while an insight is already showing.
     */
    private val _openDetailEvent = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val openDetailEvent = _openDetailEvent.asSharedFlow()

    // Reactive File List. The search query is debounced: each change runs a LIKE query over the
    // whole file index, which used to fire on every keystroke.
    private val _filesFlow = combine(
        _currentPath,
        _searchQuery.debounce { query -> if (query.isBlank()) 0L else SEARCH_DEBOUNCE_MS }
    ) { path, query -> path to query }
        .flatMapLatest { (path, query) ->
            if (query.isBlank()) repository.getFiles(path) else repository.searchFiles(query)
        }

    // Reactive File Content (a one-shot disk read, re-run on every content refresh).
    private val _selectedFileContent = combine(_selectedFilePath, _contentRefresh) { path, _ -> path }
        .flatMapLatest { path ->
            if (path == null) flowOf(null) else repository.getFileContentFlow(path).flowOn(Dispatchers.IO)
        }

    /** Precomputed, sorted view of the file list. */
    private data class FileView(val dtos: List<GithubFileDto>, val folders: List<String>)

    // Map + sort the file list OFF the main thread and only when the files themselves change.
    private val _fileView = _filesFlow
        .map { files ->
            val dtos = files
                .map { it.toDto() }
                .filter { !it.name.startsWith(".") }
                .sortedWith(compareBy({ it.type != "dir" }, { it.name }))
            val folders = files
                .filter { it.type == "dir" }
                .map { it.path }
                .distinct()
                .sorted()
            FileView(dtos, folders)
        }
        .flowOn(Dispatchers.Default)

    private data class BrowserSlice(
        val path: String,
        val query: String,
        val fileView: FileView,
        val paneWidth: Float,
        val navigationTrigger: String?
    )

    private data class DocumentSlice(
        val selectedPath: String?,
        val selectedName: String?,
        val content: String?,
        val isEditing: Boolean,
        val saveState: SaveState
    )

    private data class ProgressSlice(
        val isLoading: Boolean,
        val isRefreshing: Boolean,
        val isSyncing: Boolean,
        val isIndexing: Boolean,
        val isFocusMode: Boolean
    )

    private data class MessageSlice(
        val userMessage: String?,
        val error: String?,
        val importState: ImportState?,
        val isExitPending: Boolean,
        val showDeleteConfirmation: Boolean
    )

    private data class DialogSlice(
        val analysis: String?,
        val moveDestinations: List<String>,
        val moveDialogPath: String,
        val templates: List<String>,
        val showCreateFileDialog: Boolean
    )

    /** List-pane width while the split handle is dragged: applied instantly, persisted once the drag settles. */
    private val _draggedPaneWidth = MutableStateFlow<Float?>(null)

    init {
        // One DataStore write per settled drag instead of one per drag event (each write rewrote
        // the preferences file and re-emitted the whole screen state).
        _draggedPaneWidth
            .debounce(400L)
            .onEach { width -> width?.let { userPrefs.setListPaneWidth(it) } }
            .launchIn(viewModelScope)
    }

    private val listPaneWidth = combine(userPrefs.listPaneWidth, _draggedPaneWidth) { stored, dragged -> dragged ?: stored }

    // --- The Unified Reactive State (typed slices instead of a 26-way untyped Array combine) ---
    val uiState: StateFlow<UiState> = combine(
        combine(_currentPath, _searchQuery, _fileView, listPaneWidth, _navigationTrigger) { path, query, fileView, width, trigger ->
            BrowserSlice(path, query, fileView, width, trigger)
        },
        combine(_selectedFilePath, _selectedFileName, _selectedFileContent, _isEditing, _saveState) { path, name, content, editing, saveState ->
            DocumentSlice(path, name, content, editing, saveState)
        },
        combine(_isLoading, _isRefreshing, _isSyncing, _isIndexing, _isFocusMode) { loading, refreshing, syncing, indexing, focus ->
            ProgressSlice(loading, refreshing, syncing, indexing, focus)
        },
        combine(_userMessage, _error, _importState, _isExitPending, _showDeleteConfirmation) { message, error, import, exitPending, showDelete ->
            MessageSlice(message, error, import, exitPending, showDelete)
        },
        combine(_analysisResult, _availableMoveDestinations, _moveDialogCurrentPath, _availableTemplates, _showCreateFileDialog) { analysis, destinations, movePath, templates, showCreate ->
            DialogSlice(analysis, destinations, movePath, templates, showCreate)
        }
    ) { browser, document, progress, messages, dialogs ->
        UiState(
            searchQuery = browser.query,
            currentPath = browser.path,
            files = browser.fileView.dtos,
            selectedFileContent = document.content,
            selectedFileName = document.selectedName,
            selectedFilePath = document.selectedPath,
            isLoading = progress.isLoading,
            isRefreshing = progress.isRefreshing,
            isSyncing = progress.isSyncing,
            isIndexing = progress.isIndexing,
            saveState = document.saveState,
            userMessage = messages.userMessage,
            error = messages.error,
            importState = messages.importState,
            isFocusMode = progress.isFocusMode,
            isEditing = document.isEditing,
            listPaneWidth = browser.paneWidth,
            folders = browser.fileView.folders,
            analysisResult = dialogs.analysis,
            availableMoveDestinations = dialogs.moveDestinations,
            moveDialogCurrentPath = dialogs.moveDialogPath,
            isExitPending = messages.isExitPending,
            showDeleteConfirmation = messages.showDeleteConfirmation,
            availableTemplates = dialogs.templates,
            showCreateFileDialog = dialogs.showCreateFileDialog,
            navigationTrigger = browser.navigationTrigger
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState(isLoading = true))

    private val _autoSaveTrigger = MutableStateFlow<String?>(null)

    // Flag to track if the current import session came from an external Intent
    private var isExternalShare = false

    // The file targeted by a swipe-delete. Null means "delete the currently open file"
    // (the detail-pane top-bar delete action). Kept out of uiState — the UI never reads it.
    private var pendingDeleteFile: GithubFileDto? = null

    // Cache for valid move destinations during a move operation
    private var cachedValidMoveDestinations: List<String> = emptyList()

    init {
        // Keep the global sound-effects flag in sync with the user's preference.
        userPrefs.soundEffectsEnabled
            .onEach { soundEffectManager.enabled = it }
            .launchIn(viewModelScope)

        viewModelScope.launch(Dispatchers.IO) {
            widgetSnapshotManager.updateSnapshot()
        }

        _autoSaveTrigger
            .debounce(AUTOSAVE_DEBOUNCE_MS)
            .onEach { content -> if (content != null && hasUnsavedChanges) saveFile() }
            .launchIn(viewModelScope)

        // A completed sync cycle (pull-to-refresh on any screen) may have pulled a newer version
        // of the open note.
        syncOrchestrator.syncState
            .onEach { state -> if (state is SyncState.Success) onVaultContentChanged() }
            .launchIn(viewModelScope)
    }

    fun setNavBarOrder(order: List<String>) {
        viewModelScope.launch { userPrefs.setNavBarOrder(order) }
    }

    fun updateListPaneWidth(width: Float) {
        _draggedPaneWidth.value = width
    }

    fun loadFolder(path: String) {
        _currentPath.value = path
        _error.value = null
    }

    /** Pull-to-refresh on the file list. */
    fun refreshCurrentFolder() = syncWithRemote()

    /**
     * Pull-to-refresh on the open note. It used to do nothing at all: it only acted on a download
     * URL, which vault files no longer have.
     */
    fun refreshCurrentFile() = syncWithRemote()

    /** Commit → pull → push, then refresh everything derived from the vault (Room, open note, widgets). */
    private fun syncWithRemote() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            _isSyncing.value = true
            try {
                // Save what the editor holds first, so the pull can never clobber unsaved typing.
                flushEditor()
                val (owner, repo) = withContext(Dispatchers.IO) { secretManager.getRepoInfo() }
                if (owner == null || repo == null) return@launch

                val result = repository.syncRepository(owner, repo)
                if (result.isSuccess) {
                    _error.value = null
                    // The pull may have brought new mood/habit/chore JSON: rebuild Room from it.
                    syncOrchestrator.reconcileVaultIntoRoom()
                    onVaultContentChanged()
                    widgetSnapshotManager.updateSnapshot()
                } else {
                    val errorMsg = result.exceptionOrNull()?.localizedMessage ?: "Unknown error"
                    _error.value = "Sync Failed: $errorMsg"
                    _uiEvent.emit(UiEvent.ShowToast("Sync Failed ❌: $errorMsg"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiEvent.emit(UiEvent.ShowToast("Sync Error ⚠️: ${e.localizedMessage}"))
            } finally {
                _isRefreshing.value = false
                _isSyncing.value = false
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun loadFile(file: GithubFileDto) {
        saveEditorInBackground()
        _selectedFileName.value = file.name
        _selectedFilePath.value = file.path
        _isEditing.value = false
        unsavedContent = null
        _error.value = null
        // (Re)read it from disk — also when re-opening the file that is already selected.
        _contentRefresh.update { it + 1 }
    }

    /**
     * Open a vault file by relative path from a non-browser surface (e.g. an AI
     * source-citation chip). Content flows reactively from the selected path;
     * the "home" navigation trigger routes back to the file browser, whose
     * selectedFileName effect opens the detail pane.
     */
    fun openVaultFile(path: String) {
        if (path.startsWith("[[") || (!path.contains("/") && !path.endsWith(".md", ignoreCase = true))) {
            onWikiLinkClick(path)
            _navigationTrigger.value = "home"
            return
        }
        val dto = GithubFileDto(
            name = path.substringAfterLast('/'),
            path = path,
            type = "file",
            downloadUrl = null,
            sha = null,
            lastModified = System.currentTimeMillis()
        )
        loadFile(dto)
        _navigationTrigger.value = "home"
    }

    fun closeFile() {
        saveEditorInBackground()
        _selectedFileName.value = null
        _selectedFilePath.value = null
        _isEditing.value = false
        unsavedContent = null
    }

    fun toggleEditMode() {
        val next = !_isEditing.value
        if (next) {
            if (unsavedContent == null) {
                val current = uiState.value.selectedFileContent
                unsavedContent = current ?: ""
                // What the editor starts from is what's on disk.
                val path = _selectedFilePath.value
                if (path != null && current != null) savedContents[path] = current
            }
        } else {
            // Leaving the editor saves right away instead of waiting for the typing debounce.
            saveFile()
        }
        _isEditing.value = next
    }

    fun onContentChanged(newContent: String) {
        val oldContent = unsavedContent ?: uiState.value.selectedFileContent ?: ""
        TaskCompletionDetector.newlyCompletedTasks(oldContent, newContent).forEach { task ->
            // Task was just checked off! Process XP.
            viewModelScope.launch {
                gamificationRepository.processTaskCompletion(task)
                _uiEvent.emit(UiEvent.ShowToast("Quest Completed! ✨"))
            }
        }

        unsavedContent = newContent
        _saveState.value = SaveState.UNSAVED
        _autoSaveTrigger.value = newContent
    }

    /**
     * Saves the open note: a local write plus a batched background push. Autosave runs this on
     * every typing pause; it used to run a full network commit → pull → push each time (holding the
     * git lock) and toast "Saved & Synced".
     */
    fun saveFile() {
        val path = _selectedFilePath.value ?: return
        val content = unsavedContent ?: return
        if (savedContents[path] == content) return

        viewModelScope.launch {
            _saveState.value = SaveState.SAVING
            try {
                writeNote(path, content)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _saveState.value = SaveState.ERROR
                _uiEvent.emit(UiEvent.ShowToast("Save failed ❌: ${e.message}"))
                return@launch
            }
            if (unsavedContent != content) {
                // The user kept typing while this version was being written.
                _saveState.value = SaveState.UNSAVED
                return@launch
            }
            _saveState.value = SaveState.SAVED
            delay(SAVED_BADGE_MS)
            if (_saveState.value == SaveState.SAVED) _saveState.value = SaveState.IDLE
        }
    }

    fun forceSaveImmediate() {
        if (hasUnsavedChanges) {
            saveFile()
        }
    }

    /** The note's text right before the last Time Machine restore, so the restore can be undone. */
    private data class RestoreBackup(val path: String, val content: String)
    private var restoreBackup: RestoreBackup? = null

    private val _restoreUndo = MutableStateFlow<String?>(null)
    /** Non-null while the last restore can be undone: the snackbar message offering "Undo". */
    val restoreUndo: StateFlow<String?> = _restoreUndo.asStateFlow()

    /**
     * Replaces the open note with a version from its git history. Unlike [onContentChanged] this is
     * not a user edit: tasks that were already checked in the old version must not grant XP again.
     */
    fun restoreRevision(historicalContent: String) {
        val path = _selectedFilePath.value ?: return
        val current = unsavedContent ?: uiState.value.selectedFileContent ?: return
        if (current == historicalContent) return
        restoreBackup = RestoreBackup(path, current)
        replaceEditorContent(historicalContent)
        saveFile()
        _restoreUndo.value = "Version restored"
    }

    /** Puts back the text the last [restoreRevision] replaced. */
    fun undoRestore() {
        val backup = restoreBackup ?: return
        restoreBackup = null
        _restoreUndo.value = null
        if (_selectedFilePath.value == backup.path) {
            replaceEditorContent(backup.content)
            saveFile()
        } else {
            viewModelScope.launch {
                try {
                    writeNote(backup.path, backup.content)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _uiEvent.emit(UiEvent.ShowToast("Undo failed: ${e.message}"))
                }
            }
        }
    }

    /** The undo snackbar went away without being used. */
    fun restoreUndoShown() {
        _restoreUndo.value = null
        restoreBackup = null
    }

    private fun replaceEditorContent(newContent: String) {
        unsavedContent = newContent
        _saveState.value = SaveState.UNSAVED
        _autoSaveTrigger.value = newContent
    }

    suspend fun getFileCommitHistory(path: String = _selectedFilePath.value ?: ""): List<GitCommitDto> {
        // Encrypted notes are ciphertext in git: their history can't be shown or restored here.
        if (path.isEmpty() || path.endsWith(".enc")) return emptyList()
        return repository.getFileCommitHistory(path)
    }

    suspend fun getFileContentAtCommit(path: String = _selectedFilePath.value ?: "", commitId: String): String? {
        if (path.isEmpty() || path.endsWith(".enc")) return null
        return repository.getFileContentAtCommit(path, commitId)
    }

    /** Local write + batched background push; a no-op when [content] is already on disk. */
    private suspend fun writeNote(path: String, content: String) {
        if (savedContents[path] == content) return
        repository.saveFileLocally(path, content)
        savedContents[path] = content
        pushScheduler.requestPush()
    }

    /** Writes the editor copy of the open note to disk if it holds anything unsaved. */
    private suspend fun flushEditor() {
        val path = _selectedFilePath.value ?: return
        val content = unsavedContent ?: return
        writeNote(path, content)
    }

    /** Saves the CURRENT note's pending edits before it is replaced or closed. */
    private fun saveEditorInBackground() {
        val path = _selectedFilePath.value ?: return
        val content = unsavedContent ?: return
        if (savedContents[path] == content) return
        viewModelScope.launch {
            try {
                writeNote(path, content)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiEvent.emit(UiEvent.ShowToast("Save failed ❌: ${e.message}"))
            }
        }
    }

    /** The vault changed on disk (pull, sync cycle): show the refreshed version of the open note. */
    private fun onVaultContentChanged() {
        val path = _selectedFilePath.value
        val editorCopy = unsavedContent
        // Only an editor copy holding nothing unsaved follows the disk; unsaved typing is kept.
        if (path != null && editorCopy != null && savedContents[path] == editorCopy) {
            if (!_isEditing.value) {
                unsavedContent = null
            } else {
                // Still editing: adopt the file as the pull left it — otherwise the next keystroke
                // saves the pre-pull copy over whatever the pull brought in.
                viewModelScope.launch {
                    val onDisk = try {
                        repository.readFile(path)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    } ?: return@launch
                    if (_selectedFilePath.value == path && unsavedContent === editorCopy) {
                        savedContents[path] = onDisk
                        unsavedContent = onDisk
                    }
                }
            }
        }
        _contentRefresh.update { it + 1 }
    }

    /**
     * viewModelScope is already cancelled here, and with it any typing autosave or pause flush still
     * in flight: finishing the activity right after typing lost the last edits. They are written from
     * the application scope instead.
     */
    override fun onCleared() {
        val path = _selectedFilePath.value ?: return
        val content = unsavedContent ?: return
        if (savedContents[path] == content) return
        appScope.launch {
            try {
                repository.saveFileLocally(path, content)
                pushScheduler.requestPush()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Final save of the open note failed", e)
            }
        }
    }

    fun navigateBack(): Boolean {
        val path = _currentPath.value
        if (path.isNotEmpty()) {
            _currentPath.value = if (path.contains("/")) path.substringBeforeLast("/") else ""
            _error.value = null
            return true
        }
        return false
    }

    fun toggleFocusMode() {
        _isFocusMode.value = !_isFocusMode.value
    }

    private fun FileEntity.toDto() = GithubFileDto(
        name = name,
        path = path,
        type = type,
        downloadUrl = downloadUrl,
        sha = sha,
        lastModified = localModifiedTimestamp ?: lastSyncedAt
    )

    fun handleShareIntent(intent: android.content.Intent) {
        if (intent.action == android.content.Intent.ACTION_SEND) {
            val htmlText = intent.getStringExtra(android.content.Intent.EXTRA_HTML_TEXT)
            val plainText = intent.getCharSequenceExtra(android.content.Intent.EXTRA_TEXT)

            val textToProcess: CharSequence?
            val isMarkdown: Boolean

            if (htmlText != null) {
                textToProcess = ContentSanitizer.htmlToMarkdown(htmlText)
                isMarkdown = true
            } else if (plainText != null) {
                textToProcess = plainText
                isMarkdown = !ContentSanitizer.URL_REGEX.matches(plainText.trim())
            } else {
                textToProcess = null
                isMarkdown = false
            }

            if (textToProcess != null) {
                isExternalShare = true
                _isLoading.value = true
                viewModelScope.launch {
                    val result = ContentSanitizer.processSharedContent(textToProcess, isMarkdown)
                    _isLoading.value = false
                    _importState.value = ImportState(result.title, result.markdownContent)
                }
            }
        }
    }

    fun confirmImport(filename: String, folder: String, content: String) {
        val fullPath = if (folder.isNotBlank()) "$folder/$filename" else filename
        val fromExternalShare = isExternalShare
        viewModelScope.launch {
            try {
                repository.saveFileLocally(fullPath, content)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiEvent.emit(UiEvent.ShowToast("Import failed ❌: ${e.message}"))
                return@launch
            }
            // Batched background push: it also survives this screen closing right away, which
            // cancelled the inline push of an external share.
            pushScheduler.requestPush()

            dismissImport()
            if (fromExternalShare) {
                _userMessage.value = "Imported & Saved"
                _isExitPending.value = true
            } else {
                loadFile(
                    GithubFileDto(
                        name = filename,
                        path = fullPath,
                        type = "file",
                        downloadUrl = null,
                        lastModified = System.currentTimeMillis()
                    )
                )
                _userMessage.value = "Imported successfully"
            }
        }
    }

    fun dismissImport() {
        _importState.value = null
        isExternalShare = false
    }

    fun userMessageShown() {
        _userMessage.value = null
    }

    fun consumeNavigationTrigger() {
        _navigationTrigger.value = null
    }

    /**
     * Programmatically navigate to a top-level [route], driven from a widget tap / launcher
     * shortcut. The route is validated at the intent boundary ([cloud.wafflecommons.pixelbrainreader.widget.ui.WidgetNav.screenToRoute]);
     * the existing `navigationTrigger` LaunchedEffect in MainScreen performs the actual navigation.
     */
    fun requestNavigation(route: String) {
        _navigationTrigger.value = route
    }

    fun requestDeleteFile(file: GithubFileDto? = null) {
        // A swipe-delete passes the row's file so we can delete it WITHOUT opening it first
        // (opening caused the doc to flash into the detail pane before the confirm dialog).
        pendingDeleteFile = file
        _showDeleteConfirmation.value = true
    }

    fun dismissDeleteConfirmation() {
        _showDeleteConfirmation.value = false
        pendingDeleteFile = null
    }

    fun confirmDeleteFile() {
        _showDeleteConfirmation.value = false
        val target = pendingDeleteFile
        pendingDeleteFile = null

        // Prefer the explicit swipe target; otherwise fall back to the open file.
        val path = target?.path
            ?: _selectedFilePath.value
            ?: _selectedFileName.value?.let { name -> uiState.value.files.find { it.name == name }?.path }
            ?: return
        val selected = _selectedFilePath.value
        val deletesOpenFile = selected != null && (selected == path || selected.startsWith("$path/"))

        viewModelScope.launch {
            _isSyncing.value = true
            _userMessage.value = "Deleting file..."

            val result = repository.deleteFile(path)
            _isSyncing.value = false

            if (result.isSuccess) {
                savedContents.keys.removeAll { it == path || it.startsWith("$path/") }
                // Only close the detail pane if the deleted item held the open file. Never touch
                // _currentPath — the user stays in the current folder's list. The editor copy is
                // dropped first so closing can't write the deleted note back.
                if (deletesOpenFile) {
                    unsavedContent = null
                    closeFile()
                }
                pushScheduler.requestPush()
                _userMessage.value = "File Deleted"
            } else {
                val msg = result.exceptionOrNull()?.message ?: "Unknown"
                _error.value = "Delete Failed: $msg"
                _uiEvent.emit(UiEvent.ShowToast("Delete Failed ❌: $msg"))
            }
        }
    }

    fun renameFile(newName: String, targetFile: GithubFileDto? = null) {
        val path = targetFile?.path ?: _selectedFilePath.value ?: return
        val isDirectory = uiState.value.files.find { it.path == path }?.type == "dir"
        val parentPath = if (path.contains("/")) path.substringBeforeLast("/") else ""
        val finalNewName = if (isDirectory || newName.endsWith(".md")) newName else "$newName.md"
        val finalNewPath = if (parentPath.isNotEmpty()) "$parentPath/$finalNewName" else finalNewName
        if (finalNewPath == path) return

        relocate(
            oldPath = path,
            newPath = finalNewPath,
            progressMessage = "Renaming ${if (isDirectory) "folder" else "file"}...",
            successMessage = "Renamed successfully",
            failurePrefix = "Rename Failed"
        )
    }

    fun moveFile(file: GithubFileDto, targetFolder: String) {
        val newPath = if (targetFolder.isEmpty()) file.name else "$targetFolder/${file.name}"
        if (file.path == newPath) {
            _userMessage.value = "Item is already in this folder"
            return
        }

        relocate(
            oldPath = file.path,
            newPath = newPath,
            progressMessage = "Moving to ${if (targetFolder.isEmpty()) "Root" else targetFolder}...",
            successMessage = "Moved successfully",
            failurePrefix = "Move Failed"
        )
    }

    /** Local rename/move (recorded in a commit) + batched background push. */
    private fun relocate(oldPath: String, newPath: String, progressMessage: String, successMessage: String, failurePrefix: String) {
        viewModelScope.launch {
            _isSyncing.value = true
            _userMessage.value = progressMessage
            val result = try {
                // Pending edits of the open note are saved first, so they move with it.
                flushEditor()
                repository.renameFileSafe(oldPath, newPath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                _isSyncing.value = false
            }

            if (result.isSuccess) {
                followRelocation(oldPath, newPath)
                pushScheduler.requestPush()
                _userMessage.value = successMessage
            } else {
                _error.value = "$failurePrefix: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    /**
     * Keeps the open note (and its save bookkeeping) on its new path. Only the name used to be
     * updated, so the next save silently recreated the note at its OLD path.
     */
    private fun followRelocation(oldPath: String, newPath: String) {
        val selected = _selectedFilePath.value ?: return
        val relocated = when {
            selected == oldPath -> newPath
            selected.startsWith("$oldPath/") -> newPath + selected.removePrefix(oldPath)
            else -> return
        }
        savedContents.remove(selected)?.let { savedContents[relocated] = it }
        _selectedFilePath.value = relocated
        _selectedFileName.value = relocated.substringAfterLast('/')
    }

    /**
     * Smart Move Preparation.
     * Fetches folders and filters unavailable destinations.
     */
    fun prepareMove(targetFile: GithubFileDto) {
        viewModelScope.launch {
            val allFolders = repository.getAllFolders()

            // Filter global list once
            cachedValidMoveDestinations = allFolders.filter { folderPath ->
                // Rule 1: Exclude hidden folders (start with .)
                if (folderPath.split("/").any { it.startsWith(".") }) return@filter false

                // Rule 2: Cannot move folder into itself or its descendants
                if (targetFile.type == "dir") {
                    if (folderPath == targetFile.path) return@filter false
                    if (folderPath.startsWith("${targetFile.path}/")) return@filter false
                }

                true
            }.sorted()

            // Initialize the dialog at the root.
            updateMoveDialogContent("")
        }
    }

    fun navigateToMoveFolder(path: String) {
        updateMoveDialogContent(path)
    }

    fun navigateUp() {
        val current = _currentPath.value
        if (current.isNotEmpty()) {
            val parent = if (current.contains("/")) current.substringBeforeLast("/") else ""
            loadFolder(parent)
        }
    }

    fun navigateUpMoveFolder() {
        val current = _moveDialogCurrentPath.value
        if (current.isNotEmpty()) {
            val parent = if (current.contains("/")) current.substringBeforeLast("/") else ""
            updateMoveDialogContent(parent)
        }
    }

    private fun updateMoveDialogContent(currentPath: String) {
        val displayedFolders = cachedValidMoveDestinations.filter { folderPath ->
            if (currentPath.isEmpty()) !folderPath.contains("/")
            else folderPath.startsWith("$currentPath/") && !folderPath.substringAfter("$currentPath/").contains("/")
        }
        _moveDialogCurrentPath.value = currentPath
        _availableMoveDestinations.value = displayedFolders
    }

    fun openCreateFileDialog() {
        viewModelScope.launch {
            _availableTemplates.value = templateRepository.getAvailableTemplates()
            _showCreateFileDialog.value = true
        }
    }

    fun dismissCreateFileDialog() {
        _showCreateFileDialog.value = false
    }

    fun createNewFile(filename: String? = null, templateName: String? = null) {
        val parentPath = _currentPath.value
        val finalName = if (filename.isNullOrBlank()) "Untitled_${System.currentTimeMillis()}.md" else (if (filename.endsWith(".md")) filename else "$filename.md")
        val fullPath = if (parentPath.isNotEmpty()) "$parentPath/$finalName" else finalName

        viewModelScope.launch {
            var content = ""
            if (!templateName.isNullOrBlank()) {
                val templatePath = "${TemplateRepository.TEMPLATE_FOLDER}/$templateName"
                val templateContent = repository.getFileContentFlow(templatePath).firstOrNull() ?: ""
                content = TemplateEngine.apply(templateContent, finalName.substringBeforeLast("."))
            }

            repository.saveFileLocally(fullPath, content)
            savedContents[fullPath] = content
            pushScheduler.requestPush()
            _showCreateFileDialog.value = false

            val newDto = GithubFileDto(
                name = finalName,
                path = fullPath,
                type = "file",
                downloadUrl = null,
                lastModified = System.currentTimeMillis()
            )
            loadFile(newDto)
            _isEditing.value = true
            unsavedContent = content
        }
    }

    /**
     * Folder Insight / RAG Pivot
     * Analyzes current folder contents and generates an index.
     */
    fun analyzeCurrentFolder() {
        // The current listing is already the DIRECT children (no subdirectories), filtered
        // to files — exactly the "direct folder only" scope requested. No hard 10-file cap
        // here; GeminiRagManager bounds the work internally and summarizes file-by-file.
        val files = uiState.value.files.filter { it.type == "file" }
        if (files.isEmpty()) {
            _userMessage.value = "No files to analyze here."
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _userMessage.value = "Analyzing folder…"
            val fileContexts = files.mapNotNull { file ->
                val content = repository.getFileContentFlow(file.path).firstOrNull()
                if (content != null) Pair(file.name, content) else null
            }

            geminiRagManager.analyzeFolder(fileContexts) { done, total ->
                _userMessage.value = "Analyzing folder… ($done/$total)"
            }.fold(
                onSuccess = { rawSummary ->
                    val summary = rawSummary
                        .replace(Regex("^```markdown\\s*", RegexOption.IGNORE_CASE), "")
                        .replace(Regex("^```\\s*", RegexOption.IGNORE_CASE), "")
                        .replace(Regex("\\s*```$"), "").trim()
                    _isLoading.value = false
                    _userMessage.value = "Folder summary ready ✨"
                    // Folder insight is a READ-ONLY, ephemeral pseudo-document. Save the note that
                    // was open, then detach from any real file path — otherwise saveFile()/autosave
                    // would write this summary into that note, silently corrupting it. With
                    // path == null, saveFile() early-returns on its `_selectedFilePath` guard.
                    saveEditorInBackground()
                    _selectedFilePath.value = null
                    _selectedFileName.value = "Folder_Insight.md"
                    unsavedContent = summary
                    _isEditing.value = false
                    _saveState.value = SaveState.IDLE
                    // Force-open the Detail pane even if the pseudo-filename didn't change, so a
                    // re-run while an insight is already open still surfaces the fresh result.
                    _openDetailEvent.tryEmit(Unit)
                },
                onFailure = { e ->
                    // Surface the real failure — NEVER write the error text into a note body.
                    _isLoading.value = false
                    val reason = e.localizedMessage ?: e.message ?: "AI model unavailable"
                    _userMessage.value = "Analysis failed: $reason"
                }
            )
        }
    }

    /** Export the whole vault as a ZIP to a user-picked SAF destination (outside the app). */
    fun exportVault(outputUri: android.net.Uri) {
        viewModelScope.launch {
            _userMessage.value = "Exporting vault…"
            vaultExportRepository.exportVaultZip(outputUri) { done, total ->
                _userMessage.value = "Exporting vault… ($done/$total)"
            }.fold(
                onSuccess = { count -> _userMessage.value = "$count files exported ✅" },
                onFailure = { e ->
                    val reason = e.localizedMessage ?: e.message ?: "unknown error"
                    _userMessage.value = "Export failed: $reason"
                }
            )
        }
    }

    fun appendContent(text: String) {
        val currentContent = unsavedContent ?: uiState.value.selectedFileContent ?: ""

        val newContent = if (currentContent.isBlank()) text else "$currentContent\n\n$text"
        onContentChanged(newContent)
        _isEditing.value = true
    }

    fun onWikiLinkClick(linkText: String) {
        val cleanTarget = linkText.replace(WIKI_LINK_BRACKETS, "").split("|")[0].trim().removeSuffix("/")

        viewModelScope.launch {
            val allFolders = repository.getAllFolders()
            val matchingFolder = allFolders.find { it.equals(cleanTarget, ignoreCase = true) || it.endsWith("/$cleanTarget", ignoreCase = true) }
            if (matchingFolder != null) {
                loadFolder(matchingFolder)
                _userMessage.value = "📂 Opened ${matchingFolder.substringAfterLast("/")}"
                return@launch
            }
            val entity = repository.resolveLink(cleanTarget)
            if (entity != null) {
                if (entity.type == "dir") {
                    loadFolder(entity.path)
                    _userMessage.value = "📂 Opened ${entity.name}"
                } else loadFile(entity.toDto())
                return@launch
            }
            _userMessage.value = "Target '$cleanTarget' not found"
        }
    }

    fun saveChatToInbox(content: String) {
        viewModelScope.launch {
            val folderName = "00_Inbox"
            repository.createLocalFolder(folderName)
            // ROOT: the timestamp is part of a file name and must stay ASCII.
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
            repository.saveFileLocally("$folderName/AI_Note_$timestamp.md", content)
            pushScheduler.requestPush()
            _userMessage.value = "Saved to $folderName"
        }
    }

    fun onTodayClicked(pathOverride: String? = null, startEditing: Boolean = false) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val notePath = pathOverride ?: dailyNoteRepository.getOrCreateTodayNote()
                val noteName = notePath.substringAfterLast("/")
                val dto = GithubFileDto(name = noteName, path = notePath, type = "file", downloadUrl = null, sha = null, lastModified = System.currentTimeMillis())
                loadFile(dto)
                _isLoading.value = false
                _isEditing.value = startEditing
                _navigationTrigger.value = "home"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _isLoading.value = false
                _error.value = "Failed to open Daily Note: ${e.message}"
            }
        }
    }

    private companion object {
        const val AUTOSAVE_DEBOUNCE_MS = 1_500L
        const val SAVED_BADGE_MS = 2_500L
        const val SEARCH_DEBOUNCE_MS = 250L
        val WIKI_LINK_BRACKETS = Regex("[\\[\\]]")
    }
}

data class UiState(
    val searchQuery: String = "",
    val currentPath: String = "",
    val files: List<GithubFileDto> = emptyList(),
    val selectedFileContent: String? = null,
    val selectedFileName: String? = null,
    val selectedFilePath: String? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isSyncing: Boolean = false,
    val isIndexing: Boolean = false,
    val saveState: SaveState = SaveState.IDLE,
    val userMessage: String? = null,
    val error: String? = null,
    val importState: ImportState? = null,
    val isFocusMode: Boolean = false,
    val isEditing: Boolean = false,
    val folders: List<String> = emptyList(),
    val isExitPending: Boolean = false,
    val showDeleteConfirmation: Boolean = false,
    val listPaneWidth: Float = 300f,
    val availableMoveDestinations: List<String> = emptyList(),
    val moveDialogCurrentPath: String = "",
    val availableTemplates: List<String> = emptyList(),
    val showCreateFileDialog: Boolean = false,
    val navigationTrigger: String? = null,
    val analysisResult: String? = null
)

data class ImportState(val title: String, val content: String)
