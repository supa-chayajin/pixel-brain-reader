package cloud.wafflecommons.pixelbrainreader.ui.privatevault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.repository.PrivateNoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class PrivateJournalViewModel @Inject constructor(
    private val repository: PrivateNoteRepository,
    private val secretManager: cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager,
    private val localAiManager: cloud.wafflecommons.pixelbrainreader.data.ai.LocalAiManager
) : ViewModel() {

    data class VaultState(
        val isLocked: Boolean = true,
        val files: List<File> = emptyList(),
        val selectedFile: File? = null,
        val editorContent: String = "",
        // Bumped ONLY when editorContent is replaced externally (note open, AI apply) — never on the
        // user's own keystrokes. The editor keys its TextFieldValue reconcile on this so a laggy
        // echo of the user's own typing can't jump the caret to the end mid-edit.
        val editorRevision: Int = 0,
        val isCreatingNew: Boolean = false,
        val errorMessage: String? = null,
        val passwordInput: String = ""
    )

    private val _uiState = MutableStateFlow(VaultState())
    val uiState = _uiState.asStateFlow()

    private val _autoSaveTriggerFlow = MutableStateFlow<String?>(null)

    private val _saveState = MutableStateFlow(cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.IDLE)
    val saveState = _saveState.asStateFlow()

    init {
        // Attempt to load existing notes if already unlocked (e.g. process death restoration)
        if (!_uiState.value.isLocked) {
            refreshFiles()
        }

        // Auto-Save Pipeline
        _autoSaveTriggerFlow
            .debounce(1500L) // Wait for 1.5 seconds of typing pause
            .distinctUntilChanged()
            .onEach { content ->
                if (content != null && _uiState.value.selectedFile != null && !_uiState.value.isLocked) {
                    saveNote()
                }
            }
            .launchIn(viewModelScope)
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
            editorRevision = _uiState.value.editorRevision + 1,
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
             lockVault("Session expirée. Veuillez déverrouiller à nouveau.")
             return
        }
        
        viewModelScope.launch {
            try {
                val content = repository.readNote(file, pwd)
                _uiState.value = _uiState.value.copy(
                    selectedFile = file,
                    editorContent = content,
                    editorRevision = _uiState.value.editorRevision + 1,
                    isCreatingNew = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = "Échec du déchiffrement : ${e.message}")
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
                     _uiEvent.send(UiEvent.ShowToast("Session expirée. Veuillez vous authentifier à nouveau."))
                     lockVault("Session expirée")
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
                    editorRevision = _uiState.value.editorRevision + 1,
                    isCreatingNew = false
                )
                
                // 5. Feedback
                _uiEvent.send(UiEvent.ShowToast("Note créée avec succès"))

            } catch (e: Exception) {
                android.util.Log.e("PrivateVM", "Creation Error", e)
                _uiEvent.send(UiEvent.ShowToast("Erreur : ${e.message}"))
            }
        }
    }
    
    fun onEditorContentChange(text: String) {
        _uiState.value = _uiState.value.copy(editorContent = text)
        _saveState.value = cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.UNSAVED
        _autoSaveTriggerFlow.value = text
    }

    /** Replace the editor content from a NON-keystroke source (AI apply). Bumps [VaultState.editorRevision]
     *  so the editor reconciles its TextFieldValue; still marks unsaved + schedules the autosave. */
    private fun replaceEditorContent(text: String) {
        _uiState.value = _uiState.value.copy(
            editorContent = text,
            editorRevision = _uiState.value.editorRevision + 1
        )
        _saveState.value = cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.UNSAVED
        _autoSaveTriggerFlow.value = text
    }
    
    /**
     * Flushes the buffer to disk aggressively bypassing the debounce.
     * Hooks natively into Android lifecycle events (onPause/onStop).
     */
    fun forceSaveImmediate() {
        if (_uiState.value.selectedFile != null && !_uiState.value.isLocked) {
            saveNote()
        }
    }
    
    fun saveNote() {
        val content = _uiState.value.editorContent
        val file = _uiState.value.selectedFile ?: return 
        
        // Check Cache
        val pwd = sessionPassword ?: run {
             lockVault("Session expirée pendant l'enregistrement.")
             return
        }
        
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _saveState.value = cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.SAVING
            try {
                repository.createNote(file.name, content, pwd) // Overwrite
                refreshFiles()
                _saveState.value = cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.SAVED
                launch {
                    kotlinx.coroutines.delay(2500L)
                    if (_saveState.value == cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.SAVED) {
                        _saveState.value = cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.IDLE
                    }
                }
            } catch (e: Exception) {
               _uiState.value = _uiState.value.copy(errorMessage = "Échec de l'enregistrement : ${e.message}")
               _saveState.value = cloud.wafflecommons.pixelbrainreader.ui.components.SaveState.ERROR
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

    // --- AI writing assistant (100% on-device via LocalAiManager; safe for the encrypted vault) ---

    enum class AssistAction { IMPROVE, CONTINUE, INSPIRE }

    data class AssistState(
        val visible: Boolean = false,
        val isLoading: Boolean = false,
        val action: AssistAction? = null,
        val result: String? = null,
        val error: String? = null
    )

    private val _assistState = MutableStateFlow(AssistState())
    val assistState = _assistState.asStateFlow()

    fun openAssist() { _assistState.value = AssistState(visible = true) }
    fun dismissAssist() { _assistState.value = AssistState() }

    /** Runs a French journaling-assist action over the current entry. Never mutates the note
     *  directly — the result is shown for the user to explicitly apply. */
    fun runWritingAssist(action: AssistAction) {
        val content = _uiState.value.editorContent
        _assistState.value = AssistState(visible = true, isLoading = true, action = action)
        viewModelScope.launch {
            val prompt = when (action) {
                AssistAction.IMPROVE -> """
                    Tu es un assistant d'écriture bienveillant. Réécris ce texte de journal intime
                    en français, en améliorant le style, la clarté et la fluidité, SANS inventer de
                    faits ni changer le sens, en gardant un ton personnel et naturel. Réponds
                    uniquement avec le texte réécrit.

                    Texte :
                    $content
                """.trimIndent()
                AssistAction.CONTINUE -> """
                    Tu es un assistant d'écriture. Poursuis ce texte de journal intime en français,
                    sur 2 à 4 phrases, dans le même ton et le même style. Réponds uniquement avec la suite.

                    Texte :
                    $content
                """.trimIndent()
                AssistAction.INSPIRE -> buildString {
                    appendLine("Propose 3 questions courtes et bienveillantes, en français, pour m'aider à")
                    appendLine("écrire mon journal intime aujourd'hui. Une par ligne, format liste avec un tiret.")
                    if (content.isNotBlank()) {
                        appendLine()
                        appendLine("Contexte de ce que j'ai déjà écrit :")
                        append(content.take(1500))
                    }
                }
            }
            val result = localAiManager.generateResponse(prompt)
            _assistState.value = result.fold(
                onSuccess = { AssistState(visible = true, action = action, result = it.trim()) },
                onFailure = { AssistState(visible = true, action = action, error = it.localizedMessage ?: "Modèle IA indisponible") }
            )
        }
    }

    fun applyAssistReplace() {
        val r = _assistState.value.result ?: return
        replaceEditorContent(r)
        dismissAssist()
    }

    fun applyAssistAppend() {
        val r = _assistState.value.result ?: return
        val cur = _uiState.value.editorContent
        replaceEditorContent(if (cur.isBlank()) r else "$cur\n\n$r")
        dismissAssist()
    }

    /**
     * Reformats a snippet of the note into clean Markdown using the on-device model (100% local,
     * safe for the encrypted vault). Returns the reformatted text, or null on failure (a toast
     * explains why). Never mutates the note itself — the editor splices the result into the
     * current selection.
     */
    suspend fun beautifyMarkdown(selectedText: String): String? {
        if (selectedText.isBlank()) return null
        val prompt = """
            Reformate le texte suivant en Markdown propre et bien structuré. Applique des titres,
            des listes à puces ou numérotées, du gras et de l'italique, et des blocs de code là où
            cela convient. N'ajoute, ne supprime, ne traduis et n'invente aucune information —
            reformate uniquement ce qui est fourni. Réponds UNIQUEMENT avec le Markdown résultant,
            sans explication autour.

            Texte :
            $selectedText
        """.trimIndent()
        return localAiManager.generateResponse(prompt).fold(
            onSuccess = { it.trim() },
            onFailure = {
                _uiEvent.send(UiEvent.ShowToast("Échec de la mise en forme : ${it.localizedMessage ?: "Modèle IA indisponible"}"))
                null
            }
        )
    }
}
