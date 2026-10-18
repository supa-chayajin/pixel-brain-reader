package cloud.wafflecommons.pixelbrainreader.data.local.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** One full-text hit: the note, SQLite's raw `matchinfo('pcnalx')` blob and a short excerpt. */
data class FtsHitRow(
    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "mi") val matchInfo: ByteArray,
    @ColumnInfo(name = "excerpt") val excerpt: String
) {
    override fun equals(other: Any?): Boolean =
        other is FtsHitRow && other.path == path && other.excerpt == excerpt && other.matchInfo.contentEquals(matchInfo)

    override fun hashCode(): Int = 31 * (31 * path.hashCode() + excerpt.hashCode()) + matchInfo.contentHashCode()
}

@Dao
interface FileSearchDao {

    /** [match] must come from FtsQuery.build (sanitized, prefix terms). Ranking happens in Kotlin (BM25). */
    @Query(
        "SELECT path, matchinfo(file_contents_fts, 'pcnalx') AS mi, " +
            "snippet(file_contents_fts, '«', '»', '…', -1, 12) AS excerpt " +
            "FROM file_contents_fts WHERE file_contents_fts MATCH :match LIMIT :limit"
    )
    suspend fun match(match: String, limit: Int): List<FtsHitRow>

    /** Emits whenever the files or their contents change: the cue to re-run an open search. */
    @Query("SELECT (SELECT COUNT(*) FROM files) + (SELECT COUNT(*) FROM file_contents)")
    fun observeChanges(): Flow<Int>
}
