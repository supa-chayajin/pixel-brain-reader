package cloud.wafflecommons.pixelbrainreader.data.ai

import kotlinx.coroutines.CancellationException

/**
 * A run's time. The system stops a background job after 10 minutes, and a stopped run used to lose
 * the note it was embedding: a run now ends itself at [limitMs] and hands over to the next one.
 */
class RunBudget(private val limitMs: Long, private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private val startedAt = clockMs()

    fun exhausted(): Boolean = clockMs() - startedAt >= limitMs
}

/** A passage as the embeddings table keeps it: [content] is ciphertext for a private note. */
data class StoredPassage(val content: String, val vector: List<Float>)

/** Where a note's passages are kept: the embeddings table (a fake in tests). */
interface PassageStore {
    suspend fun passagesOf(path: String): List<StoredPassage>

    /** Keeps new vectors as they come, before the note is done: what a stopped run leaves behind. */
    suspend fun savePending(path: String, isPrivate: Boolean, passages: List<StoredPassage>)

    /** Swaps the note's rows for [passages], in note order and in one step, stamped done at [stamp]. */
    suspend fun replace(path: String, isPrivate: Boolean, passages: List<StoredPassage>, stamp: Long)
}

/** Plain text ⇄ the stored form: a private note's passages are encrypted, a public note's kept as they are. */
interface PassageCodec {
    suspend fun encode(text: String): String

    /** Null when the stored passage can't be read back (another password): it is not reused. */
    suspend fun decode(stored: String): String?

    object Plain : PassageCodec {
        override suspend fun encode(text: String): String = text
        override suspend fun decode(stored: String): String = stored
    }
}

/** The model itself can't run: every note would fail the same way, so the run stops. */
class EmbedderUnavailableException(message: String) : IllegalStateException(message)

/**
 * Embeds one note without ever losing or repeating work.
 *
 * 2026-11-11: the Fold re-embedded the same 231 KB note all night. Each run was stopped by the
 * system's 10-minute job limit before the note's single save, and the next run started it over.
 *
 * - A passage whose exact text already has a vector for this note (an unchanged part of an edited
 *   note, or one a stopped run kept) reuses it: only new text goes through the model.
 * - New vectors are kept [batchSize] at a time as they come, pending: a stop costs at most one
 *   batch, and the next run finds them as known passages.
 * - Once every passage has its vector, the note's rows are swapped for the final set in one step.
 * - A passage the model rejects is left out instead of being retried on every run.
 */
class NoteEmbedder(
    private val store: PassageStore,
    private val embed: suspend (String) -> FloatArray,
    private val batchSize: Int = BATCH_SIZE,
) {
    sealed interface Outcome {
        /** Passages that went through the model in this call. */
        val embedded: Int

        /** Every passage has its vector: [reused] of them were already known, [skipped] were rejected. */
        data class Done(override val embedded: Int, val reused: Int, val skipped: Int) : Outcome

        /** The run's time ran out: the next run resumes from what was kept. */
        data class Stopped(override val embedded: Int) : Outcome
    }

    suspend fun embedNote(
        path: String,
        isPrivate: Boolean,
        passages: List<String>,
        codec: PassageCodec,
        budget: RunBudget,
        stamp: Long,
        onEmbedded: suspend (Int) -> Unit = {},
    ): Outcome {
        val known = HashMap<String, StoredPassage>()
        for (stored in store.passagesOf(path)) {
            val text = codec.decode(stored.content) ?: continue
            known.putIfAbsent(text, stored)
        }
        val reused = passages.count { it in known }
        val rejected = HashSet<String>()
        var embedded = 0

        for (batch in passages.distinct().filterNot { it in known }.chunked(batchSize)) {
            if (budget.exhausted()) return Outcome.Stopped(embedded)
            val fresh = ArrayList<Pair<String, StoredPassage>>(batch.size)
            for (text in batch) {
                val vector = try {
                    embed(text)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: EmbedderUnavailableException) {
                    throw e
                } catch (e: Exception) {
                    rejected += text
                    continue
                }
                fresh += text to StoredPassage(codec.encode(text), vector.toList())
            }
            if (fresh.isEmpty()) continue
            store.savePending(path, isPrivate, fresh.map { it.second })
            fresh.forEach { (text, passage) -> known[text] = passage }
            embedded += fresh.size
            onEmbedded(fresh.size)
        }

        store.replace(path, isPrivate, passages.mapNotNull { known[it] }, stamp)
        return Outcome.Done(embedded = embedded, reused = reused, skipped = passages.count { it in rejected })
    }

    companion object {
        /** Vectors kept per save: a few seconds of model time on the phone. */
        const val BATCH_SIZE = 8
    }
}
