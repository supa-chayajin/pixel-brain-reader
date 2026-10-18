package cloud.wafflecommons.pixelbrainreader.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One link from a note ([source]) to a target as written. Links resolve by [targetKey] (the target's
 * basename, lower case, without `.md`) at query time, so a link to a note created later resolves
 * without re-indexing. Derived from the vault by the scan; rebuilt after a destructive migration.
 */
@Entity(
    tableName = "links",
    indices = [Index(value = ["source"]), Index(value = ["targetKey"])]
)
data class LinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    val target: String,
    val targetKey: String,
    val kind: String,
    val line: Int,
    val context: String
)
