package cloud.wafflecommons.pixelbrainreader.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "news_articles")
data class NewsArticleEntity(
    @PrimaryKey
    val url: String,
    val title: String,
    val sourceName: String,
    val thumbnailUrl: String?,
    val publishedDate: Long,
    val fetchDate: Long // Timestamp of when we fetched/cached it
)
