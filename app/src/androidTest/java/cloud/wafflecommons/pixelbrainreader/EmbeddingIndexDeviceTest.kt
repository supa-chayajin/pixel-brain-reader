package cloud.wafflecommons.pixelbrainreader

import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.wafflecommons.pixelbrainreader.data.ai.NoteEmbedder
import cloud.wafflecommons.pixelbrainreader.data.ai.PassageCodec
import cloud.wafflecommons.pixelbrainreader.data.ai.RoomPassageStore
import cloud.wafflecommons.pixelbrainreader.data.ai.RunBudget
import cloud.wafflecommons.pixelbrainreader.data.ai.VectorSearchEngine
import cloud.wafflecommons.pixelbrainreader.data.local.AppDatabase
import cloud.wafflecommons.pixelbrainreader.data.local.dao.absorbMtimeDrift
import cloud.wafflecommons.pixelbrainreader.data.local.entity.EmbeddingEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileEntity
import cloud.wafflecommons.pixelbrainreader.data.local.security.CryptoManager
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The indexer's bookkeeping on ANDROID's SQLite, and a stopped run resumed through the real model
 * (2026-11-11: the Fold re-embedded one note all night). In-memory database only: nothing on the
 * device changes. Run without uninstalling the app:
 * `adb shell am instrument -w -e class cloud.wafflecommons.pixelbrainreader.EmbeddingIndexDeviceTest
 * cloud.wafflecommons.pixelbrainreader.test/androidx.test.runner.AndroidJUnitRunner`.
 */
@RunWith(AndroidJUnit4::class)
class EmbeddingIndexDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun note(path: String, mtime: Long?) =
        db.fileDao().insertFile(FileEntity(path = path, name = path, type = "file", downloadUrl = null, sha = "sha", localModifiedTimestamp = mtime))

    private suspend fun vectors(path: String, stamp: Long, count: Int = 2) =
        db.embeddingDao().insertAll((1..count).map { EmbeddingEntity(fileId = path, content = "p$it", vector = listOf(it.toFloat()), lastUpdated = stamp) })

    private suspend fun needing() = db.fileDao().getFilesNeedingEmbeddings().map { it.path }.toSet()

    @Test
    fun aNoteIsCurrentOnlyWhileEveryPassageIs() = runBlocking {
        note("new.md", 1_000L)
        note("done.md", 1_000L); vectors("done.md", 1_000L)
        note("edited.md", 2_000L); vectors("edited.md", 1_000L)
        note("stopped.md", 1_000L); vectors("stopped.md", 1_000L); vectors("stopped.md", EmbeddingEntity.PENDING, count = 1)
        note("legacy.md", null); vectors("legacy.md", 1_000L)
        assertEquals(setOf("new.md", "edited.md", "stopped.md"), needing())

        db.embeddingDao().markStale(listOf("done.md", "legacy.md"))
        assertEquals(setOf("new.md", "edited.md", "stopped.md", "done.md", "legacy.md"), needing())
        assertEquals(2, db.embeddingDao().rowsFor("done.md").size) // stale, not deleted: reused by the indexer
    }

    @Test
    fun aRewriteWithTheSameContentKeepsCurrentVectorsOnly() = runBlocking {
        note("current.md", 1_000L); vectors("current.md", 1_000L)
        note("stale.md", 1_000L); vectors("stale.md", 1_000L); db.embeddingDao().markStale(listOf("stale.md"))

        // A pull rewrote both with the same content: newer file times, same text.
        for (path in listOf("current.md", "stale.md")) {
            db.fileDao().updateMtime(path, 5_000L)
            db.embeddingDao().absorbMtimeDrift(path, oldMtime = 1_000L, newMtime = 5_000L)
        }

        assertEquals(setOf("stale.md"), needing())
    }

    @Test
    fun aStoppedRunResumesTheNoteThroughTheRealModel() = runBlocking {
        val engine = VectorSearchEngine(
            context, db.embeddingDao(), CryptoManager(), SecretManager(context),
            dagger.Lazy<ModesRepository> { error("not used by embed") },
            dagger.Lazy<UserPreferencesRepository> { error("not used by embed") }
        )
        note("big.md", 1_000L)
        val passages = (1..40).map { "[Note: big (Vault) > Général]\nLigne $it d'une longue note copiée depuis le bureau, mise à jour souvent." }
        var calls = 0
        val embed: suspend (String) -> FloatArray = { calls++; engine.embed(it) }
        val store = RoomPassageStore(db, db.embeddingDao())
        engine.embed("warm-up: the model loads once") // keep the load out of the timing

        // The first run is cut after two batches, as the job limit cut every run on the Fold.
        var checks = 0
        val first = NoteEmbedder(store, embed, batchSize = 8)
            .embedNote("big.md", false, passages, PassageCodec.Plain, RunBudget(limitMs = 17) { (checks++).toLong() }, stamp = 1_000L)
        assertTrue(first is NoteEmbedder.Outcome.Stopped)
        assertEquals(16, db.embeddingDao().rowsFor("big.md").size)
        assertEquals(setOf("big.md"), needing())

        val started = System.nanoTime()
        val second = NoteEmbedder(store, embed)
            .embedNote("big.md", false, passages, PassageCodec.Plain, RunBudget(Long.MAX_VALUE), stamp = 1_000L)
        val msPerPassage = (System.nanoTime() - started) / 1_000_000L / 24
        Log.i("RAG_DEBUG", "EmbeddingIndexDeviceTest: $msPerPassage ms per passage")

        assertEquals(NoteEmbedder.Outcome.Done(embedded = 24, reused = 16, skipped = 0), second)
        assertEquals(passages.size, calls) // each passage went through the model once
        val rows = db.embeddingDao().rowsFor("big.md")
        assertEquals(passages.toSet(), rows.map { it.content }.toSet())
        assertEquals(passages.size, rows.size)
        assertEquals(384, rows.first().vector.size)
        assertEquals(emptySet<String>(), needing())
    }
}
