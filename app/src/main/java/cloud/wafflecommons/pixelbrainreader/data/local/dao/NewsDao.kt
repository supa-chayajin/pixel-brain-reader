package cloud.wafflecommons.pixelbrainreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cloud.wafflecommons.pixelbrainreader.data.local.entity.NewsArticleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NewsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(news: List<NewsArticleEntity>)

    // Get news fetched today
    @Query("SELECT * FROM news_articles WHERE fetchDate >= :startOfDay ORDER BY fetchDate DESC")
    suspend fun getTodayNews(startOfDay: Long): List<NewsArticleEntity>

    @Query("DELETE FROM news_articles")
    suspend fun deleteAll()

    @Query("DELETE FROM news_articles WHERE fetchDate >= :startOfDay")
    suspend fun deleteTodayNews(startOfDay: Long)

    // The feeds (12.12): `sourceName` holds the address of the feed that first brought the item,
    // stable when the feed is renamed. 13.4: `fetchDate` is when the item was first seen.

    /** The cached rows of these addresses (13.4: a later read keeps their first-seen time and their feed). */
    @Query("SELECT * FROM news_articles WHERE url IN (:urls)")
    suspend fun byUrls(urls: List<String>): List<NewsArticleEntity>

    /** The Feeds tab's stream and Daily's block (13.4), re-read at every change of the cache. */
    @Query("SELECT * FROM news_articles WHERE sourceName IN (:sources)")
    fun bySourcesFlow(sources: List<String>): Flow<List<NewsArticleEntity>>

    /** The headlines first seen after [after] (13.4: the tab's dot). */
    @Query("SELECT * FROM news_articles WHERE sourceName IN (:sources) AND fetchDate > :after")
    fun seenAfterFlow(sources: List<String>, after: Long): Flow<List<NewsArticleEntity>>

    /** Articles of feeds no longer in the list. */
    @Query("DELETE FROM news_articles WHERE sourceName NOT IN (:sources)")
    suspend fun deleteOtherSources(sources: List<String>)

    /**
     * History (13.4, F6), at each successful read of the feed [source]: its rows published before
     * [cutoff] go, except those the read still [carried] (an undated headline keeps its first-seen
     * date, and deleting it while the feed lists it would bring it back as new at the next read).
     */
    @Query("DELETE FROM news_articles WHERE sourceName = :source AND publishedDate < :cutoff AND url NOT IN (:carried)")
    suspend fun deleteOlderThanExcept(source: String, cutoff: Long, carried: List<String>): Int

    /** A feed's [keep] newest rows stay, the rest go: a runaway feed can't fill the cache (13.4). */
    @Query(
        "DELETE FROM news_articles WHERE sourceName = :source AND url NOT IN " +
            "(SELECT url FROM news_articles WHERE sourceName = :source ORDER BY publishedDate DESC LIMIT :keep)"
    )
    suspend fun trimSource(source: String, keep: Int): Int

    /** The feeds with headlines in the cache (12.18, D5a: a feed with none is read at once). */
    @Query("SELECT DISTINCT sourceName FROM news_articles")
    suspend fun sources(): List<String>

    /** Every cached address (13.4: the read marks of the others are pruned). */
    @Query("SELECT url FROM news_articles")
    suspend fun urls(): List<String>
}
