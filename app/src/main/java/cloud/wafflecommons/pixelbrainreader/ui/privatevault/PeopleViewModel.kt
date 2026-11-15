package cloud.wafflecommons.pixelbrainreader.ui.privatevault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.ai.LocalAiManager
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.people.CheckItem
import cloud.wafflecommons.pixelbrainreader.data.people.Feedback
import cloud.wafflecommons.pixelbrainreader.data.people.OneOnOne
import cloud.wafflecommons.pixelbrainreader.data.people.OneOnOneCoach
import cloud.wafflecommons.pixelbrainreader.data.people.People
import cloud.wafflecommons.pixelbrainreader.data.people.PeopleRepository
import cloud.wafflecommons.pixelbrainreader.data.people.Person
import cloud.wafflecommons.pixelbrainreader.data.people.PersonKind
import cloud.wafflecommons.pixelbrainreader.data.people.PersonNote
import cloud.wafflecommons.pixelbrainreader.data.privatevault.PrivateVaultSession
import cloud.wafflecommons.pixelbrainreader.data.repository.TaskRepository
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import cloud.wafflecommons.pixelbrainreader.data.work.LogEntry
import cloud.wafflecommons.pixelbrainreader.data.work.LogKind
import cloud.wafflecommons.pixelbrainreader.data.work.MissionLogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject

/**
 * The private journal's People tab (12.19, P1): the list the mode shows (D1: work people in Work
 * mode only), a person's page and its edits, and the extras (D9). Reads only while the journal is
 * unlocked; every edit goes to the encrypted note at once.
 */
@HiltViewModel
class PeopleViewModel @Inject constructor(
    private val repository: PeopleRepository,
    private val session: PrivateVaultSession,
    modesRepository: ModesRepository,
    private val taskRepository: TaskRepository,
    private val missionLogRepository: MissionLogRepository,
    private val localAiManager: LocalAiManager,
    private val appClock: AppClock,
    private val preferences: cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
) : ViewModel() {

    data class ListState(
        val loaded: Boolean = false,
        val people: List<Person> = emptyList(),
        /** Work people the mode hides (D1). */
        val hiddenWork: Int = 0,
        val workLabel: String = "Work"
    )

    val list: StateFlow<ListState> = combine(repository.people, modesRepository.visibility) { people, visibility ->
        if (people == null) {
            ListState(workLabel = visibility.workLabel)
        } else {
            val shown = People.visible(people, workHidden = visibility.workContextHidden)
            ListState(loaded = true, people = shown, hiddenWork = people.size - shown.size, workLabel = visibility.workLabel)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListState())

    private val _openId = MutableStateFlow<String?>(null)

    /** The person whose page is open, or null for the list. */
    val openId: StateFlow<String?> = _openId.asStateFlow()

    /**
     * The open person as the repository holds them now: the page follows every edit. Not the mode's
     * list, which leaves a work person out outside Work mode (a page opened right after adding one).
     */
    val openPerson: StateFlow<Person?> = combine(_openId, repository.people) { id, people ->
        id?.let { wanted -> people?.firstOrNull { it.id == wanted } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    /** Ask Coach's sheet (D9): null when closed. */
    data class CoachState(val loading: Boolean = true, val answer: String? = null, val error: String? = null)

    private val _coach = MutableStateFlow<CoachState?>(null)
    val coach: StateFlow<CoachState?> = _coach.asStateFlow()

    /** A 1:1 entry being written: its person and day. */
    data class EntryDraft(val personId: String, val date: LocalDate, val text: String)

    private val _entry = MutableStateFlow<EntryDraft?>(null)
    val entry: StateFlow<EntryDraft?> = _entry.asStateFlow()

    val readOnly: Boolean get() = VaultWriteGate.readOnly

    init {
        viewModelScope.launch {
            session.unlocked.collect { open ->
                if (open) reload() else {
                    _openId.value = null
                    _entry.value = null
                    _coach.value = null
                }
            }
        }
    }

    /**
     * From the toolkit or a meeting's prep (12.19): once the journal is unlocked, the person [id]'s
     * page, or their 1:1 entry of [date] when given.
     */
    fun openWhenUnlocked(id: String, date: LocalDate?) {
        viewModelScope.launch {
            session.unlocked.first { it }
            if (date != null) {
                startEntry(id, date)
            } else {
                if (repository.people.value == null) attempt("Your People notes couldn't be read.") { repository.load() }
                _openId.value = id
            }
        }
    }

    fun reload() {
        viewModelScope.launch { attempt("Your People notes couldn't be read.") { repository.load() } }
    }

    fun open(id: String?) {
        _openId.value = id
        _problem.value = null
    }

    fun person(id: String): Person? = repository.people.value?.firstOrNull { it.id == id }

    fun add(name: String, kind: PersonKind, role: String, then: (String) -> Unit = {}) {
        if (name.isBlank()) return
        viewModelScope.launch {
            attempt("The person couldn't be added.") { repository.create(name, kind, role) }?.let { id ->
                _openId.value = id
                then(id)
            }
        }
    }

    fun edit(id: String, name: String, kind: PersonKind, role: String, context: String, personal: String) = change(id) {
        it.copy(name = name.trim().ifEmpty { it.name }, kind = kind, role = role.trim(), context = context.trim(), personal = personal.trim())
    }

    fun delete(id: String) {
        viewModelScope.launch {
            attempt("The note couldn't be deleted.") { repository.delete(id) }
            // The meetings linked to them aren't any more (12.19, D5).
            preferences.updatePersonLinks { cloud.wafflecommons.pixelbrainreader.data.people.PersonLinks.forget(it, id) }
            if (_openId.value == id) _openId.value = null
        }
    }

    // --- Open points and actions ---

    enum class ListKind { OPEN_POINTS, MY_ACTIONS, THEIR_ACTIONS }

    fun addItem(id: String, list: ListKind, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        change(id) { note -> note.withList(list) { it + CheckItem(clean, added = appClock.today.value) } }
    }

    fun toggleItem(id: String, list: ListKind, item: CheckItem) = change(id) { note ->
        note.withList(list) { items ->
            val index = items.indexOfFirst { it.text == item.text && it.added == item.added }
            if (index < 0) items else items.toMutableList().apply {
                val current = this[index]
                this[index] = if (current.done) current.copy(done = false, doneOn = null) else current.copy(done = true, doneOn = appClock.today.value)
            }
        }
    }

    fun removeItem(id: String, list: ListKind, item: CheckItem) = change(id) { note ->
        note.withList(list) { items -> items.filterNot { it.text == item.text && it.added == item.added } }
    }

    private fun PersonNote.withList(list: ListKind, transform: (List<CheckItem>) -> List<CheckItem>): PersonNote = when (list) {
        ListKind.OPEN_POINTS -> copy(openPoints = transform(openPoints))
        ListKind.MY_ACTIONS -> copy(myActions = transform(myActions))
        ListKind.THEIR_ACTIONS -> copy(theirActions = transform(theirActions))
    }

    // --- The 1:1 log ---

    /**
     * Opens the entry of [date] for [id]: the existing one, or a new one. The list is read first when
     * it isn't yet (the prep can open the journal on a person right after an unlock): an entry
     * started empty over an existing one would replace it on save.
     */
    fun startEntry(id: String, date: LocalDate = appClock.today.value) {
        viewModelScope.launch {
            val people = repository.people.value ?: attempt("Your People notes couldn't be read.") { repository.load() } ?: return@launch
            val note = people.firstOrNull { it.id == id }?.note ?: return@launch
            _openId.value = id
            _entry.value = EntryDraft(id, date, note.oneOnOnes.firstOrNull { it.date == date }?.text.orEmpty())
        }
    }

    fun editEntry(text: String) {
        _entry.value = _entry.value?.copy(text = text)
    }

    fun saveEntry() {
        val draft = _entry.value ?: return
        _entry.value = null
        change(draft.personId) { note ->
            val others = note.oneOnOnes.filterNot { it.date == draft.date }
            note.copy(oneOnOnes = others + OneOnOne(draft.date, draft.text.trim()))
        }
    }

    fun closeEntry() {
        _entry.value = null
    }

    fun deleteEntry(id: String, date: LocalDate) = change(id) { note -> note.copy(oneOnOnes = note.oneOnOnes.filterNot { it.date == date }) }

    // --- Feedback ---

    fun addFeedback(id: String, given: Boolean, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        change(id) { note -> note.copy(feedback = note.feedback + Feedback(appClock.today.value, given, clean)) }
    }

    fun removeFeedback(id: String, feedback: Feedback) = change(id) { note -> note.copy(feedback = note.feedback - feedback) }

    // --- Extras (D9) ---

    /** A work person's decision to the mission log, decided by [decidedBy] (their role by default), never their name. */
    fun logDecision(text: String, decidedBy: String, done: (Boolean) -> Unit) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            val ok = attempt("The decision couldn't be logged.") {
                missionLogRepository.add(
                    LogEntry(at = LocalDateTime.now().withSecond(0).withNano(0), kind = LogKind.DECISION, text = clean, decider = decidedBy.trim().ifEmpty { null })
                )
            } != null
            done(ok)
        }
    }

    /** One of your actions as a task for today, in the words you chose. */
    fun actionToTask(text: String, done: (Boolean) -> Unit) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            done(attempt("The task couldn't be added.") { taskRepository.addTask(clean, appClock.today.value) } != null)
        }
    }

    /** Ask Coach to prepare the next 1:1 (D9): the role, open points and actions, never the name; nothing saved. */
    fun askCoach(id: String) {
        val note = person(id)?.note ?: return
        _coach.value = CoachState()
        viewModelScope.launch {
            val result = localAiManager.generateResponse(OneOnOneCoach.prompt(note, appClock.today.value))
            _coach.value = result.fold(
                onSuccess = { CoachState(loading = false, answer = it.trim()) },
                onFailure = { CoachState(loading = false, error = "Gemini Nano couldn't answer: ${it.localizedMessage ?: "unavailable"}") }
            )
        }
    }

    fun closeCoach() {
        _coach.value = null
    }

    private fun change(id: String, transform: (PersonNote) -> PersonNote) {
        viewModelScope.launch { attempt("The note couldn't be saved.") { repository.update(id, transform) } }
    }

    private suspend fun <T> attempt(failure: String, block: suspend () -> T): T? = try {
        block().also { _problem.value = null }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        _problem.value = failure
        null
    }
}
