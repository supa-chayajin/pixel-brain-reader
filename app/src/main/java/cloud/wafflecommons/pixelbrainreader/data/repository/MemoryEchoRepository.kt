package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileEntity
import cloud.wafflecommons.pixelbrainreader.data.model.MemoryEcho
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class MemoryEchoRepository @Inject constructor(
    private val fileDao: FileDao,
    private val fileContentDao: FileContentDao,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val appClock: AppClock
) {
    /**
     * Resurfaces 1 to 3 decaying notes from projects, areas, or architecture documentation.
     * Ephemeral journal notes (10_Journal), encrypted notes (99_Private), and git metadata are excluded.
     * Uses a deterministic daily rotation combined with note age.
     */
    suspend fun getDailyEchoes(limit: Int = 3): List<MemoryEcho> = withContext(Dispatchers.IO) {
        val allFiles = fileDao.getAllFiles().firstOrNull() ?: emptyList()
        val now = System.currentTimeMillis()
        val todayEpochDay = appClock.today.value.toEpochDay()
        val reviewedMap = getReviewedTimestamps()

        val candidates = allFiles.filter { entity ->
            isValidEchoCandidate(entity, now, reviewedMap)
        }

        if (candidates.isEmpty()) return@withContext emptyList()

        // Sort by decay score (age + pseudo-random daily hash seed)
        val sorted = candidates.sortedByDescending { entity ->
            val mtime = entity.localModifiedTimestamp ?: 0L
            val days = (now - mtime) / (1000L * 60 * 60 * 24)
            val dailyHash = abs((entity.path + todayEpochDay).hashCode() % 1000)
            days * 1000 + dailyHash
        }.take(limit)

        sorted.map { entity ->
            val mtime = entity.localModifiedTimestamp ?: now
            val days = maxOf(0L, (now - mtime) / (1000L * 60 * 60 * 24))
            val rawContent = fileContentDao.getContent(entity.path) ?: ""
            val snippet = cleanSnippet(rawContent)
            val badge = extractCategoryBadge(entity.path)
            val title = entity.name.removeSuffix(".md")
            val urgency = when {
                days >= 60 -> "Perle oubliée (${days}j)"
                days >= 30 -> "Rappel d'architecture (${days}j)"
                else -> "À rafraîchir (${days}j)"
            }

            MemoryEcho(
                path = entity.path,
                title = title,
                categoryBadge = badge,
                daysSinceModified = days,
                snippet = snippet,
                urgencyLabel = urgency
            )
        }
    }

    /**
     * Mark an echo as reviewed so it will be suppressed from daily resurfacing for 14 days.
     */
    suspend fun markEchoReviewed(path: String) = withContext(Dispatchers.IO) {
        userPreferencesRepository.recordEchoReviewed(path)
    }

    private suspend fun getReviewedTimestamps(): Map<String, Long> {
        return try {
            val raw = userPreferencesRepository.reviewedEchoes.firstOrNull() ?: return emptyMap()
            raw.split(",")
                .filter { it.contains(":") }
                .associate {
                    val parts = it.split(":")
                    parts[0] to (parts.getOrNull(1)?.toLongOrNull() ?: 0L)
                }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun isValidEchoCandidate(
        entity: FileEntity,
        now: Long,
        reviewedMap: Map<String, Long>
    ): Boolean {
        if (entity.type != "file") return false
        if (!entity.path.endsWith(".md")) return false
        if (entity.path.endsWith(".enc")) return false
        if (entity.path.startsWith("10_Journal")) return false
        if (entity.path.startsWith("99_Private")) return false
        if (entity.path.startsWith(".")) return false

        val mtime = entity.localModifiedTimestamp ?: return false
        val days = (now - mtime) / (1000L * 60 * 60 * 24)
        if (days < 14) return false

        // Check if reviewed recently (< 14 days ago)
        val lastReviewed = reviewedMap[entity.path]
        if (lastReviewed != null && (now - lastReviewed) < (14L * 24 * 60 * 60 * 1000)) {
            return false
        }

        return true
    }

    private fun extractCategoryBadge(path: String): String {
        val segments = path.split("/").filter { it.isNotBlank() }
        return when {
            segments.size > 2 -> segments[1] // e.g. "20_Projects/21_Waffle_Framework/doc.md" -> "21_Waffle_Framework"
            segments.size > 1 -> segments[0] // e.g. "20_Projects/doc.md" -> "20_Projects"
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
}
