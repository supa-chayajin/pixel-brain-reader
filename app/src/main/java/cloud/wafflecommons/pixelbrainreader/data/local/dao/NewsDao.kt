package cloud.wafflecommons.pixelbrainreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cloud.wafflecommons.pixelbrainreader.data.local.entity.NewsArticleEntity

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
}
