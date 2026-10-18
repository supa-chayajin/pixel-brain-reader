package cloud.wafflecommons.pixelbrainreader.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import cloud.wafflecommons.pixelbrainreader.data.local.entity.LinkEntity

@Dao
interface LinkDao {
    @Insert
    suspend fun insertAll(links: List<LinkEntity>)

    @Query("DELETE FROM links WHERE source IN (:sources)")
    suspend fun deleteBySources(sources: List<String>)

    @Query("SELECT * FROM links WHERE targetKey = :key AND source != :exceptSource ORDER BY source, line")
    suspend fun linksTo(key: String, exceptSource: String): List<LinkEntity>

    @Query("SELECT * FROM links WHERE source = :source ORDER BY line")
    suspend fun linksFrom(source: String): List<LinkEntity>

    @Query("SELECT * FROM links WHERE kind != 'embed'")
    suspend fun allNoteLinks(): List<LinkEntity>

    @Query("SELECT DISTINCT source FROM links WHERE targetKey = :key")
    suspend fun sourcesLinkingTo(key: String): List<String>
}
