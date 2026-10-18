package cloud.wafflecommons.pixelbrainreader.data.local.entity

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions

/**
 * Full-text index over the `file_contents` mirror (external content: Room keeps it in sync with
 * triggers, so the text is stored once). `unicode61` with `remove_diacritics` makes French accents
 * irrelevant on both sides: "reseau" finds "réseau". Private notes are never in `file_contents`, so
 * they are never in this index either.
 */
@Fts4(
    contentEntity = FileContentEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics=1"]
)
@Entity(tableName = "file_contents_fts")
data class FileContentFtsEntity(
    val path: String,
    val content: String
)
