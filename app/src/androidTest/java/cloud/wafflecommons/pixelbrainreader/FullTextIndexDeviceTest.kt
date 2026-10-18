package cloud.wafflecommons.pixelbrainreader

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.wafflecommons.pixelbrainreader.data.local.AppDatabase
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileContentEntity
import cloud.wafflecommons.pixelbrainreader.data.search.Bm25
import cloud.wafflecommons.pixelbrainreader.data.search.FtsQuery
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The full-text index on ANDROID's SQLite: the unicode61 tokenizer's diacritic folding, Room's sync
 * triggers (an upsert must not leave stale terms behind) and the matchinfo layout BM25 reads. None of
 * this runs on the JVM. Run on the emulator: `./gradlew connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class FullTextIndexDeviceTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private fun paths(query: String) = runBlocking {
        db.fileSearchDao().match(FtsQuery.build(query)!!, 50).map { it.path }
    }

    @Test
    fun accentsAreIgnoredBothWays() = runBlocking {
        db.fileContentDao().saveContent(FileContentEntity("a.md", "Le réseau est lent"))
        db.fileContentDao().saveContent(FileContentEntity("b.md", "Reseau sans accent"))
        assertEquals(setOf("a.md", "b.md"), paths("reseau").toSet())
        assertEquals(setOf("a.md", "b.md"), paths("réseau").toSet())
    }

    @Test
    fun anUpsertReplacesTheIndexedTerms() = runBlocking {
        db.fileContentDao().saveContent(FileContentEntity("n.md", "alpha"))
        db.fileContentDao().saveContent(FileContentEntity("n.md", "omega"))
        assertTrue(paths("alpha").isEmpty())
        assertEquals(listOf("n.md"), paths("omega"))
        db.fileContentDao().deleteContents(listOf("n.md"))
        assertTrue(paths("omega").isEmpty())
    }

    @Test
    fun prefixTermsAndRankingWork() = runBlocking {
        db.fileContentDao().saveContent(FileContentEntity("x.md", "rebase rebase rebase conflict"))
        db.fileContentDao().saveContent(FileContentEntity("y.md", "one rebase among many many other words here"))
        val hits = db.fileSearchDao().match(FtsQuery.build("reba")!!, 50)
        assertEquals(2, hits.size)
        val scores = hits.associate { it.path to Bm25.score(Bm25.decode(it.matchInfo), doubleArrayOf(2.0, 1.0)) }
        assertTrue(scores.getValue("x.md") > scores.getValue("y.md"))
        assertTrue(hits.first { it.path == "x.md" }.excerpt.contains("«"))
    }
}
