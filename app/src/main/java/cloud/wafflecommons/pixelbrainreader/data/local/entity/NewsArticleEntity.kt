package cloud.wafflecommons.pixelbrainreader.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A feed's headline in the cache (12.12; 13.4: what each feed carried in the last 30 days).
 * [sourceName] is the address of the feed that first brought it; [thumbnailUrl] its picture when the
 * feed gave one the feeds' picture rules allow (13.4, F7); [publishedDate] its date, or, for an item
 * that never gave one, the time it was first seen.
 */
@Entity(tableName = "news_articles")
data class NewsArticleEntity(
    @PrimaryKey
    val url: String,
    val title: String,
    val sourceName: String,
    val thumbnailUrl: String?,
    val publishedDate: Long,
    /** When the item was first seen (13.4: kept on every later read, for the unread marks and the tab's dot). */
    val fetchDate: Long
)
