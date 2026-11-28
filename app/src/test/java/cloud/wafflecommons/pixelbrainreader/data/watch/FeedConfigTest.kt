package cloud.wafflecommons.pixelbrainreader.data.watch

import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode.OFF
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode.PERSONAL
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode.WORK
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * The feeds live in the vault's `feeds.json` (D5): disk first, an unreadable file kept aside, no
 * write on a read-only device. 13.4 (F4): each feed picks its modes, and an older app still keeps
 * the Work rule.
 */
class FeedConfigTest {

    private val dev = Feed("https://dev.example/feed", "Dev", work = true)
    private val local = Feed("https://news.example/rss")
    private val allModes = setOf(WORK, PERSONAL, OFF)

    @Test
    fun `a feed is added once, removed, and marked Work`() {
        val config = FeedConfig().add(dev).add(local).add(Feed("HTTPS://DEV.EXAMPLE/FEED", "Twice"))
        assertEquals(listOf(dev, local), config.feeds)
        assertEquals(listOf(local), config.remove("https://dev.example/feed").feeds)
        assertEquals(true, config.withModes(local.url, setOf(WORK)).feeds.last().work)
    }

    @Test
    fun `a Work feed hides while work is out of the picture`() {
        val config = FeedConfig(listOf(dev, local))
        assertEquals(listOf(local), config.visible(Visibility(mode = PERSONAL)))
        assertEquals(listOf(local), config.visible(Visibility(mode = OFF)))
        assertEquals(listOf(dev, local), config.visible(Visibility(mode = WORK)))
        assertEquals(listOf(local), config.visible(Visibility(mode = WORK, screenShareSafe = true)))
    }

    @Test
    fun `a feed without a name shows its host`() {
        assertEquals("news.example", local.label)
        assertEquals("Dev", dev.label)
    }

    @Test
    fun `a feed without modes follows its work flag`() {
        assertEquals(setOf(WORK), dev.shownIn)
        assertTrue(dev.workOnly)
        assertFalse(dev.showsIn(PERSONAL))
        assertEquals(allModes, local.shownIn)
        assertFalse(local.workOnly)
        assertTrue(local.showsIn(OFF))
    }

    @Test
    fun `a feed's modes win over its work flag, unknown keys are kept and ignored`() {
        val garden = Feed("https://garden.example/rss", "Garden Notes", work = true, modes = listOf("personal", "off"))
        assertEquals(setOf(PERSONAL, OFF), garden.shownIn)
        assertFalse(garden.workOnly)
        // Case and spaces aside, a key this version knows is its mode; one it doesn't know is skipped.
        val later = Feed("https://dev.example/feed", modes = listOf(" Work", "later_mode"))
        assertEquals(setOf(WORK), later.shownIn)
        assertTrue(later.workOnly)
        // Nothing this version knows: the work flag decides, as before 13.4.
        assertEquals(setOf(WORK), Feed("https://dev.example/feed", work = true, modes = listOf("later_mode")).shownIn)
        assertEquals(allModes, Feed("https://news.example/rss", modes = listOf("later_mode")).shownIn)
        assertEquals(allModes, Feed("https://news.example/rss", modes = emptyList()).shownIn)
        // A change keeps the key it doesn't know, after the ones it writes.
        assertEquals(listOf("personal", "later_mode"), FeedModes.withModes(later, setOf(PERSONAL)).modes)
        assertEquals(PERSONAL, FeedModes.modeOf("PERSONAL"))
        assertNull(FeedModes.modeOf("later_mode"))
        // The keys are persisted: never renamed.
        assertEquals(listOf("work", "personal", "off"), ContextMode.entries.map(FeedModes::keyOf))
        assertEquals(listOf("work", "personal", "off"), FeedModes.ALL_KEYS)
    }

    @Test
    fun `withModes writes the modes in order and work only for Work alone`() {
        val workAndOff = FeedModes.withModes(local, setOf(OFF, WORK))
        assertEquals(listOf("work", "off"), workAndOff.modes)
        assertFalse(workAndOff.work)
        val workOnly = FeedModes.withModes(local, setOf(WORK))
        assertEquals(listOf("work"), workOnly.modes)
        assertTrue(workOnly.work)
        // A Work feed shared with another mode stops being Work-only for the older apps too.
        val shared = FeedModes.withModes(dev, setOf(PERSONAL, WORK))
        assertEquals(listOf("work", "personal"), shared.modes)
        assertFalse(shared.work)
        // Only the feed with that address changes, case aside.
        val config = FeedConfig(listOf(dev, local)).withModes("HTTPS://NEWS.EXAMPLE/RSS", setOf(OFF))
        assertEquals(dev, config.feeds[0])
        assertEquals(setOf(OFF), config.feeds[1].shownIn)
        // Where it already shows: it keeps its shape.
        assertSame(local, FeedModes.withModes(local, allModes))
        assertSame(dev, FeedModes.withModes(dev, setOf(WORK)))
    }

    @Test
    fun `a feed always keeps one mode`() {
        assertSame(local, FeedModes.withModes(local, emptySet()))
        val config = FeedConfig(listOf(dev, local))
        assertSame(config, config.withModes(dev.url, emptySet()))
    }

    @Test
    fun `a new feed shows in every mode`() {
        val added = Feed("https://cdn.example.org/atom", modes = FeedModes.ALL_KEYS)
        assertEquals(allModes, added.shownIn)
        assertFalse(added.workOnly)
        val config = FeedConfig().add(added)
        ContextMode.entries.forEach { mode -> assertEquals(listOf(added), config.visible(Visibility(mode = mode))) }
        assertEquals(listOf(added), config.visible(Visibility(mode = WORK, screenShareSafe = true)))
    }

    @Test
    fun `screen-share safe hides a Work-only feed, not one shared with another mode`() {
        val workOnly = Feed("https://dev.example/feed", "Dev", work = true, modes = listOf("work"))
        val shared = Feed("https://cdn.example.org/atom", modes = listOf("work", "personal"))
        val config = FeedConfig(listOf(workOnly, shared, local))
        assertEquals(listOf(workOnly, shared, local), config.visible(Visibility(mode = WORK)))
        assertEquals(listOf(shared, local), config.visible(Visibility(mode = WORK, screenShareSafe = true)))
        assertEquals(listOf(shared, local), config.visible(Visibility(mode = PERSONAL, screenShareSafe = true)))
        assertEquals(listOf(local), config.visible(Visibility(mode = OFF)))
    }

    @Test
    fun `reordered follows the addresses given, the rest follow in their order`() {
        val garden = Feed("https://garden.example/rss", "Garden Notes")
        val cdn = Feed("https://cdn.example.org/atom")
        val config = FeedConfig(listOf(dev, local, garden, cdn))
        assertEquals(listOf(garden, dev, local, cdn), config.reordered(listOf("HTTPS://GARDEN.EXAMPLE/RSS", dev.url)).feeds)
        // An address no feed has, or one named twice, moves nothing else, and no feed is ever lost.
        assertEquals(listOf(cdn, dev, local, garden), config.reordered(listOf("https://gone.example/rss", cdn.url, cdn.url)).feeds)
        assertEquals(config, config.reordered(emptyList()))
        // Even two feeds with the same address (a hand edit) both stay.
        val twice = FeedConfig(listOf(local, dev, local))
        assertEquals(listOf(local, local, dev), twice.reordered(listOf(local.url, local.url)).feeds)
    }

    // --- the vault file ------------------------------------------------------------------

    private var stored: String? = null
    private val fileRepository: FileRepository = mockk {
        coEvery { readFile(FeedConfigRepository.PATH) } answers { stored }
        coEvery { saveFileLocally(FeedConfigRepository.PATH, any()) } answers { stored = secondArg() }
        every { moveAsideCorrupt(FeedConfigRepository.PATH) } answers { stored = null; File("feeds.json.corrupt") }
    }
    private val pushScheduler: VaultPushScheduler = mockk(relaxed = true)
    private val repository = FeedConfigRepository(fileRepository, pushScheduler)

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        VaultWriteGate.readOnly = false
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `no file is no feed, and reading writes nothing`() = runTest {
        assertEquals(FeedConfig(), repository.read())
        assertNull(stored)
    }

    @Test
    fun `a change starts from the file on disk and is pushed`() = runTest {
        stored = """{ "feeds": [ { "url": "https://news.example/rss" } ] }"""

        val saved = repository.update { it.add(dev) }

        assertEquals(listOf(local, dev), saved?.feeds)
        assertEquals(saved, repository.read())
        verify { pushScheduler.requestPush() }
    }

    @Test
    fun `an unreadable file is moved aside, never overwritten`() = runTest {
        stored = "{ feeds: [ oops"

        assertEquals(FeedConfig(), repository.read())
        val saved = repository.update { it.add(dev) }

        verify { fileRepository.moveAsideCorrupt(FeedConfigRepository.PATH) }
        assertEquals(listOf(dev), saved?.feeds)
    }

    @Test
    fun `a read-only device changes nothing`() = runTest {
        VaultWriteGate.readOnly = true

        assertNull(repository.update { it.add(dev) })

        coVerify(exactly = 0) { fileRepository.saveFileLocally(any(), any()) }
    }

    @Test
    fun `the file keeps modes and work, and an older file still reads`() = runTest {
        val workOnly = FeedModes.withModes(Feed("https://dev.example/feed", "Dev"), setOf(WORK))
        val shared = Feed("https://cdn.example.org/atom", modes = listOf("work", "personal"))
        repository.update { FeedConfig(listOf(workOnly, shared, local)) }

        val written = Json.parseToJsonElement(stored!!).jsonObject.getValue("feeds").jsonArray.map { it.jsonObject }
        // A Work-only feed writes both: the old flag for the older apps, the modes for 13.4.
        assertEquals(JsonPrimitive(true), written[0]["work"])
        assertEquals(listOf("work"), written[0].getValue("modes").jsonArray.map { it.jsonPrimitive.content })
        assertNull(written[1]["work"])
        assertEquals(listOf("work", "personal"), written[1].getValue("modes").jsonArray.map { it.jsonPrimitive.content })
        // A feed 13.4 never changed keeps its old shape.
        assertEquals(setOf("url"), written[2].keys)
        assertEquals(FeedConfig(listOf(workOnly, shared, local)), repository.read())

        stored = """{ "feeds": [ { "url": "https://dev.example/feed", "name": "Dev", "work": true }, { "url": "https://news.example/rss" } ] }"""
        val older = repository.read().feeds
        assertEquals(setOf(WORK), older[0].shownIn)
        assertNull(older[0].modes)
        assertEquals(allModes, older[1].shownIn)
    }

    /** What a 12.x app knew of a feed. */
    @Serializable
    private data class OldFeed(val url: String, val name: String = "", val work: Boolean = false)

    @Serializable
    private data class OldConfig(val feeds: List<OldFeed> = emptyList())

    /** How a 12.x app read the file: unknown keys skipped. */
    private val olderAppJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun `an older app keeps the Work rule`() = runTest {
        repository.update {
            FeedConfig(
                listOf(
                    FeedModes.withModes(Feed("https://dev.example/feed", "Dev"), setOf(WORK)),
                    Feed("https://cdn.example.org/atom", modes = FeedModes.ALL_KEYS),
                    FeedModes.withModes(Feed("https://garden.example/rss", "Garden Notes"), setOf(PERSONAL, OFF))
                )
            )
        }

        // 12.x read the file with ignoreUnknownKeys: `modes` is skipped, `work` still says Work only.
        val old = olderAppJson.decodeFromString(OldConfig.serializer(), stored!!)
        assertEquals(listOf(true, false, false), old.feeds.map { it.work })
        assertEquals(listOf("Dev", "", "Garden Notes"), old.feeds.map { it.name })
    }

    @Test
    fun `the saved counter moves after a write, not after a refused one`() = runTest {
        assertEquals(0, repository.saved.value)
        repository.update { it.add(dev) }
        assertEquals(1, repository.saved.value)

        // Nothing to change: nothing written.
        repository.update { it.add(dev) }
        assertEquals(1, repository.saved.value)

        // A write that fails.
        coEvery { fileRepository.saveFileLocally(FeedConfigRepository.PATH, any()) } throws IOException("disk full")
        assertNull(repository.update { it.add(local) })
        assertEquals(1, repository.saved.value)

        // A read-only device.
        VaultWriteGate.readOnly = true
        assertNull(repository.update { it.add(local) })
        assertEquals(1, repository.saved.value)
    }
}
