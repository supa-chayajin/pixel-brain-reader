package cloud.wafflecommons.pixelbrainreader.ui.privatevault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.repository.PrivateNoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class PrivateJournalViewModel @Inject constructor(
    private val repository: PrivateNoteRepository,
    private val secretManager: cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
) : ViewModel() {

    data class VaultState(
        val isLocked: Boolean = true,
        val files: List<File> = emptyList(),
        val selectedFile: File? = null,
        val editorContent: String = "",
        val isCreatingNew: Boolean = false,
        val errorMessage: String? = null,
        val passwordInput: String = ""
    )

    private val _uiState = MutableStateFlow(VaultState())
    val uiState = _uiState.asStateFlow()

    init {
        // Attempt to load existing notes if already unlocked (e.g. process death restoration)
        if (!_uiState.value.isLocked) {
            refreshFiles()
        }
    }

    // REMOVED onAuthSuccess - It created invalid state (Unlocked UI but No Password)
    
    fun onAuthError(msg: String) {
        _uiState.value = _uiState.value.copy(errorMessage = msg)
    }

    fun onPasswordInputChanged(input: String) {
        _uiState.value = _uiState.value.copy(passwordInput = input)
    }

    fun unlockWithPassword() {
        val input = _uiState.value.passwordInput
        if (input.isNotEmpty()) {
            unlock(input.toCharArray())
        }
    }

    private fun refreshFiles() {
        viewModelScope.launch {
            val files = repository.getPrivateNotes()
            _uiState.value = _uiState.value.copy(files = files)
        }
    }

    fun startNewNote() {
        // Triggered by FAB -> Dialog -> createNote
        // This old method might be redundant if we use the Dialog directly, 
        // but let's keep it if we need a blank editor state before saving.
        // Requested workflow: FAB -> Dialog -> createNote -> Open Editor.
        _uiState.value = _uiState.value.copy(
            selectedFile = null,
            editorContent = "",
            isCreatingNew = true
        )
    }
    
    // One-time events
    sealed class UiEvent {
        data class ShowToast(val message: String) : UiEvent()
        data class OpenEditor(val file: File) : UiEvent()
    }
    
    private val _uiEvent = kotlinx.coroutines.channels.Channel<UiEvent>()
    val uiEvent = _uiEvent.receiveAsFlow()

    // --- Session Management ---
    // CACHED SESSION KEY (In-Memory Only)
    // We do NOT expose this in the StateFlow to avoid leaking it to UI loggers/inspector.
    private var sessionPassword: CharArray? = null

    fun unlock(password: CharArray) {
        if (password.isNotEmpty()) {
            // 1. Cache in memory
            sessionPassword = password.clone() // Clone for safety
            
            // 2. Persist in SecretManager for future Biometric Unlocks
            // In a production app, we should VERIFY the password validity before saving.
            // For now, we assume if the user Unlocks manually, they are setting the session.
            // If they enter the Wrong password, they will just see garbage data or fail to read.
            // A "Canary" file check would be better here.
            secretManager.saveVaultPassword(String(password))

            // 3. Update UI State
            _uiState.value = _uiState.value.copy(
                isLocked = false, 
                errorMessage = null,
                passwordInput = "" // Clear input
            )
            
            refreshFiles()
        }
    }
    
    private fun lockVault(reason: String? = null) {
        sessionPassword = null // Wipe key
        _uiState.value = _uiState.value.copy(
            isLocked = true,
            selectedFile = null,
            editorContent = "",
            errorMessage = reason
        )
    }
    
    // --- Operations ---

    fun openNote(file: File) {
        // Check Cache
        val pwd = sessionPassword ?: run {
             // Session Expired
             lockVault("Session expired. Please unlock again.")
             return
        }
        
        viewModelScope.launch {
            try {
                val content = repository.readNote(file, pwd)
                _uiState.value = _uiState.value.copy(
                    selectedFile = file,
                    editorContent = content,
                    isCreatingNew = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = "Decryption Failed: ${e.message}")
            }
        }
    }

    fun createNote(filename: String, initialContent: String = "") {
        viewModelScope.launch {
            try {
                // 1. Validate
                if (filename.isBlank()) return@launch
                
                // 2. Check Cache
                val pwd = sessionPassword ?: run {
                     _uiEvent.send(UiEvent.ShowToast("Session expired. Please re-authenticate."))
                     lockVault("Session expired")
                     return@launch
                }

                val safeName = if (filename.endsWith(".md.enc")) filename else "$filename.md.enc"

                // 3. Create
                repository.createNote(safeName, initialContent, pwd)
                
                // 4. Force Refresh immediately
                val updatedFiles = repository.getPrivateNotes()
                val newFile = updatedFiles.find { it.name == safeName } ?: File(repository.getPrivateNotes().firstOrNull()?.parentFile, safeName) // Fallback file object if list sync is slow, though repo read should see it.
                
                _uiState.value = _uiState.value.copy(
                    files = updatedFiles,
                    selectedFile = newFile,
                    editorContent = initialContent,
                    isCreatingNew = false
                )
                
                // 5. Feedback
                _uiEvent.send(UiEvent.ShowToast("Note created successfully"))
                
            } catch (e: Exception) {
                android.util.Log.e("PrivateVM", "Creation Error", e)
                _uiEvent.send(UiEvent.ShowToast("Error: ${e.message}"))
            }
        }
    }
    
    fun onEditorContentChange(text: String) {
        _uiState.value = _uiState.value.copy(editorContent = text)
    }
    
    fun saveNote() {
        val content = _uiState.value.editorContent
        val file = _uiState.value.selectedFile ?: return 
        
        // Check Cache
        val pwd = sessionPassword ?: run {
             lockVault("Session expired during save.")
             return
        }
        
        viewModelScope.launch {
            try {
                repository.createNote(file.name, content, pwd) // Overwrite
                refreshFiles()
            } catch (e: Exception) {
               _uiState.value = _uiState.value.copy(errorMessage = "Save Failed: ${e.message}")
            }
        }
    }

    fun deleteCurrentNote() {
        val file = _uiState.value.selectedFile ?: return
        viewModelScope.launch {
            repository.deleteNote(file)
            closeNote()
            refreshFiles()
        }
    }

    fun closeNote() {
        _uiState.value = _uiState.value.copy(
            selectedFile = null,
            editorContent = "",
            isCreatingNew = false
        )
    }
}
