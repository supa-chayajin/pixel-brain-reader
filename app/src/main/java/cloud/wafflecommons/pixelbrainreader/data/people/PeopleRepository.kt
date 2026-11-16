package cloud.wafflecommons.pixelbrainreader.data.people

import android.content.Context
import cloud.wafflecommons.pixelbrainreader.data.local.security.CryptoManager
import cloud.wafflecommons.pixelbrainreader.data.privatevault.PrivateVaultKey
import cloud.wafflecommons.pixelbrainreader.data.privatevault.PrivateVaultLockedException
import cloud.wafflecommons.pixelbrainreader.data.privatevault.PrivateVaultSession
import cloud.wafflecommons.pixelbrainreader.data.repository.PrivateNoteRepository
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import cloud.wafflecommons.pixelbrainreader.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/** A person of the People tab: the note's neutral id, and the note, or null when it doesn't open with the password. */
data class Person(val id: String, val note: PersonNote?)

/** The People notes' file names (12.19, owner's D3): `person-<8 hex>.md.enc`, nothing that says who. */
object PeopleFiles {
    private val NAME = Regex("""^person-([0-9a-f]{8})\.md\.enc$""")

    fun isPersonFile(name: String): Boolean = NAME.matches(name)

    fun idOf(fileName: String): String? = NAME.find(fileName)?.groupValues?.get(1)

    fun fileName(id: String): String = "person-$id.md.enc"

    /** The note's path inside `99_Private/`. */
    fun relativePath(id: String): String = "People/" + fileName(id)

    /** A new id no file in [existing] uses. */
    fun newId(existing: Set<String>, random: java.util.Random = SecureRandom()): String {
        while (true) {
            val id = "%08x".format(random.nextInt())
            if (fileName(id) !in existing) return id
        }
    }
}

/**
 * The People notes (12.19, P1): one encrypted file per person in `99_Private/People/`, read only
 * while the private journal is unlocked (settled decision #21). Reading decrypts every note in
 * parallel with a copy of the session's password; the list stays in memory until the journal locks.
 * Each change reads the file from disk, changes it and writes it back through
 * [PrivateNoteRepository] (atomic, under the session's write lock), so no write is based on a stale
 * copy (settled decision #8). Nothing here runs in the background.
 */
@Singleton
class PeopleRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val crypto: CryptoManager,
    private val session: PrivateVaultSession,
    private val privateNotes: PrivateNoteRepository,
    private val appClock: AppClock,
    @ApplicationScope appScope: CoroutineScope
) {
    private val folder: File get() = File(context.filesDir, "vault/" + VaultSchema.PEOPLE_FOLDER)

    private val _people = MutableStateFlow<List<Person>?>(null)

    /** The people, decrypted; null while the journal is locked or before the first [load]. */
    val people: StateFlow<List<Person>?> = _people.asStateFlow()

    private val loading = Mutex()

    init {
        // The decrypted notes leave memory with the password.
        appScope.launch { session.unlocked.collect { open -> if (!open) _people.value = null } }
    }

    /** Reads every People note. Throws [PrivateVaultLockedException] while the journal is locked. */
    suspend fun load(): List<Person> = loading.withLock {
        val key = session.key() ?: throw PrivateVaultLockedException()
        try {
            val files = withContext(Dispatchers.IO) {
                folder.listFiles { file -> file.isFile && PeopleFiles.isPersonFile(file.name) }?.toList().orEmpty()
            }
            val people = coroutineScope {
                files.map { file -> async { Person(PeopleFiles.idOf(file.name)!!, read(file, key)) } }.awaitAll()
            }.sortedWith(compareBy<Person>({ it.note == null }, { it.note?.name?.lowercase().orEmpty() }))
            if (session.isOpen()) _people.value = people
            people
        } finally {
            key.wipe()
        }
    }

    /** A person from the list in memory, or null. */
    fun cached(id: String): Person? = _people.value?.firstOrNull { it.id == id }

    /** Adds a person, written at once; returns their id. */
    suspend fun create(name: String, kind: PersonKind, role: String): String {
        val existing = withContext(Dispatchers.IO) { folder.list()?.toSet().orEmpty() }
        val id = PeopleFiles.newId(existing)
        val note = PersonNoteFormat.create(name, kind, role, appClock.today.value)
        write(id) { note }
        return id
    }

    /** Changes a person's note from what is on disk; returns the note as written. */
    suspend fun update(id: String, change: (PersonNote) -> PersonNote): PersonNote =
        write(id) { current -> change(current ?: throw IOException("This person's note is gone.")) }

    suspend fun delete(id: String) {
        privateNotes.deleteNote(File(folder, PeopleFiles.fileName(id)))
        _people.update { list -> list?.filterNot { it.id == id } }
    }

    private suspend fun write(id: String, change: (PersonNote?) -> PersonNote): PersonNote {
        val key = session.key() ?: throw PrivateVaultLockedException()
        try {
            var written: PersonNote? = null
            privateNotes.rewriteEncrypted(PeopleFiles.relativePath(id), key) { text ->
                val today = appClock.today.value
                val current = text?.let { PersonNoteFormat.parse(it, today) }
                val next = change(current).copy(updated = today)
                written = next
                PersonNoteFormat.render(next)
            }
            val note = written ?: throw IOException("The note wasn't written.")
            _people.update { list ->
                list?.let { people ->
                    val others = people.filterNot { it.id == id }
                    (others + Person(id, note)).sortedWith(compareBy<Person>({ it.note == null }, { it.note?.name?.lowercase().orEmpty() }))
                }
            }
            return note
        } finally {
            key.wipe()
        }
    }

    private suspend fun read(file: File, key: PrivateVaultKey): PersonNote? = try {
        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        PersonNoteFormat.parse(crypto.decrypt(bytes, key.password), appClock.today.value)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null // a note that doesn't open with this password is listed, never dropped
    }
}
