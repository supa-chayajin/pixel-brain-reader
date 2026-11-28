package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.device.ReadOnlyDeviceException
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * The vault's JSON list files (13.4): a file that no longer parses is moved aside before anything is
 * written over it, and Repair's write-back never replaces a file's items with an app that holds none.
 */
class VaultListFilesTest {

    private data class Row(val id: String)

    private val gson = Gson()
    private val parse: (String) -> List<*>? = { gson.fromJson<List<Row>>(it, object : TypeToken<List<Row>>() {}.type) }
    private val path = "10_Journal/data/home/chores.json"

    private var onDisk: String? = null
    private val saved = mutableListOf<String>()
    private val fileRepository: FileRepository = mockk {
        coEvery { readFile(path) } answers { onDisk }
        coEvery { saveFileLocally(path, any()) } answers { saved += secondArg<String>() }
        every { moveAsideCorrupt(path) } answers { onDisk = null; File("chores.json.corrupt-1") }
    }

    @After
    fun tearDown() {
        VaultWriteGate.readOnly = false
    }

    @Test
    fun `a file is absent, holds items, or doesn't parse, as its import reads it`() {
        assertEquals(ListOnDisk.Absent, VaultListFiles.classify(null, parse))
        assertEquals(ListOnDisk.Absent, VaultListFiles.classify("  \n", parse))
        assertEquals(ListOnDisk.Items(0), VaultListFiles.classify("[]", parse))
        assertEquals(ListOnDisk.Items(0), VaultListFiles.classify("null", parse))
        assertEquals(ListOnDisk.Items(2), VaultListFiles.classify("""[{"id":"a"},{"id":"b"}]""", parse))
        assertEquals(ListOnDisk.Unreadable, VaultListFiles.classify("[{\"id\":", parse))
        // Valid JSON of another shape is no list the import can read either.
        assertEquals(ListOnDisk.Unreadable, VaultListFiles.classify("""{"id":"a"}""", parse))
    }

    @Test
    fun `an app that holds none never writes over a file that holds items or may`() {
        assertFalse(VaultListFiles.mayWriteBack(ListOnDisk.Items(3), appItems = 0))
        assertFalse(VaultListFiles.mayWriteBack(ListOnDisk.Unreadable, appItems = 0))
        assertTrue(VaultListFiles.mayWriteBack(ListOnDisk.Absent, appItems = 0))
        assertTrue(VaultListFiles.mayWriteBack(ListOnDisk.Items(0), appItems = 0))
        assertTrue(VaultListFiles.mayWriteBack(ListOnDisk.Items(3), appItems = 1))
        assertTrue(VaultListFiles.mayWriteBack(ListOnDisk.Unreadable, appItems = 2))
    }

    @Test
    fun `a save moves an unreadable file aside before it writes`() = runBlocking {
        onDisk = "{ not json"

        VaultListFiles.replace(fileRepository, path, """[{"id":"a"}]""", parse)

        coVerifyOrder {
            fileRepository.moveAsideCorrupt(path)
            fileRepository.saveFileLocally(path, """[{"id":"a"}]""")
        }
    }

    @Test
    fun `a save over a readable file moves nothing`() = runBlocking {
        onDisk = """[{"id":"a"}]"""

        VaultListFiles.replace(fileRepository, path, "[]", parse)

        verify(exactly = 0) { fileRepository.moveAsideCorrupt(any()) }
        assertEquals(listOf("[]"), saved)
    }

    @Test
    fun `an unreadable file that can't be kept aside is never overwritten`() = runBlocking {
        onDisk = "{ not json"
        every { fileRepository.moveAsideCorrupt(path) } returns null

        try {
            VaultListFiles.replace(fileRepository, path, "[]", parse)
            fail("The write should have stopped")
        } catch (e: IOException) {
            // Reported to the caller.
        }
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `the write-back leaves a file whose items the app holds none of, and says so`() = runBlocking {
        onDisk = """[{"id":"a"},{"id":"b"}]"""

        assertFalse(VaultListFiles.writeBack(fileRepository, path, appItems = 0, content = "[]", parse = parse))

        assertTrue(saved.isEmpty())
        verify(exactly = 0) { fileRepository.moveAsideCorrupt(any()) }
        assertEquals("""[{"id":"a"},{"id":"b"}]""", onDisk)
    }

    @Test
    fun `the write-back replaces a file the app holds items for, an unreadable one kept aside first`() = runBlocking {
        onDisk = "<<<<<<< conflict"

        assertTrue(VaultListFiles.writeBack(fileRepository, path, appItems = 1, content = """[{"id":"a"}]""", parse = parse))

        coVerifyOrder {
            fileRepository.moveAsideCorrupt(path)
            fileRepository.saveFileLocally(path, """[{"id":"a"}]""")
        }
    }

    @Test
    fun `a read-only device moves nothing and refuses as the write would`() = runBlocking {
        VaultWriteGate.readOnly = true
        onDisk = "{ not json"

        try {
            VaultListFiles.replace(fileRepository, path, "[]", parse)
            fail("A read-only device should refuse")
        } catch (e: ReadOnlyDeviceException) {
            // The refusal every vault writer gives.
        }
        verify(exactly = 0) { fileRepository.moveAsideCorrupt(any()) }
        coVerify(exactly = 0) { fileRepository.saveFileLocally(any(), any()) }
    }
}
