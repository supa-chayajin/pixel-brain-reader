package cloud.wafflecommons.pixelbrainreader.data.ai

import androidx.room.withTransaction
import cloud.wafflecommons.pixelbrainreader.data.local.AppDatabase
import cloud.wafflecommons.pixelbrainreader.data.local.dao.EmbeddingDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.EmbeddingEntity

/**
 * The embeddings table as [NoteEmbedder] sees it: vectors kept before their note is done carry
 * [EmbeddingEntity.PENDING]; the final set is swapped in one transaction, stamped with the file
 * time the note was read at.
 */
internal class RoomPassageStore(private val database: AppDatabase, private val dao: EmbeddingDao) : PassageStore {
    override suspend fun passagesOf(path: String): List<StoredPassage> =
        dao.rowsFor(path).map { StoredPassage(it.content, it.vector) }

    override suspend fun savePending(path: String, isPrivate: Boolean, passages: List<StoredPassage>) {
        dao.insertAll(passages.map { row(path, isPrivate, it, EmbeddingEntity.PENDING) })
    }

    override suspend fun replace(path: String, isPrivate: Boolean, passages: List<StoredPassage>, stamp: Long) {
        val rows = passages.map { row(path, isPrivate, it, stamp) }
        database.withTransaction {
            dao.deleteByFileId(path)
            if (rows.isNotEmpty()) dao.insertAll(rows)
        }
    }

    private fun row(path: String, isPrivate: Boolean, passage: StoredPassage, stamp: Long) = EmbeddingEntity(
        fileId = path, content = passage.content, vector = passage.vector, isPrivate = isPrivate, lastUpdated = stamp
    )
}
