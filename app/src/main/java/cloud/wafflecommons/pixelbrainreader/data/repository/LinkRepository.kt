package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.LinkDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.LinkEntity
import cloud.wafflecommons.pixelbrainreader.data.vault.LinkExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Backlinks and dead links, read from the links index the vault scan maintains. */
@Singleton
class LinkRepository @Inject constructor(
    private val linkDao: LinkDao,
    private val fileDao: FileDao
) {

    /** Notes linking to [path] (embeds included), each with the line that links. */
    suspend fun backlinks(path: String): List<LinkEntity> = withContext(Dispatchers.IO) {
        val key = LinkExtractor.keyOfPath(path)
        linkDao.linksTo(key, exceptSource = path).filter { pointsAt(it.target, path) }
    }

    /** Wiki and markdown links whose target matches no file in the vault. */
    suspend fun deadLinks(): List<LinkEntity> = withContext(Dispatchers.IO) {
        val keys = fileDao.getAllPaths().mapTo(HashSet()) { LinkExtractor.keyOfPath(it) }
        linkDao.allNoteLinks().filter { it.targetKey !in keys }
    }

    /** Sources already linking to [path], so unlinked mentions can leave them out. */
    suspend fun sourcesLinkingTo(path: String): Set<String> = withContext(Dispatchers.IO) {
        linkDao.sourcesLinkingTo(LinkExtractor.keyOfPath(path)).toSet()
    }

    companion object {
        /**
         * A target with a folder (`[[A/Note]]`) must match the end of the path; a bare name matches
         * any note with that basename, like Obsidian.
         */
        fun pointsAt(target: String, path: String): Boolean {
            val clean = target.trim().replace('\\', '/').trimStart('/')
            if (!clean.contains('/')) return true
            val withMd = if (clean.endsWith(".md", ignoreCase = true)) clean else "$clean.md"
            return path.equals(withMd, ignoreCase = true) || path.endsWith("/$withMd", ignoreCase = true) ||
                path.equals(clean, ignoreCase = true) || path.endsWith("/$clean", ignoreCase = true)
        }
    }
}
