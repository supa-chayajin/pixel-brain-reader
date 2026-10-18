package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.ai.VectorSearchEngine
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import cloud.wafflecommons.pixelbrainreader.data.utils.FrontmatterManager
import cloud.wafflecommons.pixelbrainreader.data.vault.FrontmatterLines
import cloud.wafflecommons.pixelbrainreader.data.vault.MentionFinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class NoteRef(val path: String, val title: String, val excerpt: String?)

/** A note's connections (E2): who links to it, who names it without linking, what reads close to it. */
data class Connections(val backlinks: List<NoteRef>, val mentions: List<NoteRef>, val related: List<NoteRef>)

@Singleton
class ConnectionsRepository @Inject constructor(
    private val linkRepository: LinkRepository,
    private val vaultDiscoveryRepository: VaultDiscoveryRepository,
    private val fileContentDao: FileContentDao,
    private val vectorSearchEngine: VectorSearchEngine,
    private val modesRepository: ModesRepository,
    private val fileRepository: FileRepository,
    private val pushScheduler: VaultPushScheduler
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
        Connections(backlinks, mentions, related)
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
