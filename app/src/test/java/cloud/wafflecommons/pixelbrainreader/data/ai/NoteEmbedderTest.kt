package cloud.wafflecommons.pixelbrainreader.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins the indexer's two promises (2026-11-11, the Fold re-embedded one note all night): work is
 * never repeated — an unchanged passage keeps its vector — and never lost — a stopped run's
 * vectors are kept and the next run resumes the note.
 */
class NoteEmbedderTest {

    /** The embeddings table of one note: pending rows as they come, then the final swap. */
    private class FakeStore : PassageStore {
        val rows = mutableMapOf<String, MutableList<StoredPassage>>()
        val stamps = mutableMapOf<String, Long>()
        var pendingSaves = 0

        override suspend fun passagesOf(path: String): List<StoredPassage> = rows[path].orEmpty().toList()

        override suspend fun savePending(path: String, isPrivate: Boolean, passages: List<StoredPassage>) {
            pendingSaves++
            rows.getOrPut(path) { mutableListOf() } += passages
        }

        override suspend fun replace(path: String, isPrivate: Boolean, passages: List<StoredPassage>, stamp: Long) {
            rows[path] = passages.toMutableList()
            stamps[path] = stamp
        }
    }

    /** A model that counts what it was asked to embed: one float per passage, its length. */
    private class CountingModel {
        val calls = mutableListOf<String>()
        suspend fun embed(text: String): FloatArray {
            calls += text
            return floatArrayOf(text.length.toFloat())
        }
    }

    private val never = RunBudget(Long.MAX_VALUE)

    private fun passages(count: Int, prefix: String = "passage") = (1..count).map { "$prefix $it" }

    @Test
    fun `a new note embeds each distinct passage once and keeps them in note order`() = runTest {
        val store = FakeStore()
        val model = CountingModel()
        val note = listOf("intro", "body", "intro") // a repeated passage is embedded once

        val outcome = NoteEmbedder(store, model::embed).embedNote("a.md", false, note, PassageCodec.Plain, never, stamp = 42L)

        assertEquals(NoteEmbedder.Outcome.Done(embedded = 2, reused = 0, skipped = 0), outcome)
        assertEquals(listOf("intro", "body"), model.calls)
        assertEquals(note, store.rows.getValue("a.md").map { it.content })
        assertEquals(42L, store.stamps.getValue("a.md"))
    }

    @Test
    fun `an edited note only embeds its new passages`() = runTest {
        val store = FakeStore()
        val model = CountingModel()
        val embedder = NoteEmbedder(store, model::embed)
        val before = passages(600) // the size of the mirrored TODO that looped
        embedder.embedNote("todo.md", false, before, PassageCodec.Plain, never, stamp = 1L)
        model.calls.clear()

        val after = before.toMutableList().apply { set(10, "a reworded line"); add("a new line") }
        val outcome = embedder.embedNote("todo.md", false, after, PassageCodec.Plain, never, stamp = 2L)

        assertEquals(listOf("a reworded line", "a new line"), model.calls)
        assertEquals(NoteEmbedder.Outcome.Done(embedded = 2, reused = 599, skipped = 0), outcome)
        assertEquals(after, store.rows.getValue("todo.md").map { it.content })
    }

    @Test
    fun `runs stopped mid-note never embed a passage twice and the note completes`() = runTest {
        val store = FakeStore()
        val model = CountingModel()
        val note = passages(600)
        var runs = 0
        var outcome: NoteEmbedder.Outcome
        do {
            runs++
            // Each run is cut after ~40 passages, as the job limit cut every run on the Fold.
            var checks = 0
            val budget = RunBudget(limitMs = 40) { (checks++).toLong() }
            outcome = NoteEmbedder(store, model::embed, batchSize = 8).embedNote("todo.md", false, note, PassageCodec.Plain, budget, stamp = 9L)
            assertTrue("no progress: a run starting over would loop forever", runs < 100)
        } while (outcome is NoteEmbedder.Outcome.Stopped)

        assertEquals(note.size, model.calls.size)
        assertEquals(note.toSet(), model.calls.toSet())
        assertEquals(note, store.rows.getValue("todo.md").map { it.content })
        assertTrue("it took several runs", runs > 1)
    }

    @Test
    fun `a run out of time keeps every passage it embedded, a part batch included`() = runTest {
        val store = FakeStore()
        var checks = 0
        val budget = RunBudget(limitMs = 12) { (checks++).toLong() } // 11 passages: a batch and a part

        val outcome = NoteEmbedder(store, CountingModel()::embed, batchSize = 8)
            .embedNote("todo.md", false, passages(100), PassageCodec.Plain, budget, stamp = 9L)

        assertTrue(outcome is NoteEmbedder.Outcome.Stopped)
        assertEquals(11, outcome.embedded)
        assertEquals(11, store.rows.getValue("todo.md").size)
        assertEquals(null, store.stamps["todo.md"]) // not done: still pending for the next run
    }

    @Test
    fun `a passage the model rejects is left out and the note still completes`() = runTest {
        val store = FakeStore()
        val embed: suspend (String) -> FloatArray = { text ->
            if (text == "bad") throw IllegalArgumentException("tokenizer choked") else floatArrayOf(1f)
        }

        val outcome = NoteEmbedder(store, embed).embedNote("a.md", false, listOf("good", "bad", "fine"), PassageCodec.Plain, never, 5L)

        assertEquals(NoteEmbedder.Outcome.Done(embedded = 2, reused = 0, skipped = 1), outcome)
        assertEquals(listOf("good", "fine"), store.rows.getValue("a.md").map { it.content })
    }

    @Test
    fun `a model that can't start stops the run instead of failing every passage`() = runTest {
        val store = FakeStore()
        val embed: suspend (String) -> FloatArray = { throw EmbedderUnavailableException("asset missing") }

        try {
            NoteEmbedder(store, embed).embedNote("a.md", false, listOf("x"), PassageCodec.Plain, never, 5L)
            fail("expected the run to stop")
        } catch (e: EmbedderUnavailableException) {
            assertEquals(null, store.rows["a.md"])
        }
    }

    @Test
    fun `a cancelled run keeps the batches before the cancel`() = runTest {
        val store = FakeStore()
        var count = 0
        val embed: suspend (String) -> FloatArray = {
            if (++count == 12) throw CancellationException("the system stopped the job")
            floatArrayOf(1f)
        }

        try {
            NoteEmbedder(store, embed, batchSize = 8).embedNote("a.md", false, passages(20), PassageCodec.Plain, never, 5L)
            fail("expected the cancellation to propagate")
        } catch (e: CancellationException) {
            assertEquals(8, store.rows.getValue("a.md").size) // the first batch was kept
        }
    }

    @Test
    fun `a stored passage that can't be read back is embedded again`() = runTest {
        val store = FakeStore()
        store.rows["secret.md.enc"] = mutableListOf(StoredPassage("ciphertext-of-another-password", listOf(0f)))
        val model = CountingModel()
        val unreadable = object : PassageCodec {
            override suspend fun encode(text: String) = "enc:$text"
            override suspend fun decode(stored: String): String? = stored.removePrefix("enc:").takeIf { stored.startsWith("enc:") }
        }

        NoteEmbedder(store, model::embed).embedNote("secret.md.enc", true, listOf("diary"), unreadable, never, 5L)

        assertEquals(listOf("diary"), model.calls)
        assertEquals(listOf("enc:diary"), store.rows.getValue("secret.md.enc").map { it.content })
    }

    @Test
    fun `a note that is fully known needs no model at all`() = runTest {
        val store = FakeStore()
        NoteEmbedder(store, CountingModel()::embed).embedNote("a.md", false, listOf("one", "two"), PassageCodec.Plain, never, 1L)
        val embed: suspend (String) -> FloatArray = { fail("the model was loaded for nothing"); floatArrayOf() }

        val outcome = NoteEmbedder(store, embed).embedNote("a.md", false, listOf("one", "two"), PassageCodec.Plain, never, 2L)

        assertEquals(NoteEmbedder.Outcome.Done(embedded = 0, reused = 2, skipped = 0), outcome)
        assertEquals(2L, store.stamps.getValue("a.md"))
    }

    @Test
    fun `the budget is spent once its time has passed`() {
        var now = 1_000L
        val budget = RunBudget(limitMs = 500) { now }
        assertEquals(false, budget.exhausted())
        now += 499
        assertEquals(false, budget.exhausted())
        now += 1
        assertEquals(true, budget.exhausted())
    }
}
