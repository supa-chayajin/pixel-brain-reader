package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileDao
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
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
    private val appClock: AppClock,
    private val modesRepository: ModesRepository
) {
    suspend fun getDailyEchoes(limit: Int = 3, now: Long = System.currentTimeMillis()): List<MemoryEcho> =
        withContext(Dispatchers.IO) {
            val files = fileDao.getAllFiles().firstOrNull().orEmpty()
            val lastChanged = gitFileAges.lastChanged()
            val reviewedAt = echoReviewStore.reviewedAt()
            val todayEpochDay = appClock.today.value.toEpochDay()
            val visibility = modesRepository.visibility.value

            val candidates = files.mapNotNull { entity ->
                val changedAt = lastChanged[entity.path] ?: return@mapNotNull null // not committed yet: not forgotten
                if (!isEchoable(entity) || !visibility.isVisible(entity.path)) return@mapNotNull null
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

    /**
     * Today's echoes (K6, 12.20): the spaced ones due first (notes tagged #review and the notes of
     * the picked folders, the owner's D11), then forgotten notes as before.
     */
    suspend fun getEchoesForToday(
        forgottenLimit: Int = 2,
        spacedLimit: Int = 3,
        now: Long = System.currentTimeMillis()
    ): List<MemoryEcho> = withContext(Dispatchers.IO) {
        val spaced = spacedEchoes(spacedLimit)
        val forgotten = getDailyEchoes(forgottenLimit + spaced.size, now)
            .filter { echo -> spaced.none { it.path == echo.path } }
            .take(forgottenLimit)
        spaced + forgotten
    }

    private suspend fun spacedEchoes(limit: Int): List<MemoryEcho> {
        val folders = echoReviewStore.folders()
        val visibility = modesRepository.visibility.value
        val files = fileDao.getAllFiles().firstOrNull().orEmpty().filter { entity ->
            entity.type == "file" && entity.path.endsWith(".md") && !entity.path.startsWith("99_Private") &&
                entity.path.split('/').none { it.startsWith(".") } && visibility.isVisible(entity.path)
        }
        val byPath = files.associateBy { it.path }
        val tagged = fileContentDao.pathsContaining("review")
            .filter { it in byPath && SpacedReview.tagged(fileContentDao.getContent(it).orEmpty()) }
        val inScope = (byPath.keys.filter { SpacedReview.inFolders(it, folders) } + tagged).distinct()
        if (inScope.isEmpty()) return emptyList()
        val cards = echoReviewStore.spacedCards()
        val today = appClock.today.value.toEpochDay()
        return SpacedReview.due(inScope, cards, today, limit).mapNotNull { path ->
            val entity = byPath[path] ?: return@mapNotNull null
            val card = cards[path]
            toEcho(entity, days = 0).copy(
                spaced = true,
                urgencyLabel = when {
                    card == null -> "New to review"
                    card.due < today -> "Review · ${today - card.due} days late"
                    else -> "Review today"
                }
            )
        }
    }

    suspend fun answerSpaced(path: String, answer: SpacedReview.Answer) =
        echoReviewStore.answer(path, answer, appClock.today.value.toEpochDay())

    suspend fun reviewFolders(): Set<String> = echoReviewStore.folders()

    suspend fun setReviewFolders(folders: Set<String>) = echoReviewStore.setFolders(folders)

    /** The vault's folders, two levels deep, for the review picker; private and hidden ones aside. */
    suspend fun folderChoices(): List<String> = withContext(Dispatchers.IO) {
        fileDao.getAllFiles().firstOrNull().orEmpty().asSequence()
            .map { it.path }
            .filter { it.endsWith(".md") }
            .flatMap { path ->
                val parts = path.split('/').dropLast(1)
                listOfNotNull(parts.getOrNull(0), parts.take(2).takeIf { it.size == 2 }?.joinToString("/"))
            }
            .filterNot { folder -> folder.startsWith("99_Private") || folder.split('/').any { it.startsWith(".") } }
            .distinct()
            .sorted()
            .toList()
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
