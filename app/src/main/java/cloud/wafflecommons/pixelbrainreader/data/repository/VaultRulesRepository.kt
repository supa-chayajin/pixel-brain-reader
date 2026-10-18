package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.ai.VectorSearchEngine
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileDao
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import cloud.wafflecommons.pixelbrainreader.data.utils.FrontmatterManager
import cloud.wafflecommons.pixelbrainreader.data.vault.FrontmatterLines
import cloud.wafflecommons.pixelbrainreader.data.vault.InboxFiling
import cloud.wafflecommons.pixelbrainreader.data.vault.LinkExtractor
import cloud.wafflecommons.pixelbrainreader.data.vault.LintIssue
import cloud.wafflecommons.pixelbrainreader.data.vault.VaultLint
import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import cloud.wafflecommons.pixelbrainreader.data.work.WorkNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Where an inbox note could go and the tags it could take. */
data class FilingSuggestion(val folders: List<String>, val tags: List<String>)

/** The vault's rules on the phone (E7): advisory lint for a note, and inbox filing (roadmap 2.6). */
@Singleton
class VaultRulesRepository @Inject constructor(
    private val fileDao: FileDao,
    private val fileContentDao: FileContentDao,
    private val vectorSearchEngine: VectorSearchEngine,
    private val fileRepository: FileRepository,
    private val pushScheduler: VaultPushScheduler
) {

    suspend fun lint(path: String, content: String): List<LintIssue> = withContext(Dispatchers.IO) {
        val keys = fileDao.getAllPaths().mapTo(HashSet()) { LinkExtractor.keyOfPath(it) }
        VaultLint.check(path, content, keys::contains)
    }

    /** The open note with the safe repairs applied (the caller saves it, like an edit). */
    fun fixed(path: String, content: String): String = VaultLint.fix(path, content, LocalDate.now())

    suspend fun inboxNotes(): List<WorkNote> = withContext(Dispatchers.IO) {
        fileContentDao.contentsUnder(VaultSchema.INBOX_FOLDER + "/")
            .filter { it.path.endsWith(".md") }
            .map { WorkNote.from(it.path, it.content) }
            .sortedByDescending { it.created ?: it.updated }
    }

    suspend fun suggest(note: WorkNote): FilingSuggestion = withContext(Dispatchers.IO) {
        val similar = try {
            vectorSearchEngine.search(note.title + "\n" + FrontmatterManager.stripFrontmatter(note.content).take(600), limit = 10)
                .map { it.fileId }.distinct().filter { it != note.path }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        val allPaths = fileDao.getAllPaths().filter { it.endsWith(".md") }
        val byKey = allPaths.groupBy { LinkExtractor.keyOfPath(it) }
        val linked = LinkExtractor.extract(note.content).mapNotNull { byKey[it.targetKey]?.firstOrNull() }
        val similarTags = similar.mapNotNull { path -> fileContentDao.getContent(path)?.let { FrontmatterLines.getList(it, "tags") } }
        FilingSuggestion(InboxFiling.folders(similar, linked), InboxFiling.tags(similarTags, note.tags))
    }

    /**
     * Files [note] into [folder]: type and inbox status follow the destination (R4), the chosen
     * [tags] are added, date_updated is today — one key at a time — then the note moves (a git
     * rename). Returns the new path.
     */
    suspend fun file(note: WorkNote, folder: String, tags: List<String>): String = withContext(Dispatchers.IO) {
        val target = "${folder.trim('/')}/${note.path.substringAfterLast('/')}"
        if (fileRepository.fileExists(target)) throw IllegalStateException("A note with this name is already in $folder.")
        val content = fileRepository.readFile(note.path) ?: throw IllegalStateException("The note is no longer there.")
        val type = VaultSchema.typeFor(target)
        var updated = content
        if (FrontmatterLines.hasFrontmatter(updated)) {
            updated = FrontmatterLines.set(updated, "type", type)
            if (FrontmatterLines.get(updated, "status")?.startsWith("📥") != false) {
                updated = FrontmatterLines.set(updated, "status", VaultSchema.defaultStatus(type))
            }
            val merged = (FrontmatterLines.getList(updated, "tags").filter { it.lowercase() != "inbox" } + tags.map(VaultSchema::normalizeTag)).distinct()
            updated = FrontmatterLines.setList(updated, "tags", merged)
            updated = FrontmatterLines.set(updated, "date_updated", VaultSchema.singleQuoted(LocalDate.now().toString()))
        }
        if (updated != content) fileRepository.saveFileLocally(note.path, updated)
        fileRepository.renameFileSafe(note.path, target).getOrThrow()
        pushScheduler.requestPush()
        target
    }
}
