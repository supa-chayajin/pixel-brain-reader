package cloud.wafflecommons.pixelbrainreader.ui.meetings

import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Diversity3
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.meetings.MeetingPrepRepository
import cloud.wafflecommons.pixelbrainreader.data.meetings.MeetingPrepResult
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.people.People
import cloud.wafflecommons.pixelbrainreader.data.people.PeopleRepository
import cloud.wafflecommons.pixelbrainreader.data.people.Person
import cloud.wafflecommons.pixelbrainreader.data.people.PersonKind
import cloud.wafflecommons.pixelbrainreader.data.people.PersonLinks
import cloud.wafflecommons.pixelbrainreader.data.privatevault.PrivateVaultSession
import cloud.wafflecommons.pixelbrainreader.data.privatevault.PrivateVaultUnlocker
import cloud.wafflecommons.pixelbrainreader.data.privatevault.VaultPasswordBinding
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.ui.components.EmptyHint
import cloud.wafflecommons.pixelbrainreader.ui.components.FormScaffold
import cloud.wafflecommons.pixelbrainreader.ui.components.SectionCard
import cloud.wafflecommons.pixelbrainreader.ui.privatevault.PrivateJournalActivity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import javax.crypto.Cipher
import javax.inject.Inject

/** D4: a calendar entry's prep (route `meeting_prep?entry=`), its minutes in one tap, and (12.19) the person it's with. */
@HiltViewModel
class MeetingPrepViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MeetingPrepRepository,
    private val people: PeopleRepository,
    private val session: PrivateVaultSession,
    private val unlocker: PrivateVaultUnlocker,
    private val binding: VaultPasswordBinding,
    private val preferences: UserPreferencesRepository,
    modesRepository: ModesRepository
) : ViewModel() {

    sealed interface PrepState {
        data object Loading : PrepState
        /** The entry is gone: a sync replaced today's calendar rows. */
        data object Gone : PrepState
        data class Ready(val prep: MeetingPrepResult) : PrepState
    }

    /** The person the meeting is with (12.19, the owner's D5): what the prep's card shows. */
    sealed interface PersonState {
        /** Not a calendar meeting, or a read-only device with nothing linked: no card. */
        data object None : PersonState
        data object Locked : PersonState
        data object Reading : PersonState
        /** Not linked yet: the people this mode shows. */
        data class Choose(val people: List<Person>) : PersonState
        data class Linked(val person: Person) : PersonState
        /** Linked to a work person, outside Work mode. */
        data class Hidden(val workLabel: String) : PersonState
        /** Linked to a note that's gone or doesn't open. */
        data object Missing : PersonState
    }

    private val entryId: String = savedStateHandle.get<String>("entry").orEmpty()

    private val _state = MutableStateFlow<PrepState>(PrepState.Loading)
    val state: StateFlow<PrepState> = _state.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    val person: StateFlow<PersonState> = combine(
        _state, session.unlocked, people.people, preferences.personLinks, modesRepository.visibility
    ) { prep, unlocked, list, links, visibility ->
        val eventId = (prep as? PrepState.Ready)?.prep?.entry?.googleEventId ?: return@combine PersonState.None
        val link = PersonLinks.of(PersonLinks.decode(links), eventId)
        when {
            VaultWriteGate.readOnly && link == null -> PersonState.None
            !unlocked -> PersonState.Locked
            list == null -> PersonState.Reading
            link == null -> PersonState.Choose(People.visible(list, visibility.workContextHidden).filter { it.note != null })
            else -> {
                val linked = list.firstOrNull { it.id == link.person }
                when {
                    linked?.note == null -> PersonState.Missing
                    linked.note.kind == PersonKind.WORK && visibility.workContextHidden -> PersonState.Hidden(visibility.workLabel)
                    else -> PersonState.Linked(linked)
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PersonState.None)

    init {
        viewModelScope.launch {
            _state.value = try {
                repository.prep(entryId)?.let { PrepState.Ready(it) } ?: PrepState.Gone
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PrepState.Gone
            }
        }
        // Reads the People notes once the journal is open (here or elsewhere).
        viewModelScope.launch {
            session.unlocked.collect { open ->
                if (open && people.people.value == null) {
                    try {
                        people.load()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _problem.value = "Your People notes couldn't be read."
                    }
                }
            }
        }
    }

    /** The prep holds the journal's session while it's on screen (12.19): it can't lock mid-meeting. */
    fun hold() = session.hold()

    fun release() = session.release()

    /** The cipher the face or fingerprint prompt opens; null when the password is typed instead. */
    suspend fun unlockCipher(): Cipher? = withContext(Dispatchers.IO) { binding.cipherForUnlock() }

    fun unlockWith(cipher: Cipher) {
        viewModelScope.launch {
            val password = withContext(Dispatchers.Default) { binding.unwrap(cipher) }
            if (password == null) {
                _problem.value = "This phone's copy of the password can't be read: type it once."
                return@launch
            }
            try {
                if (!unlocker.unlock(password, viaBiometric = true)) {
                    _problem.value = "This phone's copy of the password no longer opens the journal: type it once."
                }
            } finally {
                password.fill('\u0000')
            }
        }
    }

    /** False when the password doesn't open the journal. */
    suspend fun unlockWith(password: CharArray): Boolean = try {
        unlocker.unlock(password, viaBiometric = false).also { if (it) _problem.value = null }
    } finally {
        password.fill('\u0000')
    }

    /** Links this meeting's series to [person] (D5); kept on the phone, neutral ids only. */
    fun link(person: Person) {
        val eventId = (_state.value as? PrepState.Ready)?.prep?.entry?.googleEventId ?: return
        val kind = person.note?.kind ?: return
        viewModelScope.launch {
            preferences.updatePersonLinks { PersonLinks.link(it, cloud.wafflecommons.pixelbrainreader.data.people.MeetingSeries.keyOf(eventId), person.id, kind) }
        }
    }

    fun unlink() {
        val eventId = (_state.value as? PrepState.Ready)?.prep?.entry?.googleEventId ?: return
        viewModelScope.launch {
            preferences.updatePersonLinks { PersonLinks.unlink(it, cloud.wafflecommons.pixelbrainreader.data.people.MeetingSeries.keyOf(eventId)) }
        }
    }

    /** Writes the meeting's note with the prep in it, then [open]s it. */
    fun writeMinutes(open: (String) -> Unit) {
        val prep = (_state.value as? PrepState.Ready)?.prep ?: return
        _problem.value = null
        viewModelScope.launch {
            try {
                open(repository.writeMinutes(prep))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _problem.value = "The meeting note couldn't be written."
            }
        }
    }
}

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

@Composable
fun MeetingPrepScreen(onBack: () -> Unit, onOpenNote: (String) -> Unit, viewModel: MeetingPrepViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val problem by viewModel.problem.collectAsStateWithLifecycle()
    val person by viewModel.person.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    var askPassword by rememberSaveable { mutableStateOf(false) }

    // While on screen the prep holds the journal's session, like the journal itself (12.19).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var held = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START && !held) {
                viewModel.hold()
                held = true
            } else if (event == Lifecycle.Event.ON_STOP && held) {
                viewModel.release()
                held = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (held) viewModel.release()
        }
    }

    // A person's notes on screen: no screenshot, also in a debug build (release sets it for the whole app).
    val showsPerson = person is MeetingPrepViewModel.PersonState.Linked || person is MeetingPrepViewModel.PersonState.Choose
    DisposableEffect(showsPerson, activity) {
        val window = activity?.window
        val alreadySecure = window != null && (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        if (showsPerson && window != null && !alreadySecure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (showsPerson && window != null && !alreadySecure) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    fun unlock() {
        val host = activity ?: run { askPassword = true; return }
        scope.launch {
            val cipher = viewModel.unlockCipher() ?: run { askPassword = true; return@launch }
            val prompt = BiometricPrompt(host, ContextCompat.getMainExecutor(host), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    result.cryptoObject?.cipher?.let(viewModel::unlockWith) ?: run { askPassword = true }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) askPassword = true
                }
            })
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Unlock your People notes")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .setNegativeButtonText("Use password")
                    .setConfirmationRequired(false)
                    .build(),
                BiometricPrompt.CryptoObject(cipher)
            )
        }
    }

    if (askPassword) {
        PasswordDialog(
            onUnlock = { typed, done -> scope.launch { done(viewModel.unlockWith(typed)) } },
            onDismiss = { askPassword = false }
        )
    }

    FormScaffold(title = "Meeting prep", onBack = onBack) {
        when (val current = state) {
            MeetingPrepViewModel.PrepState.Loading -> EmptyHint("Reading your notes…")
            MeetingPrepViewModel.PrepState.Gone -> EmptyHint("This meeting is no longer on the timeline.")
            is MeetingPrepViewModel.PrepState.Ready -> {
                val prep = current.prep
                Text(prep.topic.ifEmpty { prep.entry.content }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "${prep.entry.date.format(DateTimeFormatter.ofPattern("EEEE d MMMM"))} · ${prep.entry.time.format(DateTimeFormatter.ofPattern("HH:mm"))}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (prep.workHidden) {
                    Text(
                        "Work notes and the mission log stay hidden outside ${prep.workLabel} mode.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

                PersonCard(
                    state = person,
                    onUnlock = ::unlock,
                    onLink = viewModel::link,
                    onUnlink = viewModel::unlink,
                    onOpen = { id -> context.startActivity(PrivateJournalActivity.peopleIntent(context, id)) },
                    onWrite = { id -> context.startActivity(PrivateJournalActivity.peopleIntent(context, id, prep.entry.date)) }
                )

                SectionCard(title = "Notes about it · ${prep.notes.size}", icon = Icons.Rounded.Description) {
                    if (prep.notes.isEmpty()) EmptyHint("Nothing in your notes about it yet.")
                    prep.notes.forEach { note ->
                        Column(modifier = Modifier.fillMaxWidth().clickable { onOpenNote(note.path) }.padding(vertical = 6.dp)) {
                            Text(note.path.substringAfterLast('/').removeSuffix(".md"), style = MaterialTheme.typography.bodyLarge)
                            val excerpt = note.excerpt.lines().filterNot { it.startsWith("[Note:") }.joinToString(" ").trim()
                            if (excerpt.isNotBlank()) {
                                Text(
                                    excerpt,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                if (!prep.workHidden) {
                    SectionCard(title = "Last decisions about it", icon = Icons.Rounded.Gavel) {
                        if (prep.decisions.isEmpty()) EmptyHint("No decision in the mission log about it.")
                        prep.decisions.forEach { decision ->
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text(decision.text, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    decision.at.toLocalDate().format(DateTimeFormatter.ISO_DATE),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // A meeting with a linked person writes its 1:1 note in their encrypted note (D7).
                if (!VaultWriteGate.readOnly && person !is MeetingPrepViewModel.PersonState.Linked) {
                    Button(onClick = { viewModel.writeMinutes(onOpenNote) }) { Text("Write the meeting note") }
                }
            }
        }
    }
}

/** The person the meeting is with (12.19, D5/D7), behind the journal's unlock. */
@Composable
private fun PersonCard(
    state: MeetingPrepViewModel.PersonState,
    onUnlock: () -> Unit,
    onLink: (Person) -> Unit,
    onUnlink: () -> Unit,
    onOpen: (String) -> Unit,
    onWrite: (String) -> Unit
) {
    if (state is MeetingPrepViewModel.PersonState.None) return
    SectionCard(title = "With", icon = Icons.Rounded.Diversity3) {
        when (state) {
            MeetingPrepViewModel.PersonState.None -> Unit
            MeetingPrepViewModel.PersonState.Locked -> {
                EmptyHint("Your People notes are in the private journal.")
                FilledTonalButton(onClick = onUnlock) { Text("Unlock to see who it's with") }
            }
            MeetingPrepViewModel.PersonState.Reading -> EmptyHint("Reading your People notes…")
            is MeetingPrepViewModel.PersonState.Choose -> {
                if (state.people.isEmpty()) {
                    EmptyHint("No one in your People notes yet: add them in the private journal's People tab.")
                } else if (!VaultWriteGate.readOnly) {
                    EmptyHint("Who is this meeting with? The phone remembers it for the next ones.")
                    state.people.forEach { person ->
                        val note = person.note ?: return@forEach
                        TextButton(onClick = { onLink(person) }) {
                            Text(if (note.role.isBlank()) note.name else "${note.name} · ${note.role}")
                        }
                    }
                }
            }
            is MeetingPrepViewModel.PersonState.Hidden -> EmptyHint("A work person: hidden outside ${state.workLabel} mode.")
            MeetingPrepViewModel.PersonState.Missing -> {
                EmptyHint("The person this meeting was linked to has no note any more.")
                if (!VaultWriteGate.readOnly) TextButton(onClick = onUnlink) { Text("Unlink") }
            }
            is MeetingPrepViewModel.PersonState.Linked -> {
                val note = state.person.note ?: return@SectionCard
                Text(note.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (note.role.isNotBlank()) Text(note.role, style = MaterialTheme.typography.bodyMedium)
                Text(
                    note.lastOneOnOne?.let { "Last 1:1: ${it.format(DAY)}" } ?: "No 1:1 written yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Lines("Open points", note.openPoints.filterNot { it.done }.map { it.text })
                Lines("Your actions", note.myActions.filterNot { it.done }.map { it.text })
                Lines("Their actions", note.theirActions.filterNot { it.done }.map { it.text })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!VaultWriteGate.readOnly) Button(onClick = { onWrite(state.person.id) }) { Text("Write the 1:1 note") }
                    TextButton(onClick = { onOpen(state.person.id) }) { Text("Open their notes") }
                }
                if (!VaultWriteGate.readOnly) TextButton(onClick = onUnlink) { Text("Not with them? Unlink") }
            }
        }
    }
}

@Composable
private fun Lines(title: String, lines: List<String>) {
    if (lines.isEmpty()) return
    Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
    lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
}

/** "Use password": the journal's password typed for the prep. */
@Composable
private fun PasswordDialog(onUnlock: (CharArray, (Boolean) -> Unit) -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("The journal's password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it; wrong = false },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                if (wrong) Text("Wrong password", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(enabled = typed.isNotEmpty(), onClick = {
                onUnlock(typed.toCharArray()) { ok -> if (ok) onDismiss() else wrong = true }
            }) { Text("Unlock") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
