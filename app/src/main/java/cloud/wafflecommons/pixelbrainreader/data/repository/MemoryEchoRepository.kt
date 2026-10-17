package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileEntity
import cloud.wafflecommons.pixelbrainreader.data.model.MemoryEcho
import cloud.wafflecommons.pixelbrainreader.data.remote.GitFileAges
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Memory Echoes: notes untouched for a while, resurfaced on the Daily screen.
 *
 * "Untouched" is measured in GIT (author time of the last commit touching the note): on-disk
 * mtimes are rewritten by every clone and pull. Each day the selection rotates among the most
 * forgotten notes, and a note marked as reviewed rests for two weeks (the review log lives in the
 * vault, so it syncs). Journal, private, encrypted and hidden notes never echo.
 */
@Singleton
class MemoryEchoRepository @Inject constructor(
    private val fileDao: FileDao,
    private val fileContentDao: FileContentDao,
    private val gitFileAges: GitFileAges,
    private val echoReviewStore: EchoReviewStore,
    private val appClock: AppClock
) {
    suspend fun getDailyEchoes(limit: Int = 3, now: Long = System.currentTimeMillis()): List<MemoryEcho> =
        withContext(Dispatchers.IO) {
            val files = fileDao.getAllFiles().firstOrNull().orEmpty()
            val lastChanged = gitFileAges.lastChanged()
            val reviewedAt = echoReviewStore.reviewedAt()
            val todayEpochDay = appClock.today.value.toEpochDay()

            val candidates = files.mapNotNull { entity ->
                val changedAt = lastChanged[entity.path] ?: return@mapNotNull null // not committed yet: not forgotten
                if (!isEchoable(entity)) return@mapNotNull null
                if (now - changedAt < MIN_AGE_MS) return@mapNotNull null
                val reviewed = reviewedAt[entity.path]
                if (reviewed != null && now - reviewed < REST_MS) return@mapNotNull null
                entity to changedAt
            }

            // The most forgotten notes form the pool; a shuffle seeded by the day picks which of them
            // echo — stable within a day, different from one day to the next. (Ordering by
            // hash(path + day) barely moved: appending the same suffix preserves most of the order.)
            candidates
                .sortedWith(compareBy({ it.second }, { it.first.path }))
                .take(POOL_SIZE)
                .shuffled(Random(todayEpochDay))
                .take(limit)
                .map { (entity, changedAt) -> toEcho(entity, days = (now - changedAt) / DAY_MS) }
        }

    suspend fun markEchoReviewed(path: String, now: Long = System.currentTimeMillis()) {
        echoReviewStore.markReviewed(path, now)
    }

    private suspend fun toEcho(entity: FileEntity, days: Long): MemoryEcho {
        val snippet = cleanSnippet(fileContentDao.getContent(entity.path).orEmpty())
        return MemoryEcho(
            path = entity.path,
            title = entity.name.removeSuffix(".md"),
            categoryBadge = categoryBadge(entity.path),
            daysSinceModified = days,
            snippet = snippet,
            urgencyLabel = when {
                days >= 60 -> "Forgotten for $days days"
                days >= 30 -> "Untouched for $days days"
                else -> "Last edited $days days ago"
            }
        )
    }

    private fun isEchoable(entity: FileEntity): Boolean =
        entity.type == "file" &&
            entity.path.endsWith(".md") &&
            !entity.path.startsWith("10_Journal") &&
            !entity.path.startsWith("99_Private") &&
            entity.path.split('/').none { it.startsWith(".") }

    private fun categoryBadge(path: String): String {
        val segments = path.split("/").filter { it.isNotBlank() }
        return when {
            segments.size > 2 -> segments[1] // "20_Projects/21_Waffle/doc.md" → "21_Waffle"
            segments.size > 1 -> segments[0] // "20_Projects/doc.md" → "20_Projects"
            else -> "Vault"
        }
    }

    private fun cleanSnippet(content: String): String {
        val withoutFrontmatter = if (content.startsWith("---")) {
            val second = content.indexOf("---", 3)
            if (second != -1) content.substring(second + 3).trim() else content
        } else content

        val cleaned = withoutFrontmatter
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .joinToString(" ")
            .replace("\\s+".toRegex(), " ")

        return if (cleaned.length > 200) cleaned.take(200) + "…" else cleaned
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val MIN_AGE_MS = 14 * DAY_MS
        const val REST_MS = 14 * DAY_MS
        /** How many of the most forgotten notes the daily rotation draws from. */
        const val POOL_SIZE = 30
    }
}
