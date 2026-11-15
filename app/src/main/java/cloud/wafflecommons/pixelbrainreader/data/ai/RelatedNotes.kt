package cloud.wafflecommons.pixelbrainreader.data.ai

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Related while writing (K5, 12.20): the notes closest by meaning to the paragraph being written,
 * through the production vector search (the 0.35 floor and the mode's visibility apply). Never the
 * note itself, and never a private note: a tap links it, and its name would land in a plain note.
 */
@Singleton
class RelatedNotes @Inject constructor(
    private val vectorSearchEngine: VectorSearchEngine
) {
    suspend fun closestTo(paragraph: String, notePath: String?, limit: Int = LIMIT): List<String> =
        vectorSearchEngine.search(
            paragraph,
            limit = limit * PASSAGES_PER_NOTE,
            within = { it != notePath && !it.startsWith(PRIVATE_ROOT) }
        ).map { it.fileId }.distinct().take(limit)

    companion object {
        const val LIMIT = 3

        /** Passages asked per note wanted: a note's passages often come several in a row. */
        private const val PASSAGES_PER_NOTE = 4
        private const val PRIVATE_ROOT = "99_Private/"
    }
}
