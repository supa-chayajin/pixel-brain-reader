package cloud.wafflecommons.pixelbrainreader.ui.main

import android.util.Log
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import cloud.wafflecommons.pixelbrainreader.data.ai.IndexingWorker
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileEntity
import cloud.wafflecommons.pixelbrainreader.data.remote.model.GithubFileDto
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyNoteRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.TemplateRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.AppThemeConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.combine

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow


@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: FileRepository,
    private val dailyNoteRepository: DailyNoteRepository,
    private val templateRepository: TemplateRepository,
    private val secretManager: SecretManager,
    private val userPrefs: UserPreferencesRepository,
    private val geminiRagManager: cloud.wafflecommons.pixelbrainreader.data.ai.GeminiRagManager,
    private val widgetSnapshotManager: cloud.wafflecommons.pixelbrainreader.widget.manager.WidgetSnapshotManager,
    private val uiEffectManager: cloud.wafflecommons.pixelbrainreader.ui.utils.UiEffectManager,
    private val gamificationRepository: cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationRepository,
    private val jGitProvider: cloud.wafflecommons.pixelbrainreader.data.remote.JGitProvider,
    private val syncOrchestrator: cloud.wafflecommons.pixelbrainreader.data.sync.SyncOrchestrator,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _currentPath = MutableStateFlow("")


    private val _searchQuery = MutableStateFlow("")
    private val _selectedFilePath = MutableStateFlow<String?>(null)
    private val _selectedFileName = MutableStateFlow<String?>(null)
    private val _isEditing = MutableStateFlow(false)
    private val _unsavedContent = MutableStateFlow<String?>(null)
    private val _isLoading = MutableStateFlow(false)
    private val _isRefreshing = MutableStateFlow(false)
    private val _isSyncing = MutableStateFlow(false)
    private val _saveState = MutableStateFlow(cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.IDLE)
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

    // Expose Theme Preference
    val themeConfig: StateFlow<AppThemeConfig> = userPrefs.themeConfig
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppThemeConfig.FOLLOW_SYSTEM)

    // Global Effects (One-shot)
    val globalEffects = uiEffectManager.effects

    // Global Sync State (observable by UI)
    val globalSyncState = syncOrchestrator.syncState

    // Reactive File List
    private val _filesFlow = combine(_currentPath, _searchQuery) { path, query ->
        if (query.isBlank()) repository.getFiles(path)
        else repository.searchFiles(query)
    }.flatMapLatest { it }

    // Reactive File Content
    private val _selectedFileContent = _selectedFilePath.flatMapLatest { path ->
        if (path == null) flowOf(null)
        else repository.getFileContentFlow(path)
    }

    // --- The Unified Reactive State ---
    val uiState: StateFlow<UiState> = combine(
        _currentPath, _searchQuery, _selectedFilePath, _selectedFileName,
        _isEditing, _unsavedContent, _isLoading, _isRefreshing, _isSyncing,
        _saveState, _userMessage, _error, _importState, _isFocusMode,
        _isExitPending, _showDeleteConfirmation, userPrefs.listPaneWidth,
        _filesFlow, _selectedFileContent, _isIndexing, _analysisResult,
        _availableMoveDestinations, _moveDialogCurrentPath, _availableTemplates,
        _showCreateFileDialog, _navigationTrigger
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val path = args[0] as String
        val query = args[1] as String
        val selectedPath = args[2] as? String
        val selectedName = args[3] as? String
        val isEditing = args[4] as Boolean
        val unsavedContent = args[5] as? String
        val isLoading = args[6] as Boolean
        val isRefreshing = args[7] as Boolean
        val isSyncing = args[8] as Boolean
        val saveState = args[9] as cloud.wafflecommons.pixelbrainreader.ui.components.SaveState
        val userMsg = args[10] as? String
        val errorMsg = args[11] as? String
        val importState = args[12] as? ImportState
        val isFocusMode = args[13] as Boolean
        val isExitPending = args[14] as Boolean
        val showDelete = args[15] as Boolean
        val width = args[16] as Float
        val files = args[17] as List<FileEntity>
        val dbContent = args[18] as? String
        val isIndexing = args[19] as Boolean
        val analysis = args[20] as? String
        val moveDests = args[21] as List<String>
        val movePath = args[22] as String
        val templates = args[23] as List<String>
        val showCreate = args[24] as Boolean
        val navTrigger = args[25] as? String

        val dtos = files
            .map { it.toDto() }
            .filter { !it.name.startsWith(".") }
            .sortedWith(compareBy({ it.type != "dir" }, { it.name }))

        val foldersList = files
            .filter { it.type == "dir" }
            .map { it.path }
            .distinct()
            .sorted()

        UiState(
            searchQuery = query,
            currentPath = path,
            files = dtos,
            selectedFileContent = dbContent,
            unsavedContent = unsavedContent,
            selectedFileName = selectedName,
            selectedFilePath = selectedPath,
            isLoading = isLoading,
            isRefreshing = isRefreshing,
            isSyncing = isSyncing,
            isIndexing = isIndexing,
            saveState = saveState,
            userMessage = userMsg,
            error = errorMsg,
            importState = importState,
            isFocusMode = isFocusMode,
            isEditing = isEditing,
            listPaneWidth = width,
            folders = foldersList,
            analysisResult = analysis,
            availableMoveDestinations = moveDests,
            moveDialogCurrentPath = movePath,
            isExitPending = isExitPending,
            showDeleteConfirmation = showDelete,
            availableTemplates = templates,
            showCreateFileDialog = showCreate,
            navigationTrigger = navTrigger
        )

    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState(isLoading = true))


    private var isInitialSyncDone = false
    private val _autoSaveTriggerFlow = MutableStateFlow<String?>(null)

    init {
        performInitialSync()

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            widgetSnapshotManager.updateSnapshot()
        }

        _autoSaveTriggerFlow
            .debounce(1500L)
            .distinctUntilChanged()
            .onEach { content ->
                if (content != null && uiState.value.hasUnsavedChanges) {
                    saveFile()
                }
            }
            .launchIn(viewModelScope)
    }


    fun performInitialSync() {
        if (isInitialSyncDone) return
        isInitialSyncDone = true
        
        val (owner, repo) = secretManager.getRepoInfo()
        if (owner == null || repo == null) {
             _isLoading.value = false
            return
        }

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
             _isLoading.value = true
             _isSyncing.value = true
             
             try {
                 // Delegate to the global SyncOrchestrator for the strict Git→Health→Git cycle
                 val didSync = syncOrchestrator.executeFullSyncCycle()
                 
                 if (didSync) {
                     _uiEvent.emit(cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent.ShowToast("Sync Complete ✅"))
                     loadFolder(_currentPath.value)
                 } else {
                     Log.w("MainViewModel", "Initial sync was skipped (cooldown or in-progress)")
                 }
             } catch (e: Exception) {
                 Log.e("MainViewModel", "Sync Error", e)
                 _uiEvent.emit(cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent.ShowToast("Sync Error ⚠️: ${e.message}"))
             } finally {
                 triggerBrainOptimization()
                 _isLoading.value = false
                 _isSyncing.value = false
             }
        }
    }

    fun updateListPaneWidth(width: Float) {
       viewModelScope.launch {
           userPrefs.setListPaneWidth(width)
       }
    }
    
    fun loadFolder(path: String) {
        _currentPath.value = path
        _error.value = null
    }

    fun refreshCurrentFolder() {
        val (owner, repo) = secretManager.getRepoInfo()
        if (owner == null || repo == null) return

        viewModelScope.launch {
            _isRefreshing.value = true
            _isSyncing.value = true
            
            try {
                val result = repository.syncRepository(owner, repo)
                
                if (result.isSuccess) {
                    widgetSnapshotManager.updateSnapshot()
                    triggerBrainOptimization()
                } else {
                    val errorMsg = result.exceptionOrNull()?.localizedMessage ?: "Unknown error"
                    _error.value = "Sync Failed: $errorMsg"
                    _uiEvent.emit(cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent.ShowToast("Sync Failed ❌: $errorMsg"))
                }
            } catch (e: Exception) {
                 _uiEvent.emit(cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent.ShowToast("Sync Error ⚠️: ${e.localizedMessage}"))
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
        _selectedFileName.value = file.name
        _selectedFilePath.value = file.path
        _isEditing.value = false
        _unsavedContent.value = null
        _error.value = null
        
        if (file.downloadUrl != null) {
            syncFile(file.path, file.downloadUrl, isUserAction = false)
        }
    }
    
    fun refreshFile(file: GithubFileDto) {
        if (file.downloadUrl == null) return
        syncFile(file.path, file.downloadUrl, isUserAction = true)
    }

    fun closeFile() {
        _selectedFileName.value = null
        _selectedFilePath.value = null
        _isEditing.value = false
        _unsavedContent.value = null
    }

    private fun syncFile(path: String, url: String, isUserAction: Boolean) {
         viewModelScope.launch {
            if (isUserAction) {
                _isRefreshing.value = true
                _isSyncing.value = true
            }
            repository.refreshFileContent(path, url)
            if (isUserAction) {
                _isRefreshing.value = false
                _isSyncing.value = false
            }
         }
    }


    fun toggleEditMode() {
        val next = !_isEditing.value
        if (next && _unsavedContent.value == null) {
             _unsavedContent.value = uiState.value.selectedFileContent ?: ""
        }
        _isEditing.value = next
    }

    fun onContentChanged(newContent: String) {
        val oldContent = _unsavedContent.value ?: uiState.value.selectedFileContent ?: ""
        detectAndProcessTaskCompletion(oldContent, newContent)

        _unsavedContent.value = newContent
        _saveState.value = cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.UNSAVED
        _autoSaveTriggerFlow.value = newContent
    }


    private fun detectAndProcessTaskCompletion(oldContent: String, newContent: String) {
        val oldLines = oldContent.lines()
        val newLines = newContent.lines()
        
        for (newLine in newLines) {
            val trimmed = newLine.trimStart()
            if (trimmed.startsWith("- [x]", ignoreCase = true)) {
                val uncheckedVersion1 = newLine.replaceFirst("- [x]", "- [ ]", ignoreCase = true)
                val uncheckedVersion2 = newLine.replaceFirst("- [X]", "- [ ]") 
                
                // If the old content had the unchecked version, but didn't have the checked version
                if ((oldLines.contains(uncheckedVersion1) || oldLines.contains(uncheckedVersion2)) && !oldLines.contains(newLine)) {
                    // Task was just checked off! Process XP.
                    viewModelScope.launch {
                        gamificationRepository.processTaskCompletion(newLine)
                        // Show subtle feedback
                        _uiEvent.emit(cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent.ShowToast("Quest Completed! ✨"))
                    }
                }
            }
        }
    }

    fun refreshCurrentFile() {
        val fileName = _selectedFileName.value ?: return
        val file = uiState.value.files.find { it.name == fileName } ?: return

        if (file.downloadUrl == null) return

        viewModelScope.launch {
            _isRefreshing.value = true
            _isSyncing.value = true
            repository.refreshFileContent(file.path, file.downloadUrl)
            _isRefreshing.value = false
            _isSyncing.value = false
        }

    }

    // Event Channel
    private val _uiEvent = kotlinx.coroutines.flow.MutableSharedFlow<cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val uiEvent = _uiEvent.asSharedFlow()


    fun saveFile() {
        val path = _selectedFilePath.value ?: return
        val content = _unsavedContent.value ?: return

        viewModelScope.launch {
            try {
                _isSyncing.value = true
                _saveState.value = cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.SAVING
                
                val (owner, repo) = secretManager.getRepoInfo()
                val result = repository.saveAndSync(path, content, owner, repo)
                
                if (result.isSuccess) {
                    _saveState.value = cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.SAVED
                    
                    launch {
                        kotlinx.coroutines.delay(2500L)
                        if (_saveState.value == cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.SAVED) {
                            _saveState.value = cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.IDLE
                        }
                    }
                    _uiEvent.emit(cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent.ShowToast("Saved & Synced ✅"))
                } else {
                    val msg = result.exceptionOrNull()?.message ?: "Unknown"
                    _saveState.value = cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.ERROR
                    _error.value = "Sync Warning: $msg"
                    _uiEvent.emit(cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent.ShowToast("Saved Locally. Sync Failed"))
                }
            } catch (e: Exception) {
                 _uiEvent.emit(cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent.ShowToast("Save Failed ❌: ${e.message}"))
                 _saveState.value = cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.ERROR
            } finally {
                _isSyncing.value = false
            }
        }
    }

    
    private fun syncDirtyFiles() {
        val (owner, repo) = secretManager.getRepoInfo()
        if (owner == null || repo == null) return

        viewModelScope.launch {
            _isSyncing.value = true
            val result = repository.pushDirtyFiles(owner, repo)
            if (result.isFailure) {
                _error.value = "Push Failed: ${result.exceptionOrNull()?.message}"
            }
            _isSyncing.value = false
        }

    }

    fun forceSaveImmediate() {
        if (uiState.value.hasUnsavedChanges) {
            saveFile()
        }
    }

    fun navigateBack(): Boolean {
        val path = _currentPath.value
        if (path.isNotEmpty()) {
            _currentPath.value = if(path.contains("/")) path.substringBeforeLast("/") else ""
            _error.value = null
            return true
        }
        return false
    }


    fun logout() {
        secretManager.clear()
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

    // Flag to track if the current import session came from an external Intent
    private var isExternalShare = false

    fun handleShareIntent(intent: android.content.Intent) {
        if (intent.action == android.content.Intent.ACTION_SEND) {
            val htmlText = intent.getStringExtra(android.content.Intent.EXTRA_HTML_TEXT)
            val plainText = intent.getCharSequenceExtra(android.content.Intent.EXTRA_TEXT)
            
            val textToProcess: CharSequence?
            val isMarkdown: Boolean

            if (htmlText != null) {
                textToProcess = cloud.wafflecommons.pixelbrainreader.data.utils.ContentSanitizer.htmlToMarkdown(htmlText)
                isMarkdown = true
            } else if (plainText != null) {
                textToProcess = plainText
                isMarkdown = !cloud.wafflecommons.pixelbrainreader.data.utils.ContentSanitizer.URL_REGEX.matches(plainText.trim())
            } else {
                textToProcess = null
                isMarkdown = false
            }
            
            if (textToProcess != null) {
                isExternalShare = true 
                _isLoading.value = true
                viewModelScope.launch {
                    val result = cloud.wafflecommons.pixelbrainreader.data.utils.ContentSanitizer.processSharedContent(textToProcess, isMarkdown)
                    _isLoading.value = false
                    _importState.value = ImportState(result.title, result.markdownContent)
                }
            }

        }
    }

    fun confirmImport(filename: String, folder: String, content: String) {
        val fullPath = if (folder.isNotBlank()) "$folder/$filename" else filename
        viewModelScope.launch {
             repository.saveFileLocally(fullPath, content)
             
             if (isExternalShare) {
                 dismissImport()
                 _userMessage.value = "Imported & Saved"
                 _isExitPending.value = true
             } else {
                 val newDto = GithubFileDto(
                    name = filename,
                    path = fullPath,
                    type = "file", 
                    downloadUrl = null,
                    lastModified = System.currentTimeMillis()
                )
                 dismissImport()
                 loadFile(newDto)
                 _userMessage.value = "Imported successfully"
             }

             
              val (owner, repo) = secretManager.getRepoInfo()
              if (owner != null && repo != null) {
                    _isSyncing.value = true
                   try {
                       val result = repository.pushDirtyFiles(owner, repo)
                       if (result.isSuccess) {
                           _uiEvent.emit(cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent.ShowToast("Synced with Git ✅"))
                       } else {
                           val msg = result.exceptionOrNull()?.message ?: "Unknown"
                           _uiEvent.emit(cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent.ShowToast("Git Sync Failed ❌: $msg"))
                       }
                   } catch (e: Exception) {
                       _uiEvent.emit(cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent.ShowToast("Git Sync Failed ❌: ${e.message}"))
                   }
                    _isSyncing.value = false

              }
             isExternalShare = false
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

    
    fun requestDeleteFile() {
        _showDeleteConfirmation.value = true
    }


    fun dismissDeleteConfirmation() {
        _showDeleteConfirmation.value = false
    }


    fun confirmDeleteFile() {
        dismissDeleteConfirmation()
        val fileName = _selectedFileName.value ?: return
        val path = _selectedFilePath.value ?: uiState.value.files.find { it.name == fileName }?.path ?: return

        
        val (owner, repo) = secretManager.getRepoInfo()
        
        viewModelScope.launch {
            _isSyncing.value = true
            _userMessage.value = "Deleting specified file..."
            
            val result = repository.deleteFile(path, owner, repo)
            
            if (result.isSuccess) {
                closeFile()
                navigateBack() // Go back to list
                _userMessage.value = "File Deleted"
                _isSyncing.value = false
            } else {
                 val msg = result.exceptionOrNull()?.message ?: "Unknown"
                 _error.value = "Delete Failed: $msg"
                 _isSyncing.value = false
                 _uiEvent.emit(cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent.ShowToast("Delete Failed ❌: $msg"))
            }
        }

    }

    fun renameFile(newName: String, targetFile: GithubFileDto? = null) {
        val path = targetFile?.path ?: _selectedFilePath.value ?: return
        val isDirectory = uiState.value.files.find { it.path == path }?.type == "dir"
        val parentPath = if(path.contains("/")) path.substringBeforeLast("/") else ""
        val finalNewName = if (isDirectory) newName else (if (newName.endsWith(".md")) newName else "$newName.md")
        val finalNewPath = if(parentPath.isNotEmpty()) "$parentPath/$finalNewName" else finalNewName
        if (finalNewPath == path) return

        val (owner, repo) = secretManager.getRepoInfo()

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _isSyncing.value = true
            _userMessage.value = "Renaming ${if(isDirectory) "folder" else "file"}..."
            
            val result = repository.renameAndSync(path, finalNewPath, owner, repo)
            if (result.isSuccess) {
                 if (targetFile == null) _selectedFileName.value = finalNewName
                 _userMessage.value = "Renamed successfully"
            } else {
                 _error.value = "Rename Failed: ${result.exceptionOrNull()?.message}"
            }
            _isSyncing.value = false
        }
    }


    fun moveFile(file: GithubFileDto, targetFolder: String) {
        val currentPath = file.path
        val newPath = if (targetFolder.isEmpty()) file.name else "$targetFolder/${file.name}"
        
        if (currentPath == newPath) {
             _userMessage.value = "Item is already in this folder"
             return
        }

        val (owner, repo) = secretManager.getRepoInfo()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
             _isSyncing.value = true
             _userMessage.value = "Moving to ${if(targetFolder.isEmpty()) "Root" else targetFolder}..."

            val result = repository.renameAndSync(currentPath, newPath, owner, repo)
             if (result.isSuccess) {
                _userMessage.value = "Moved successfully"
            } else {
                 _error.value = "Move Failed: ${result.exceptionOrNull()?.message}"
            }
            _isSyncing.value = false
        }
    }




    // Cache for valid move destinations during a move operation
    private var cachedValidMoveDestinations: List<String> = emptyList()

    /**
     * Smart Move Preparation.
     * Fetches folders and filters unavailable destinations.
     */
    fun prepareMove(targetFile: GithubFileDto) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val allFolders = repository.getAllFolders()
            val currentParent = if(targetFile.path.contains("/")) targetFile.path.substringBeforeLast("/") else ""
            
            // Filter global list once
            cachedValidMoveDestinations = allFolders.filter { folderPath ->
                // Rule 1: Exclude hidden folders (start with .)
                if (folderPath.split("/").any { it.startsWith(".") }) return@filter false

                // Rule 2: Cannot move folder into itself or its descendants
                if (targetFile.type == "dir") {
                    if (folderPath == targetFile.path) return@filter false
                    if (folderPath.startsWith("${targetFile.path}/")) return@filter false
                }
                
                // Rule 3: Allow current parent (Context) - REMOVED the check that excluded currentParent
                
                true
            }.sorted()

            // Initialize Dialog at Root (or start at current parent for better UX? No, start at root is safer navigation)
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
        val finalName = if (filename.isNullOrBlank()) "Untitled_${System.currentTimeMillis()}.md" else (if(filename.endsWith(".md")) filename else "$filename.md")
        val fullPath = if (parentPath.isNotEmpty()) "$parentPath/$finalName" else finalName
        
        viewModelScope.launch {
            var content = ""
            if (!templateName.isNullOrBlank()) {
                 val templatePath = "${TemplateRepository.TEMPLATE_FOLDER}/$templateName"
                 val templateContent = repository.getFileContentFlow(templatePath).firstOrNull() ?: ""
                 content = cloud.wafflecommons.pixelbrainreader.data.utils.TemplateEngine.apply(templateContent, finalName.substringBeforeLast("."))
            }

            repository.saveFileLocally(fullPath, content)
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
            _unsavedContent.value = content
        }
    }


    /**
     * Folder Insight / RAG Pivot
     * Analyzes current folder contents and generates an index.
     */
    fun analyzeCurrentFolder() {
        val files = uiState.value.files.filter { it.type == "file" }.take(10)
        if (files.isEmpty()) {
            _userMessage.value = "No files to analyze here."
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _userMessage.value = "AI analyzing folder..."
            val fileContexts = files.mapNotNull { file ->
                val content = repository.getFileContentFlow(file.path).firstOrNull() 
                if (content != null) Pair(file.name, content) else null
            }

            val rawSummary = geminiRagManager.analyzeFolder(fileContexts)
            val summary = rawSummary.replace(Regex("^```markdown\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("^```\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*```$"), "").trim()
            
            _isLoading.value = false
            _selectedFileName.value = "Folder_Insight.md"
            _unsavedContent.value = summary
            _isEditing.value = false
        }
    }


    fun appendContent(text: String) {
        val currentContent = _unsavedContent.value ?: uiState.value.selectedFileContent ?: ""

        val newContent = if (currentContent.isBlank()) text else "$currentContent\n\n$text"
        onContentChanged(newContent)
        _isEditing.value = true
    }

    fun onWikiLinkClick(linkText: String) {
        var target = linkText.replace(Regex("[\\[\\]]"), "").split("|")[0].trim().removeSuffix("/")
        val cleanTarget = target

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
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
            val fullPath = "$folderName/AI_Note_$timestamp.md"
            repository.saveFileLocally(fullPath, content)
            _userMessage.value = "Saved to $folderName"
             val (owner, repo) = secretManager.getRepoInfo()
             if (owner != null && repo != null) repository.pushDirtyFiles(owner, repo)
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
            } catch (e: Exception) {
                _isLoading.value = false
                _error.value = "Failed to open Daily Note: ${e.message}"
            }
        }
    }

    private fun triggerBrainOptimization() {
        _isIndexing.value = true
        val workRequest = OneTimeWorkRequestBuilder<IndexingWorker>().setInputData(workDataOf("FULL_REINDEX" to true)).addTag("brain_optimization").build()
        WorkManager.getInstance(context).enqueue(workRequest as WorkRequest)
        viewModelScope.launch {
            WorkManager.getInstance(context).getWorkInfoByIdFlow(workRequest.id).collect { workInfo ->
                if (workInfo != null && workInfo.state.isFinished) {
                    _isIndexing.value = false
                    if(workInfo.state == androidx.work.WorkInfo.State.SUCCEEDED) _userMessage.value = "Brain Optimized 🧠"
                }
            }
        }
    }

}

data class UiState(
    val searchQuery: String = "",
    val currentPath: String = "",
    val files: List<GithubFileDto> = emptyList(),
    val selectedFileContent: String? = null,
    val unsavedContent: String? = null,
    val selectedFileName: String? = null,
    val selectedFilePath: String? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isSyncing: Boolean = false,
    val isIndexing: Boolean = false,
    val saveState: cloud.wafflecommons.pixelbrainreader.ui.components.SaveState = cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.IDLE,
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
) {
    val hasUnsavedChanges: Boolean get() = unsavedContent != null
}

data class ImportState(val title: String, val content: String)


