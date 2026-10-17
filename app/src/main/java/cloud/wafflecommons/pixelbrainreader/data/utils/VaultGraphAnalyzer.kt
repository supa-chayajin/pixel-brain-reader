package cloud.wafflecommons.pixelbrainreader.data.utils

import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

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
    /** Share of notes with neither a dead link nor orphan status, 0–100. */
    val healthScore: Int
)

/**
 * Wiki-link health of the vault: dead links (a `[[target]]` that matches no file) and orphan notes
 * (no link in or out; daily journal notes excluded).
 *
 * Embeds (`![[image.png]]`) are attachments, not links, and links may point at any vault file
 * (`[[spec.pdf]]`), so neither counts as dead. Links inside fenced code are ignored. The score is
 * the share of healthy notes — it used to subtract 3 points per dead link, so any vault with
 * images scored 0%.
 */
@Singleton
class VaultGraphAnalyzer @Inject constructor(
    private val fileDao: FileDao,
    private val fileContentDao: FileContentDao
) {
    // [[Target]], [[Target|Alias]], [[Target#Section]], [[Target#Section|Alias]]
    private val wikiLinkRegex = Regex("""\[\[([^\]|#]+)(?:#[^\]|]+)?(?:\|[^\]]+)?\]\]""")

    suspend fun analyze(): VaultGraphReport = withContext(Dispatchers.IO) {
        val files = (fileDao.getAllFiles().firstOrNull() ?: emptyList())
            .filter { it.type == "file" && it.path.split('/').none { segment -> segment.startsWith(".") } }
        val notes = files.filter { it.path.endsWith(".md") }
        if (notes.isEmpty()) {
            return@withContext VaultGraphReport(0, 0, emptyList(), emptyList(), healthScore = 100)
        }

        // Anything in the vault can be a link target: by path or by bare name, ".md" optional.
        val knownPaths = HashSet<String>()
        val knownNames = HashSet<String>()
        for (f in files) {
            knownPaths += f.path.lowercase()
            knownPaths += f.path.removeSuffix(".md").lowercase()
            knownNames += f.name.lowercase()
            knownNames += f.name.removeSuffix(".md").lowercase()
        }

        val incomingLinks = mutableMapOf<String, Int>()
        val outgoingLinks = mutableMapOf<String, Int>()
        val deadLinks = mutableListOf<DeadLink>()
        var totalLinks = 0

        for (note in notes) {
            val content = fileContentDao.getContent(note.path) ?: continue
            var inFence = false
            for (line in content.lines()) {
                val trimmed = line.trimStart()
                if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                    inFence = !inFence
                    continue
                }
                if (inFence) continue
                for (match in wikiLinkRegex.findAll(line)) {
                    if (match.range.first > 0 && line[match.range.first - 1] == '!') continue // embed
                    val rawTarget = match.groupValues[1].trim()
                    if (rawTarget.isBlank()) continue

                    totalLinks++
                    outgoingLinks[note.path] = (outgoingLinks[note.path] ?: 0) + 1
                    val target = rawTarget.lowercase()
                    val targetName = target.substringAfterLast('/')
                    if (target in knownPaths || targetName in knownNames) {
                        val key = targetName.removeSuffix(".md")
                        incomingLinks[key] = (incomingLinks[key] ?: 0) + 1
                    } else {
                        deadLinks += DeadLink(sourcePath = note.path, targetName = rawTarget, lineSnippet = line.trim().take(80))
                    }
                }
            }
        }

        val orphanNotes = notes.filter { note ->
            if (note.path.startsWith("10_Journal")) return@filter false
            val incoming = incomingLinks[note.name.removeSuffix(".md").lowercase()] ?: 0
            val outgoing = outgoingLinks[note.path] ?: 0
            incoming == 0 && outgoing == 0
        }.map { it.path }

        val unhealthy = (deadLinks.map { it.sourcePath } + orphanNotes).toSet()
        val score = (100.0 * (notes.size - unhealthy.size) / notes.size).roundToInt().coerceIn(0, 100)

        VaultGraphReport(
            totalNotesAnalyzed = notes.size,
            totalWikiLinksFound = totalLinks,
            deadLinks = deadLinks,
            orphanNotes = orphanNotes,
            healthScore = score
        )
    }
}
