package cloud.wafflecommons.pixelbrainreader.ui.guard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.guard.AllowEntry
import cloud.wafflecommons.pixelbrainreader.data.guard.ConfidentialityGuard
import cloud.wafflecommons.pixelbrainreader.data.guard.GuardFinding
import cloud.wafflecommons.pixelbrainreader.data.guard.GuardSettings
import cloud.wafflecommons.pixelbrainreader.data.guard.TextFold
import cloud.wafflecommons.pixelbrainreader.data.remote.JGitProvider
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.PrivateNoteRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.GitSyncCoordinator
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Arrays
import javax.inject.Inject

@HiltViewModel
class ConfidentialityViewModel @Inject constructor(
    private val guard: ConfidentialityGuard,
    private val jGitProvider: JGitProvider,
    private val gitSyncCoordinator: GitSyncCoordinator,
    private val pushScheduler: VaultPushScheduler,
    private val privateNoteRepository: PrivateNoteRepository,
    private val fileRepository: FileRepository
) : ViewModel() {

    val settings: StateFlow<GuardSettings> = guard.settings
    val held: StateFlow<List<GuardFinding>> = guard.held
    val lastCheckMillis: StateFlow<Long?> = guard.lastCheckMillis

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _settingsWereReset = MutableStateFlow(false)
    val settingsWereReset: StateFlow<Boolean> = _settingsWereReset.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** A hold was seen since the last clean check: when it clears, the changes must be pushed. */
    private var hadHold = false

    init {
        viewModelScope.launch {
            guard.held.collect { if (it.isNotEmpty()) hadHold = true }
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { guard.current() }
            _settingsWereReset.value = guard.settingsWereReset
            check()
        }
    }

    /**
     * Re-runs the guard on everything pending, without committing. When a hold has just cleared,
     * the changes are queued for the next batched push.
     */
    fun check() {
        if (_checking.value) {
            // A settings change during a check: run once more with the new settings.
            recheckRequested = true
            return
        }
        _checking.value = true
        viewModelScope.launch {
            try {
                do {
                    recheckRequested = false
                    gitSyncCoordinator.mutex.withLock { jGitProvider.reviewPendingChanges() }
                        .onSuccess { findings ->
                            if (findings.isEmpty() && hadHold) {
                                hadHold = false
                                pushScheduler.requestPush()
                                _messages.send("All clear: your changes will sync.")
                            }
                        }
                        .onFailure { _messages.send("Check failed: ${it.message}") }
                } while (recheckRequested)
            } finally {
                _checking.value = false
            }
        }
    }

    private var recheckRequested = false

    /** Adds a protected name; false when it is too short to match anything meaningful. */
    fun addTerm(raw: String): Boolean {
        val term = raw.trim()
        if (TextFold.term(term) == null) return false
        updateSettings { settings ->
            if (settings.terms.any { it.equals(term, ignoreCase = true) }) settings
            else settings.copy(terms = (settings.terms + term).sortedBy { it.lowercase() })
        }
        return true
    }

    fun removeTerm(term: String) = updateSettings { it.copy(terms = it.terms - term) }

    fun setDetectSecrets(enabled: Boolean) = updateSettings { it.copy(detectSecrets = enabled) }

    /** Lets [finding] through, in its note or in its whole folder. */
    fun allow(finding: GuardFinding, wholeFolder: Boolean) {
        val scope = if (wholeFolder) folderOf(finding.path)?.let { "$it/" } ?: return else finding.path
        updateSettings { settings ->
            val entry = AllowEntry(scope, finding.ruleKey)
            if (entry in settings.allowed) settings else settings.copy(allowed = settings.allowed + entry)
        }
    }

    fun clearAllowed() = updateSettings { it.copy(allowed = emptyList()) }

    /**
     * Encrypts the note into the private vault and deletes the clear copy. The password must open
     * the existing private notes; an existing private note with the same title is never replaced.
     */
    fun moveToPrivate(path: String, title: String, password: CharArray) {
        viewModelScope.launch {
            try {
                val safeTitle = title.trim().replace('/', '-').replace('\\', '-').removeSuffix(".md")
                if (safeTitle.isBlank()) {
                    _messages.send("Give the private note a title.")
                    return@launch
                }
                if (!privateNoteRepository.verifyPassword(password)) {
                    _messages.send("Wrong password")
                    return@launch
                }
                if (privateNoteRepository.getPrivateNotes().any { it.name == "$safeTitle.md.enc" }) {
                    _messages.send("A private note with this title already exists.")
                    return@launch
                }
                val content = fileRepository.readFile(path)
                if (content == null) {
                    _messages.send("The note is no longer there.")
                    return@launch
                }
                privateNoteRepository.createNote(safeTitle, content, password)
                fileRepository.deleteFile(path).getOrThrow()
                _messages.send("Moved to the private vault.")
                check()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("Could not move the note: ${e.message}")
            } finally {
                Arrays.fill(password, '\u0000')
            }
        }
    }

    /** A settings change can create or clear holds: check again right after it. */
    private fun updateSettings(transform: (GuardSettings) -> GuardSettings) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { guard.update(transform) }
            check()
        }
    }

    companion object {
        /** The folder holding [path], or null for a file at the vault root. */
        fun folderOf(path: String): String? = path.substringBeforeLast('/', "").ifEmpty { null }
    }
}
