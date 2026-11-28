package cloud.wafflecommons.pixelbrainreader.data.watch

import androidx.room.withTransaction
import cloud.wafflecommons.pixelbrainreader.data.local.AppDatabase
import cloud.wafflecommons.pixelbrainreader.data.local.dao.NewsDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.NewsArticleEntity
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.Visibility
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.web.WebPageLoader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The feeds' cache and the tab's dot (13.4, F3/F6) over a mocked cache: a read never deletes, for
 * its age, a headline it still carries; the dot follows a `feeds.json` a sync changed; a headline
 * stamped when it enters the cache lights the dot of a tab opened during its refresh.
 */
class WatchRepositoryTest {

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = System.currentTimeMillis()

    private val work = Feed("https://dev.example/feed", "Dev", work = true)
    private val news = Feed("https://news.example/rss", "News")

    /** What `feeds.json` holds: a sync may change it without any save on this device. */
    private val feedsFile = MutableStateFlow(FeedConfig(listOf(work)))
    private val feeds: FeedConfigRepository = mockk {
        coEvery { read() } answers { feedsFile.value }
        every { saved } returns MutableStateFlow(0)
    }

    private val rows = MutableStateFlow<List<NewsArticleEntity>>(emptyList())
    private val newsDao: NewsDao = mockk(relaxed = true) {
        every { seenAfterFlow(any(), any()) } answers {
            val sources = firstArg<List<String>>()
            val after = secondArg<Long>()
            rows.map { all -> all.filter { it.sourceName in sources && it.fetchDate > after } }
        }
        coEvery { sources() } answers { rows.value.map { it.sourceName }.distinct() }
        coEvery { urls() } answers { rows.value.map { it.url } }
        coEvery { byUrls(any()) } answers { rows.value.filter { it.url in firstArg<List<String>>() } }
    }
    private val database: AppDatabase = mockk(relaxed = true)
    private val loader: WebPageLoader = mockk()

    /** Both feeds read a minute ago: a refresh reads none of them again (no network in these tests). */
    private val preferences: UserPreferencesRepository = mockk(relaxed = true) {
        every { watchFeedStatus } returns flowOf(
            FeedStatuses.encode(mapOf(work.url to FeedStatus(readAt = now - 60_000L), news.url to FeedStatus(readAt = now - 60_000L)))
        )
    }
    private val marksFlow = MutableStateFlow(FeedMarks())
    private val marksStore: FeedMarksStore = mockk {
        every { marks } returns marksFlow
        coEvery { update(any()) } answers {
            marksFlow.value = firstArg<(FeedMarks) -> FeedMarks>()(marksFlow.value)
            marksFlow.value
        }
    }

    private val repository = WatchRepository(feeds, newsDao, database, loader, preferences, marksStore)

    @Before
    fun setUp() {
        // The transaction runs its block as it is: the cache is a mock.
        mockkStatic("androidx.room.RoomDatabaseKt")
        val block = slot<suspend () -> Any?>()
        coEvery { database.withTransaction(capture(block)) } coAnswers { block.captured.invoke() }
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    private fun row(url: String, feed: Feed, published: Long, seen: Long) =
        NewsArticleEntity(url = url, title = url.substringAfterLast('/'), sourceName = feed.url, thumbnailUrl = null, publishedDate = published, fetchDate = seen)

    @Test
    fun `the dot follows a feed list a sync changed, without a save on this device`() = runBlocking {
        // A new, unread headline of the News feed is cached; feeds.json lists only the Work feed.
        rows.value = listOf(row("https://news.example/1", news, published = now, seen = now))
        val dot = MutableStateFlow<Boolean?>(null)
        val watching = launch(Dispatchers.Default) {
            repository.newSinceOpened(flowOf(Visibility(mode = ContextMode.PERSONAL))).collect { dot.value = it }
        }
        try {
            assertEquals(false, withTimeout(5_000) { dot.first { it != null } })
            repository.refresh()
            assertEquals(false, dot.value)

            // A sync brings a feeds.json with the News feed: the next refresh reads it.
            feedsFile.value = FeedConfig(listOf(work, news))
            repository.refresh()
            assertEquals(true, withTimeout(5_000) { dot.first { it == true } })

            // And one without it again.
            feedsFile.value = FeedConfig(listOf(work))
            repository.refresh()
            assertEquals(false, withTimeout(5_000) { dot.first { it == false } })
        } finally {
            watching.cancel()
        }
    }

    @Test
    fun `a read keeps an undated headline it still carries past the 30 days, with its first-seen time`() = runBlocking {
        val carried = "https://news.example/undated"
        val firstSeen = now - 31 * day
        rows.value = listOf(row(carried, news, published = firstSeen, seen = firstSeen))

        repository.keep(news, listOf(FeedItem("undated", carried, null)), now = now, seenAt = now + 1_000L)

        // Merged into its row, never inserted again as new: the first-seen time and date stay.
        coVerify { newsDao.insertAll(listOf(row(carried, news, published = firstSeen, seen = firstSeen))) }
        // The age prune is the feed's own and spares what this read carries.
        coVerify { newsDao.deleteOlderThanExcept(news.url, TechWatch.cutoff(now), listOf(carried)) }
        coVerify { newsDao.trimSource(news.url, TechWatch.MAX_PER_FEED) }
    }

    @Test
    fun `a read that carries nothing recent still prunes its old rows`() = runBlocking {
        repository.keep(news, listOf(FeedItem("Old", "https://news.example/old", "2020-01-01T00:00:00Z")), now = now, seenAt = now)

        coVerify(exactly = 0) { newsDao.insertAll(any()) }
        coVerify { newsDao.deleteOlderThanExcept(news.url, TechWatch.cutoff(now), emptyList()) }
    }

    @Test
    fun `a headline that enters the cache after the tab was opened lights the dot`() = runBlocking {
        // The refresh began at now; the download took 40 s; the tab was opened and left 10 s in.
        marksFlow.value = FeedMarks(tabOpenedAt = now + 10_000L)
        val inserted = slot<List<NewsArticleEntity>>()
        coEvery { newsDao.insertAll(capture(inserted)) } answers { rows.value = rows.value + inserted.captured }

        repository.keep(news, listOf(FeedItem("Fresh", "https://news.example/fresh", null)), now = now, seenAt = now + 40_000L)

        assertTrue(inserted.captured.all { it.fetchDate == now + 40_000L })
        feedsFile.value = FeedConfig(listOf(news))
        assertEquals(true, withTimeout(5_000) { repository.newSinceOpened(flowOf(Visibility(mode = ContextMode.PERSONAL))).first { it } })
    }
}
