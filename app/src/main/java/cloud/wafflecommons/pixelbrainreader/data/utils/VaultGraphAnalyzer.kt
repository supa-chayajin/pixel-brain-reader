package cloud.wafflecommons.pixelbrainreader.data.utils

import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class DeadLink(
    val sourcePath: String,
    val targetName: String,
    val lineSnippet: String = ""
)

data class VaultGraphReport(
    val totalNotesAnalyzed: Int,
    val totalWikiLinksFound: Int,
    val deadLinks: List<DeadLink>,
    val orphanNotes: List<String>,
    val healthScore: Int
)

/**
 * Knowledge Graph Entropy & Dead-Link Visualizer.
 * Analyzes Obsidian [[WikiLinks]] across the second-brain vault to detect:
 * 1. Broken / Dead Links (pointing to non-existent notes).
 * 2. Orphan Notes (notes with 0 incoming and 0 outgoing links).
 * 3. Overall Vault Graph Health Score (0-100%).
 */
@Singleton
class VaultGraphAnalyzer @Inject constructor(
    private val fileDao: FileDao,
    private val fileContentDao: FileContentDao
) {
    // Matches [[TargetNote]], [[TargetNote|Alias]], [[TargetNote#Section]], [[TargetNote#Section|Alias]]
    private val wikiLinkRegex = Regex("""\[\[([^\]|#]+)(?:#[^\]|]+)?(?:\|[^\]]+)?\]\]""")

    suspend fun analyze(): VaultGraphReport = withContext(Dispatchers.IO) {
        val allFiles = fileDao.getAllFiles().firstOrNull() ?: emptyList()
        val markdownFiles = allFiles.filter {
            it.type == "file" &&
            it.path.endsWith(".md") &&
            !it.path.endsWith(".enc") &&
            !it.path.startsWith(".")
        }

        if (markdownFiles.isEmpty()) {
            return@withContext VaultGraphReport(
                totalNotesAnalyzed = 0,
                totalWikiLinksFound = 0,
                deadLinks = emptyList(),
                orphanNotes = emptyList(),
                healthScore = 100
            )
        }

        // Canonical note lookups (case-insensitive title and path)
        val availableNames = mutableSetOf<String>()
        val availablePaths = mutableSetOf<String>()
        for (f in markdownFiles) {
            val title = f.name.removeSuffix(".md").lowercase()
            availableNames.add(title)
            availablePaths.add(f.path.removeSuffix(".md").lowercase())
        }

        val incomingLinks = mutableMapOf<String, Int>()
        val outgoingLinks = mutableMapOf<String, Int>()
        val deadLinks = mutableListOf<DeadLink>()
        var totalLinks = 0

        for (file in markdownFiles) {
            val content = fileContentDao.getContent(file.path) ?: continue
            val lines = content.lines()
            var fileLinkCount = 0

            for (line in lines) {
                val matches = wikiLinkRegex.findAll(line)
                for (match in matches) {
                    val rawTarget = match.groupValues[1].trim()
                    if (rawTarget.isBlank()) continue

                    fileLinkCount++
                    totalLinks++

                    val normalizedTarget = rawTarget.substringAfterLast("/").removeSuffix(".md").lowercase()
                    val targetPath = rawTarget.removeSuffix(".md").lowercase()

                    val targetExists = availableNames.contains(normalizedTarget) || availablePaths.contains(targetPath)
                    if (targetExists) {
                        incomingLinks[normalizedTarget] = (incomingLinks[normalizedTarget] ?: 0) + 1
                    } else {
                        deadLinks.add(
                            DeadLink(
                                sourcePath = file.path,
                                targetName = rawTarget,
                                lineSnippet = line.trim().take(80)
                            )
                        )
                    }
                }
            }
            outgoingLinks[file.path] = fileLinkCount
        }

        // Orphan notes: notes with 0 incoming and 0 outgoing links (excluding daily journal)
        val orphanNotes = markdownFiles.filter { file ->
            if (file.path.startsWith("10_Journal")) return@filter false
            val title = file.name.removeSuffix(".md").lowercase()
            val incoming = incomingLinks[title] ?: 0
            val outgoing = outgoingLinks[file.path] ?: 0
            incoming == 0 && outgoing == 0
        }.map { it.path }

        // Health score calculation
        val deadPenalty = deadLinks.size * 3
        val orphanPenalty = orphanNotes.size * 2
        val score = (100 - (deadPenalty + orphanPenalty)).coerceIn(0, 100)

        VaultGraphReport(
            totalNotesAnalyzed = markdownFiles.size,
            totalWikiLinksFound = totalLinks,
            deadLinks = deadLinks,
            orphanNotes = orphanNotes,
            healthScore = score
        )
    }
}
