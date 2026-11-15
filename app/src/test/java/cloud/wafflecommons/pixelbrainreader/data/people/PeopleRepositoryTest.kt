package cloud.wafflecommons.pixelbrainreader.data.people

import android.content.Context
import cloud.wafflecommons.pixelbrainreader.data.local.security.CryptoManager
import cloud.wafflecommons.pixelbrainreader.data.privatevault.PrivateVaultLockedException
import cloud.wafflecommons.pixelbrainreader.data.privatevault.PrivateVaultSession
import cloud.wafflecommons.pixelbrainreader.data.repository.PrivateNoteRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

/** The People notes (12.19, P1): encrypted, neutral file names, read while the journal is unlocked. */
class PeopleRepositoryTest {

    @get:Rule val folder = TemporaryFolder()

    private val crypto = CryptoManager()
    private val preferences: UserPreferencesRepository = mockk { every { privateAutoLockMinutes } returns flowOf(5) }
    private val scope = TestScope()
    private val session = PrivateVaultSession(crypto, preferences, scope)
    private val day = LocalDate.of(2026, 11, 15)
    private val clock: AppClock = mockk { every { today } returns MutableStateFlow(day) }
    private val password = "the journal's password".toCharArray()

    private fun repository(): PeopleRepository {
        val context = mockk<Context> { every { filesDir } returns folder.root }
        val notes = PrivateNoteRepository(context, crypto, mockk(relaxed = true), session)
        return PeopleRepository(context, crypto, session, notes, clock, scope)
    }

    private fun peopleFolder() = File(folder.root, "vault/99_Private/People")

    @Test
    fun `a person is written encrypted under a neutral name, and reads back`() = runTest {
        session.open(password)
        val repository = repository()

        val id = repository.create("Alex Martin", PersonKind.WORK, "Tech lead")

        val files = peopleFolder().list()!!.toList()
        assertEquals(listOf("person-$id.md.enc"), files)
        val bytes = File(peopleFolder(), files.single()).readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("Alex"))
        val people = repository.load()
        assertEquals(listOf("Alex Martin"), people.map { it.note?.name })
        assertEquals("Tech lead", people.single().note!!.role)
    }

    @Test
    fun `a change starts from the note on disk`() = runTest {
        session.open(password)
        val repository = repository()
        val id = repository.create("Alex Martin", PersonKind.WORK, "Tech lead")
        repository.load()
        // Another write lands on disk (an earlier save), unknown to the list in memory.
        val file = File(peopleFolder(), PeopleFiles.fileName(id))
        val onDisk = PersonNoteFormat.parse(crypto.decrypt(file.readBytes(), password), day)
            .copy(context = "Written elsewhere")
        file.writeBytes(crypto.encrypt(PersonNoteFormat.render(onDisk), password))

        val written = repository.update(id) { it.copy(openPoints = it.openPoints + CheckItem("Budget", day)) }

        assertEquals("Written elsewhere", written.context)
        assertEquals(listOf("Budget"), written.openPoints.map { it.text })
        assertEquals(written, repository.cached(id)?.note)
    }

    @Test
    fun `a note that doesn't open is listed, never dropped`() = runTest {
        session.open(password)
        val repository = repository()
        repository.create("Alex Martin", PersonKind.WORK, "")
        File(peopleFolder(), "person-0badc0de.md.enc").writeBytes(crypto.encrypt("# Someone", "another password".toCharArray()))

        val people = repository.load()

        assertEquals(2, people.size)
        assertEquals("0badc0de", people.last().id)
        assertNull(people.last().note)
    }

    @Test
    fun `nothing is read while the journal is locked, and the list leaves memory at the lock`() = runTest {
        val repository = repository()
        try {
            repository.load()
            fail("a locked journal handed People notes out")
        } catch (e: PrivateVaultLockedException) {
            // expected
        }

        session.open(password)
        repository.create("Alex Martin", PersonKind.PERSONAL, "")
        repository.load()
        assertTrue(repository.people.value!!.isNotEmpty())

        session.lock()
        scope.testScheduler.runCurrent()
        assertNull(repository.people.value)
    }

    @Test
    fun `a deleted person leaves the folder and the list`() = runTest {
        session.open(password)
        val repository = repository()
        val id = repository.create("Alex Martin", PersonKind.WORK, "")
        repository.load()

        repository.delete(id)

        assertTrue(peopleFolder().list()!!.isEmpty())
        assertTrue(repository.people.value!!.isEmpty())
    }

    @Test
    fun `new ids never reuse a file's`() {
        val taken = setOf(PeopleFiles.fileName("0000002a"))
        val random = object : java.util.Random() {
            private var calls = 0
            override fun nextInt(): Int = if (calls++ == 0) 42 else 43
        }

        assertEquals("0000002b", PeopleFiles.newId(taken, random))
        assertTrue(PeopleFiles.isPersonFile("person-0000002b.md.enc"))
        assertFalse(PeopleFiles.isPersonFile("person-Alex.md.enc"))
    }
}
