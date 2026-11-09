package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the mood-tag file contract (`10_Journal/data/config/mood_tags.json`): icons are written NEXT
 * TO the unchanged tag list, so an app version from before icons still reads the list — and with no
 * icon picked the file is byte-for-byte the old shape.
 */
class MoodTagRepositoryTest {

    private var stored: String? = null
    private val fileRepository: FileRepository = mockk {
        coEvery { readFile(MoodTagRepository.TAGS_PATH) } answers { stored }
        coEvery { saveFileLocally(MoodTagRepository.TAGS_PATH, any()) } answers { stored = secondArg() }
    }
    private val pushScheduler: VaultPushScheduler = mockk(relaxed = true)
    private val repository = MoodTagRepository(fileRepository, pushScheduler)

    /** The reader of an app version from before icons: the same lenient decoder, no icons field. */
    @Serializable
    private data class PreIconsDto(val tags: List<String> = emptyList())
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any(), any<String>(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        VaultWriteGate.readOnly = false
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `a list saved before icons existed reads with default icons`() = runTest {
        stored = """{ "tags": [ "Gaming", "Coding" ] }"""

        repository.ensureLoaded()

        assertEquals(listOf(MoodTag("Gaming"), MoodTag("Coding")), repository.tags.value)
    }

    @Test
    fun `a picked icon is written next to the unchanged tag list`() = runTest {
        stored = """{ "tags": [ "Gaming", "Coding" ] }"""

        repository.setIcon("Gaming", "piano")

        assertEquals(listOf(MoodTag("Gaming", "piano"), MoodTag("Coding")), repository.tags.value)
        val file = json.parseToJsonElement(stored!!).jsonObject
        assertEquals(setOf("tags", "icons"), file.keys)
        assertEquals(mapOf("Gaming" to "piano"), json.decodeFromString<Map<String, String>>(file.getValue("icons").toString()))
        // An older version still reads the same list (it ignores the icons).
        assertEquals(listOf("Gaming", "Coding"), json.decodeFromString<PreIconsDto>(stored!!).tags)
        verify { pushScheduler.requestPush() }
    }

    @Test
    fun `without any icon the file keeps the old shape`() = runTest {
        stored = """{ "tags": [ "Gaming" ] }"""

        repository.addTag("Reading")

        assertEquals(setOf("tags"), json.parseToJsonElement(stored!!).jsonObject.keys)
        assertEquals(listOf("Gaming", "Reading"), json.decodeFromString<PreIconsDto>(stored!!).tags)
    }

    @Test
    fun `a new tag can come with its icon`() = runTest {
        stored = """{ "tags": [ "Gaming" ] }"""

        repository.addTag("  Reading ", "menu_book")
        repository.addTag("reading", "piano") // same tag, other case: ignored

        assertEquals(listOf(MoodTag("Gaming"), MoodTag("Reading", "menu_book")), repository.tags.value)
    }

    @Test
    fun `an icon moves with its tag and leaves with it`() = runTest {
        stored = """{ "tags": [ "Gaming", "Coding", "Solo" ], "icons": { "Gaming": "piano", "Solo": "pets" } }"""

        repository.moveTag(0, 2)
        assertEquals(
            listOf(MoodTag("Coding"), MoodTag("Solo", "pets"), MoodTag("Gaming", "piano")),
            repository.tags.value
        )
        // The icons follow the tag order in the file too.
        assertTrue(stored!!.indexOf("\"Solo\": \"pets\"") < stored!!.indexOf("\"Gaming\": \"piano\""))

        repository.removeTag("Gaming")
        assertEquals(listOf(MoodTag("Coding"), MoodTag("Solo", "pets")), repository.tags.value)
        assertFalse(stored!!.contains("piano"))
    }

    @Test
    fun `null brings the default icon back`() = runTest {
        stored = """{ "tags": [ "Gaming" ], "icons": { "Gaming": "piano" } }"""

        repository.setIcon("Gaming", null)

        assertEquals(listOf(MoodTag("Gaming")), repository.tags.value)
        assertEquals(setOf("tags"), json.parseToJsonElement(stored!!).jsonObject.keys)
    }

    @Test
    fun `an icon this version doesn't know survives an edit`() = runTest {
        stored = """{ "tags": [ "Gaming" ], "icons": { "Gaming": "an_icon_from_a_newer_version" } }"""

        repository.addTag("Coding")

        assertEquals("an_icon_from_a_newer_version", repository.tags.value.first().icon)
        assertTrue(stored!!.contains("an_icon_from_a_newer_version"))
    }

    @Test
    fun `picking the icon a tag already has writes nothing`() = runTest {
        stored = """{ "tags": [ "Gaming" ], "icons": { "Gaming": "piano" } }"""

        repository.setIcon("Gaming", "piano")

        coVerify(exactly = 0) { fileRepository.saveFileLocally(any(), any()) }
        verify(exactly = 0) { pushScheduler.requestPush() }
    }

    @Test
    fun `a read-only device changes nothing, not even the list on screen`() = runTest {
        stored = """{ "tags": [ "Gaming" ] }"""
        repository.ensureLoaded()
        VaultWriteGate.readOnly = true

        repository.addTag("Reading")
        repository.setIcon("Gaming", "piano")
        repository.moveTag(0, 0)
        repository.removeTag("Gaming")

        assertEquals(listOf(MoodTag("Gaming")), repository.tags.value)
        coVerify(exactly = 0) { fileRepository.saveFileLocally(any(), any()) }
        verify(exactly = 0) { pushScheduler.requestPush() }
    }

    @Test
    fun `no file yet offers the seed tags and writes nothing`() = runTest {
        stored = null

        repository.ensureLoaded()

        assertEquals(MoodTagRepository.DEFAULT_TAGS, repository.tags.value.map { it.name })
        assertTrue(repository.tags.value.all { it.icon == null })
        assertNull(stored)
    }
}
