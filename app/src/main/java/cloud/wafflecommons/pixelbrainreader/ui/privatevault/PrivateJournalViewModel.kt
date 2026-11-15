package cloud.wafflecommons.pixelbrainreader.ui.privatevault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.ai.LocalAiManager
import cloud.wafflecommons.pixelbrainreader.data.privatevault.AutoLock
import cloud.wafflecommons.pixelbrainreader.data.privatevault.PasswordRules
import cloud.wafflecommons.pixelbrainreader.data.privatevault.PrivateVaultUnlocker
import cloud.wafflecommons.pixelbrainreader.data.privatevault.PrivateVaultRekey
import cloud.wafflecommons.pixelbrainreader.data.privatevault.PrivateVaultSession
import cloud.wafflecommons.pixelbrainreader.data.privatevault.VaultPasswordBinding
import cloud.wafflecommons.pixelbrainreader.data.repository.PrivateNoteRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.di.ApplicationScope
import cloud.wafflecommons.pixelbrainreader.ui.components.SaveState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Arrays
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class PrivateJournalViewModel @Inject constructor(
    private val repository: PrivateNoteRepository,
    private val session: PrivateVaultSession,
    private val binding: VaultPasswordBinding,
    private val unlocker: PrivateVaultUnlocker,
    private val rekey: PrivateVaultRekey,
    private val preferences: UserPreferencesRepository,
    private val localAiManager: LocalAiManager,
    @param:ApplicationScope private val appScope: CoroutineScope
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

    // Always mutated through update { }: saves used to copy the state from a background thread
    // while keystrokes wrote it on Main, so a save could revert what had just been typed.
    private val _uiState = MutableStateFlow(VaultState())
    val uiState = _uiState.asStateFlow()

    private val _autoSaveTriggerFlow = MutableStateFlow<String?>(null)

    private val _saveState = MutableStateFlow(SaveState.IDLE)
    val saveState = _saveState.asStateFlow()

    // One-time events
    sealed class UiEvent {
        data class ShowToast(val message: String) : UiEvent()
        data class OpenEditor(val file: File) : UiEvent()
    }

    // Buffered: sending must never suspend the operation that reports it.
    private val _uiEvent = Channel<UiEvent>(Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()

    // --- Session Management ---
    // The password lives in PrivateVaultSession (memory only, 12.18 V3), never in this state.

    /** A password check is running (it derives a key). Main thread only. */
    private var isUnlocking = false

    /** Last content written per note path: autosave and the pause flush skip unchanged notes. Main thread only. */
    private val savedContents = mutableMapOf<String, String>()

    private val _biometricReady = MutableStateFlow(false)

    /** The face or fingerprint can unlock: the password is bound to the biometric key (V3). */
    val biometricReady = _biometricReady.asStateFlow()

    /** How long the journal waits off screen before it locks (V2), as the menu offers it. */
    val autoLockMinutes: StateFlow<Int> = preferences.privateAutoLockMinutes
        .map { AutoLock.minutesOrDefault(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AutoLock.DEFAULT_MINUTES)

    fun setAutoLockMinutes(minutes: Int) {
        viewModelScope.launch { preferences.setPrivateAutoLockMinutes(AutoLock.minutesOrDefault(minutes)) }
    }

    /** A change of password (V1), as its dialog shows it. */
    sealed interface PasswordChange {
        data object Idle : PasswordChange
        data class Working(val done: Int, val total: Int) : PasswordChange
        data class Done(val files: Int) : PasswordChange
        data class Problem(val message: String) : PasswordChange
    }

    private val _passwordChange = MutableStateFlow<PasswordChange>(PasswordChange.Idle)
    val passwordChange: StateFlow<PasswordChange> = _passwordChange.asStateFlow()

    /**
     * Re-encrypts every private note with [new] (12.18, V1). Runs in the application scope: leaving
     * the screen mid-way doesn't stop it (and a crash is finished or rolled back at the next start).
     */
    fun changePassword(current: String, new: String, repeat: String) {
        if (VaultWriteGate.refuses() || _passwordChange.value is PasswordChange.Working) return
        PasswordRules.problem(current, new, repeat)?.let {
            _passwordChange.value = PasswordChange.Problem(it)
            return
        }
        _passwordChange.value = PasswordChange.Working(0, 0)
        val currentChars = current.toCharArray()
        val newChars = new.toCharArray()
        appScope.launch {
            try {
                val result = rekey.change(currentChars, newChars) { done, total ->
                    _passwordChange.value = PasswordChange.Working(done, total)
                }
                _passwordChange.value = when (result) {
                    is PrivateVaultRekey.Result.Changed -> PasswordChange.Done(result.files)
                    PrivateVaultRekey.Result.WrongPassword -> PasswordChange.Problem("The current password is wrong.")
                    PrivateVaultRekey.Result.Locked -> PasswordChange.Problem("The journal locked meanwhile: unlock it and try again.")
                    PrivateVaultRekey.Result.ReadOnly -> PasswordChange.Problem("Read-only device: change it on your phone.")
                    is PrivateVaultRekey.Result.Unreadable -> PasswordChange.Problem(
                        "${result.names.size} note(s) don't open with this password: ${result.names.joinToString()}. Nothing was changed."
                    )
                    is PrivateVaultRekey.Result.NotChanged -> PasswordChange.Problem(result.reason)
                }
                if (result is PrivateVaultRekey.Result.Changed) {
                    refreshFiles()
                    refreshBiometric()
                }
            } finally {
                Arrays.fill(currentChars, '\u0000')
                Arrays.fill(newChars, '\u0000')
            }
        }
    }

    fun dismissPasswordChange() {
        if (_passwordChange.value !is PasswordChange.Working) _passwordChange.value = PasswordChange.Idle
    }

    init {
        refreshBiometric()
        // Locked from anywhere (a logout, the auto-lock): the open note leaves the screen too.
        session.unlocked
            .onEach { unlocked ->
                when {
                    !unlocked && !_uiState.value.isLocked -> showLocked()
                    unlocked && _uiState.value.isLocked -> {
                        _uiState.update { it.copy(isLocked = false, errorMessage = null, passwordInput = "") }
                        refreshFiles()
                    }
                }
            }
            .launchIn(viewModelScope)
        // Auto-Save Pipeline
        _autoSaveTriggerFlow
            .debounce(1500L) // Wait for 1.5 seconds of typing pause
            .distinctUntilChanged()
            .onEach { content ->
                val state = _uiState.value
                if (content != null && state.selectedFile != null && !state.isLocked) {
                    saveNote()
                }
            }
            .launchIn(viewModelScope)
    }

    fun onAuthError(msg: String) {
        _uiState.update { it.copy(errorMessage = msg) }
    }

    fun onPasswordInputChanged(input: String) {
        _uiState.update { it.copy(passwordInput = input) }
    }

    fun unlockWithPassword() {
        val input = _uiState.value.passwordInput
        if (input.isNotEmpty()) {
            val chars = input.toCharArray()
            unlock(chars, viaBiometric = false)
            Arrays.fill(chars, '\u0000')
        }
    }

    private fun refreshBiometric() {
        viewModelScope.launch {
            _biometricReady.value = withContext(Dispatchers.IO) {
                binding.migrateLegacy()
                binding.available() && binding.isBound()
            }
        }
    }

    private fun refreshFiles() {
        viewModelScope.launch {
            val files = repository.getPrivateNotes()
            _uiState.update { it.copy(files = files) }
        }
    }

    fun startNewNote() {
        if (VaultWriteGate.refuses()) return // a read-only device writes nothing
        // Triggered by FAB -> Dialog -> createNote
        _uiState.update {
            it.copy(
                selectedFile = null,
                editorContent = "",
                editorRevision = it.editorRevision + 1,
                isCreatingNew = true
            )
        }
    }

    /**
     * Unlocks the vault with [password] once it is VERIFIED to open the existing notes. Any password
     * used to be accepted and saved: a typo then encrypted every new note with the wrong key.
     * [viaBiometric]: it came from the biometric key (V3); a typed one is bound to it afterwards.
     */
    fun unlock(password: CharArray, viaBiometric: Boolean) {
        if (password.isEmpty() || isUnlocking) return
        isUnlocking = true
        val candidate = password.clone() // the caller wipes its own
        viewModelScope.launch {
            // Verified against the notes, then the session, the binding and the follow-ups (12.19: shared with the prep).
            val opened = try {
                unlocker.unlock(candidate, viaBiometric)
            } finally {
                isUnlocking = false
                Arrays.fill(candidate, '\u0000')
            }
            if (!opened) {
                val message = if (viaBiometric) {
                    "This phone's copy of the password no longer opens the journal: type it once."
                } else {
                    "Wrong password"
                }
                _uiState.update { it.copy(errorMessage = message, passwordInput = "") }
                return@launch
            }
            refreshBiometric()
        }
    }

    /** Locks now: the password and its keys leave memory. */
    fun lockNow() = lockVault()

    private fun lockVault(reason: String? = null) {
        session.lock()
        showLocked(reason)
    }

    private fun showLocked(reason: String? = null) {
        savedContents.clear()
        _uiState.update {
            it.copy(
                isLocked = true,
                selectedFile = null,
                editorContent = "",
                errorMessage = reason
            )
        }
    }

    // --- Operations ---

    fun openNote(file: File) {
        val key = session.key() ?: run {
            lockVault("Session expired. Please unlock again.")
            return
        }

        viewModelScope.launch {
            try {
                val content = try {
                    repository.readNote(file, key)
                } finally {
                    key.wipe()
                }
                savedContents[file.path] = content
                _uiState.update {
                    it.copy(
                        selectedFile = file,
                        editorContent = content,
                        editorRevision = it.editorRevision + 1,
                        isCreatingNew = false
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Decryption Failed: ${e.message}") }
            }
        }
    }

    fun createNote(filename: String, initialContent: String = "") {
        if (VaultWriteGate.refuses()) return // a read-only device writes nothing
        viewModelScope.launch {
            try {
                // 1. Validate
                if (filename.isBlank()) return@launch

                // 2. The session's password
                val key = session.key() ?: run {
                    _uiEvent.send(UiEvent.ShowToast("Session expired. Please re-authenticate."))
                    lockVault("Session expired")
                    return@launch
                }

                val safeName = if (filename.endsWith(".md.enc")) filename else "$filename.md.enc"

                // 3. Create
                try {
                    repository.createNote(safeName, initialContent, key)
                } finally {
                    key.wipe()
                }

                // 4. Force Refresh immediately
                val updatedFiles = repository.getPrivateNotes()
                val newFile = updatedFiles.find { it.name == safeName }
                    ?: File(updatedFiles.firstOrNull()?.parentFile, safeName)
                savedContents[newFile.path] = initialContent

                _uiState.update {
                    it.copy(
                        files = updatedFiles,
                        selectedFile = newFile,
                        editorContent = initialContent,
                        editorRevision = it.editorRevision + 1,
                        isCreatingNew = false
                    )
                }

                // 5. Feedback
                _uiEvent.send(UiEvent.ShowToast("Note created successfully"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("PrivateVM", "Creation Error", e)
                _uiEvent.send(UiEvent.ShowToast("Error: ${e.message}"))
            }
        }
    }

    fun onEditorContentChange(text: String) {
        if (VaultWriteGate.refuses()) return // a read-only device writes nothing
        _uiState.update { it.copy(editorContent = text) }
        _saveState.value = SaveState.UNSAVED
        _autoSaveTriggerFlow.value = text
    }

    /** Replace the editor content from a NON-keystroke source (AI apply). Bumps [VaultState.editorRevision]
     *  so the editor reconciles its TextFieldValue; still marks unsaved + schedules the autosave. */
    private fun replaceEditorContent(text: String) {
        _uiState.update { it.copy(editorContent = text, editorRevision = it.editorRevision + 1) }
        _saveState.value = SaveState.UNSAVED
        _autoSaveTriggerFlow.value = text
    }

    /**
     * Flushes the buffer to disk aggressively bypassing the debounce.
     * Hooks natively into Android lifecycle events (onPause/onStop).
     */
    fun forceSaveImmediate() {
        val state = _uiState.value
        if (state.selectedFile != null && !state.isLocked) {
            saveNote()
        }
    }

    fun saveNote() {
        if (VaultWriteGate.readOnly) return // nothing to save: a read-only device never edits
        val state = _uiState.value
        val file = state.selectedFile ?: return
        val content = state.editorContent
        // Unchanged since the last write (e.g. the pause flush right after an autosave): skip the key
        // derivation, the encryption and the push entirely.
        if (savedContents[file.path] == content) return

        // Locked meanwhile: the save already asked for (the pause flush) holds its own key.
        val key = session.key() ?: return

        viewModelScope.launch {
            _saveState.value = SaveState.SAVING
            try {
                repository.createNote(file.name, content, key) // Overwrite
                savedContents[file.path] = content
                refreshFiles()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Save Failed: ${e.message}") }
                _saveState.value = SaveState.ERROR
                return@launch
            } finally {
                key.wipe()
            }
            if (_uiState.value.editorContent != content) {
                // The user kept typing while this version was being written.
                _saveState.value = SaveState.UNSAVED
                return@launch
            }
            _saveState.value = SaveState.SAVED
            delay(2500L)
            if (_saveState.value == SaveState.SAVED) {
                _saveState.value = SaveState.IDLE
            }
        }
    }

    fun deleteCurrentNote() {
        if (VaultWriteGate.refuses()) return // a read-only device writes nothing
        val file = _uiState.value.selectedFile ?: return
        viewModelScope.launch {
            repository.deleteNote(file)
            savedContents.remove(file.path)
            // No save flush here: it would write the deleted note back.
            clearSelection()
            refreshFiles()
        }
    }

    fun closeNote() {
        saveNote() // Flush pending edits (no-op when nothing changed)
        clearSelection()
    }

    private fun clearSelection() {
        _uiState.update {
            it.copy(
                selectedFile = null,
                editorContent = "",
                isCreatingNew = false
            )
        }
    }

    /**
     * viewModelScope is already cancelled here, and with it any autosave or pause flush still in
     * flight — closing the journal right after typing lost the last edits (a session's first save also
     * derives the key, which takes a moment). They are saved from the application scope, and the
     * derived keys are dropped once nothing needs them any more. Closing the journal locks it.
     */
    override fun onCleared() {
        val state = _uiState.value
        val file = state.selectedFile
        val key = session.key()
        session.lock()
        val content = state.editorContent
        if (VaultWriteGate.readOnly || file == null || key == null || state.isLocked || savedContents[file.path] == content) {
            key?.wipe()
            return
        }
        appScope.launch {
            try {
                repository.createNote(file.name, content, key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("PrivateVM", "Final save of the open note failed")
            } finally {
                key.wipe()
                repository.forgetKeys()
            }
        }
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

    fun openAssist() {
        if (VaultWriteGate.refuses()) return // no AI on a read-only device
        _assistState.value = AssistState(visible = true)
    }
    fun dismissAssist() { _assistState.value = AssistState() }

    /** Runs a French journaling-assist action over the current entry. Never mutates the note
     *  directly — the result is shown for the user to explicitly apply. */
    fun runWritingAssist(action: AssistAction) {
        if (VaultWriteGate.refuses()) return // a read-only device writes nothing
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
                onFailure = { AssistState(visible = true, action = action, error = it.localizedMessage ?: "AI model unavailable") }
            )
        }
    }

    fun applyAssistReplace() {
        if (VaultWriteGate.refuses()) return // a read-only device writes nothing
        val r = _assistState.value.result ?: return
        replaceEditorContent(r)
        dismissAssist()
    }

    fun applyAssistAppend() {
        if (VaultWriteGate.refuses()) return // a read-only device writes nothing
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
        if (VaultWriteGate.readOnly) return null // no AI on a read-only device
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
                _uiEvent.send(UiEvent.ShowToast("Formatting failed: ${it.localizedMessage ?: "AI model unavailable"}"))
                null
            }
        )
    }
}
