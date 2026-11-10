package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.ai.VectorSearchEngine
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.remote.FileHistory
import cloud.wafflecommons.pixelbrainreader.data.remote.GitFileAges
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import cloud.wafflecommons.pixelbrainreader.data.utils.FrontmatterManager
import cloud.wafflecommons.pixelbrainreader.data.vault.FrontmatterLines
import cloud.wafflecommons.pixelbrainreader.data.vault.MentionFinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class NoteRef(val path: String, val title: String, val excerpt: String?)

/**
 * A note's connections (E2): who links to it, who names it without linking, what reads close to
 * it, and its life in git (C15) — null while it has never been committed.
 */
data class Connections(
    val backlinks: List<NoteRef>,
    val mentions: List<NoteRef>,
    val related: List<NoteRef>,
    val history: NoteHistory? = null
)

/** A note's life in git (C15): when it first appeared, when it last changed, how many commits touched it. */
data class NoteHistory(val firstSeen: Long, val lastChanged: Long, val commits: Int) {

    /** One line for the Connections sheet, dates in [zone]. */
    fun summary(zone: ZoneId): String {
        val first = DATE.format(Instant.ofEpochMilli(firstSeen).atZone(zone))
        val last = DATE.format(Instant.ofEpochMilli(lastChanged).atZone(zone))
        val count = if (commits == 1) "1 commit" else "$commits commits"
        return if (first == last) "Since $first · $count" else "Since $first · last changed $last · $count"
    }

    companion object {
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

        /** From the cached git walk ([GitFileAges]); null while [path] has never been committed. */
        fun of(path: String, history: FileHistory): NoteHistory? {
            val first = history.firstSeen[path] ?: return null
            return NoteHistory(first, history.lastChanged[path] ?: first, history.changes[path] ?: 1)
        }
    }
}

@Singleton
class ConnectionsRepository @Inject constructor(
    private val linkRepository: LinkRepository,
    private val vaultDiscoveryRepository: VaultDiscoveryRepository,
    private val fileContentDao: FileContentDao,
    private val vectorSearchEngine: VectorSearchEngine,
    private val modesRepository: ModesRepository,
    private val fileRepository: FileRepository,
    private val pushScheduler: VaultPushScheduler,
    private val gitFileAges: GitFileAges
) {

    suspend fun connections(path: String): Connections = withContext(Dispatchers.IO) {
        val visibility = modesRepository.currentVisibility()
        val content = fileContentDao.getContent(path) ?: fileRepository.readFile(path).orEmpty()
        val names = names(path, content)

        val backlinks = linkRepository.backlinks(path)
            .filter { visibility.isVisible(it.source) }
            .distinctBy { it.source }
            .map { NoteRef(it.source, titleOf(it.source), it.context.trim().ifEmpty { null }) }
        val linking = linkRepository.sourcesLinkingTo(path) + path

        val mentions = vaultDiscoveryRepository.fullTextHits(names.first(), limit = MENTION_CANDIDATES)
            .map { it.path }
            .filter { it !in linking && visibility.isVisible(it) }
            .mapNotNull { candidate ->
                val text = fileContentDao.getContent(candidate) ?: return@mapNotNull null
                MentionFinder.firstMention(text, names)?.let { NoteRef(candidate, titleOf(candidate), it.take(EXCERPT)) }
            }
            .take(MAX_ITEMS)

        val related = try {
            val query = names.first() + "\n" + FrontmatterManager.stripFrontmatter(content).take(RELATED_QUERY_CHARS)
            vectorSearchEngine.search(query, limit = MAX_ITEMS + 4)
                .map { it.fileId }
                .distinct()
                .filter { it !in linking && it !in mentions.map(NoteRef::path) }
                .take(MAX_ITEMS)
                .map { NoteRef(it, titleOf(it), null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        // The git walk is cached (incremental after the first one); a failure only hides the line.
        val history = try {
            NoteHistory.of(path, gitFileAges.history())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        Connections(backlinks, mentions, related, history)
    }

    /** Turns the first unlinked mention in [mention] into a link to [path]. */
    suspend fun linkMention(mention: NoteRef, path: String): Boolean = withContext(Dispatchers.IO) {
        val name = path.substringAfterLast('/').removeSuffix(".md")
        val content = fileRepository.readFile(mention.path) ?: return@withContext false
        val linked = MentionFinder.link(content, name) ?: return@withContext false
        fileRepository.saveFileLocally(mention.path, linked)
        pushScheduler.requestPush()
        true
    }

    /** The note's names: its file name first, then its title and aliases. */
    private fun names(path: String, content: String): List<String> =
        (listOf(path.substringAfterLast('/').removeSuffix(".md")) +
            listOfNotNull(FrontmatterLines.get(content, "title")) +
            FrontmatterLines.getList(content, "aliases"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun titleOf(path: String): String = path.substringAfterLast('/').removeSuffix(".md").removeSuffix(".enc")

    private companion object {
        const val MENTION_CANDIDATES = 60
        const val MAX_ITEMS = 8
        const val EXCERPT = 160
        const val RELATED_QUERY_CHARS = 600
    }
}
