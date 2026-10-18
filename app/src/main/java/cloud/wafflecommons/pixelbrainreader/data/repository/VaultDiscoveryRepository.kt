package cloud.wafflecommons.pixelbrainreader.data.repository

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import cloud.wafflecommons.pixelbrainreader.data.local.AppDatabase
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileSearchDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.LinkEntity
import cloud.wafflecommons.pixelbrainreader.data.search.Bm25
import cloud.wafflecommons.pixelbrainreader.data.search.FtsQuery
import cloud.wafflecommons.pixelbrainreader.data.search.FullTextHit
import cloud.wafflecommons.pixelbrainreader.data.utils.FrontmatterManager
import cloud.wafflecommons.pixelbrainreader.data.utils.VaultFileIndex
import cloud.wafflecommons.pixelbrainreader.data.vault.LinkExtractor
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Responsible for File System Discovery and Indexing (SQLite).
 * Scans the vault and keeps the DB in sync.
 */
@Singleton
class VaultDiscoveryRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val fileDao: FileDao,
    private val fileSearchDao: FileSearchDao,
    private val database: AppDatabase,
    private val gson: Gson // For JSON serialization of simplified metadata
) {

    private val rootDir: File
        get() = File(context.filesDir, "vault")

    /**
     * Serializes full scans. Cold start, the IndexingWorker, sync phases and file operations can all
     * request one at the same moment; running them concurrently duplicated the vault walk and let a
     * scan with a stale filesystem snapshot delete rows another scan had just inserted.
     */
    private val reindexMutex = Mutex()

    fun getAllFilesFlow(path: String?): Flow<List<FileEntity>> {
        // Existing DAO getFiles(path) filters by parent folder ("" = vault root).
        return fileDao.getFiles(path ?: "")
    }

    /**
     * In-app search: file and folder names first, then note bodies ranked by BM25 through the
     * full-text index (accent-insensitive, prefix terms). Re-runs whenever the vault changes while
     * the search is open.
     */
    fun searchFiles(query: String): Flow<List<FileEntity>> = fileSearchDao.observeChanges()
        .map { searchNow(query) }
        .flowOn(Dispatchers.IO)

    suspend fun searchNow(query: String): List<FileEntity> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()
        val byName = fileDao.searchByName(escapeLike(trimmed), NAME_MATCH_LIMIT)
        val hits = fullTextHits(trimmed, BODY_MATCH_LIMIT)
        val byBody = if (hits.isEmpty()) {
            emptyList()
        } else {
            val rows = fileDao.getFilesByPaths(hits.map { it.path }).associateBy { it.path }
            hits.mapNotNull { rows[it.path] }
        }
        (byName + byBody).distinctBy { it.path }
    }

    /** Ranked full-text hits with an excerpt (`«…»` marks the matched words). */
    suspend fun fullTextHits(query: String, limit: Int = BODY_MATCH_LIMIT): List<FullTextHit> = withContext(Dispatchers.IO) {
        val match = FtsQuery.build(query) ?: return@withContext emptyList()
        val rows = try {
            fileSearchDao.match(match, FTS_CANDIDATES)
        } catch (e: android.database.sqlite.SQLiteException) {
            Log.w("VaultDiscovery", "Full-text query failed: ${e.message}")
            return@withContext emptyList()
        }
        rows.map { FullTextHit(it.path, Bm25.score(Bm25.decode(it.matchInfo), FTS_COLUMN_WEIGHTS), it.excerpt) }
            .sortedByDescending { it.score }
            .take(limit)
    }

    private fun escapeLike(raw: String): String =
        raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /**
     * Attachment index for `![[embeds]]`, built off the main thread from the file table. It is
     * re-emitted only when the SET of attachment paths changes — every note save touches the
     * `files` table, and rebuilding (and re-parsing every open note) on each save would be waste.
     */
    fun observeAttachmentIndex(): Flow<VaultFileIndex> = fileDao.getAllFiles()
        .map { files ->
            files.asSequence()
                .filter { it.type == "file" && VaultFileIndex.isAttachment(it.path) }
                .map { it.path }
                .sorted()
                .toList()
        }
        .distinctUntilChanged()
        .map { VaultFileIndex(it) }
        .catch { e ->
            // Without the index, embeds only fall back to vault paths; never crash a screen over it.
            Log.w("VaultDiscovery", "Attachment index unavailable: ${e.message}")
            emit(VaultFileIndex.EMPTY)
        }
        .flowOn(Dispatchers.Default)

    /** Every directory path in the vault, for wikilink folder resolution. */
    suspend fun getAllFolderPaths(): List<String> = withContext(Dispatchers.IO) {
        fileDao.getAllFolderPaths()
    }

    /**
     * Resolve an Obsidian link target (`[[Note]]`, `[[folder/Note]]`, `[[Folder]]`, or a
     * relative markdown path) to a real vault entity. Mirrors Obsidian's resolution order:
     * exact path first, then `.md` extension, then a case-insensitive match on the basename
     * or path suffix anywhere in the vault. Returns null if nothing matches.
     */
    suspend fun resolveLink(target: String): FileEntity? = withContext(Dispatchers.IO) {
        val clean = target.trim().removePrefix("/").removeSuffix("/")
        if (clean.isEmpty()) return@withContext null

        // 1. Direct path hit (with and without an implicit .md extension).
        fileDao.getFile(clean)?.let { return@withContext it }
        if (!clean.contains('.')) {
            fileDao.getFile("$clean.md")?.let { return@withContext it }
        }

        // 2. Basename / path-suffix match across the whole vault.
        val candidates = if (clean.endsWith(".md")) listOf(clean) else listOf(clean, "$clean.md")
        val matchPath = fileDao.getAllPaths().firstOrNull { path ->
            candidates.any { c ->
                path.equals(c, ignoreCase = true) ||
                    path.endsWith("/$c", ignoreCase = true) ||
                    path.substringAfterLast('/').equals(c, ignoreCase = true)
            }
        }
        matchPath?.let { fileDao.getFile(it) }
    }

    /**
     * Full delta scan of the vault into the `files` table. Returns the entities whose content
     * actually changed (new or different fingerprint). [sinceTimestamp] is kept for API
     * compatibility; the scan always relies on mtime + content fingerprints.
     */
    suspend fun reindexAll(sinceTimestamp: Long = 0L): List<FileEntity> = withContext(Dispatchers.IO) {
        reindexMutex.withLock { scanAndApply() }
    }

    private suspend fun scanAndApply(): List<FileEntity> {
        val start = System.currentTimeMillis()
        if (!rootDir.exists()) rootDir.mkdirs()

        // 1. Snapshot existing FileEntity rows (path + sha + mtime).
        val dbFilesMap = fileDao.getAllFileFingerprints().associateBy { it.path }
        val dbPaths = dbFilesMap.keys

        // 2. Walk FS
        val fsPaths = mutableSetOf<String>()
        val changedFiles = mutableListOf<FileEntity>()
        val newFiles = mutableListOf<FileEntity>()       // Upserted
        val newContents = mutableListOf<cloud.wafflecommons.pixelbrainreader.data.local.entity.FileContentEntity>()
        val mtimeRefreshes = mutableListOf<Pair<String, Long>>() // UPDATE-only (no CASCADE)

        // Prune at descent time, NOT inside the loop. `walkTopDown().forEach`
        // with a name check is `continue`, not `prune` — by the time we react,
        // the walker has already queued every child of `.git`. Using `.onEnter`
        // keeps the entire subtree out of the scan in the first place.
        rootDir.walkTopDown().onEnter { dir ->
            dir.name !in EXCLUDED_DIRS
        }.forEach { file ->
            val relativePath = file.relativeTo(rootDir).path
            if (relativePath.isEmpty()) return@forEach

            fsPaths.add(relativePath)

            val dbEntity = dbFilesMap[relativePath]
            val onDiskMtime = file.lastModified()

            if (dbEntity == null) {
                // NEW FILE — must compute fingerprint and insert.
                val (entity, contentEntity) = createFileEntityWithContent(file, relativePath)
                newFiles.add(entity)
                contentEntity?.let { newContents.add(it) }
                changedFiles.add(entity)
                return@forEach
            }

            // EXISTING FILE — fast path: mtime unchanged ⇒ content unchanged.
            if (dbEntity.localModifiedTimestamp == onDiskMtime && dbEntity.sha != null) {
                return@forEach
            }

            // mtime drifted (or sha was null from a legacy row): compare content fingerprints.
            val (newEntity, contentEntity) = createFileEntityWithContent(file, relativePath)
            if (newEntity.sha != null && newEntity.sha == dbEntity.sha) {
                // Content identical — bump stored mtime via UPDATE so we hit the fast path next
                // time. NOT a REPLACE, so the FK CASCADE on EmbeddingEntity does not fire and the
                // existing embeddings for this file are preserved.
                mtimeRefreshes.add(relativePath to onDiskMtime)
                return@forEach
            }

            // Content actually changed — upsert row and delete stale embeddings so IndexingWorker
            // re-embeds it on its next pass.
            newFiles.add(newEntity)
            contentEntity?.let { newContents.add(it) }
            changedFiles.add(newEntity)
        }

        val toDelete = dbPaths.minus(fsPaths)

        // 3. Apply all changes in ONE transaction = ONE invalidation, so every Flow observing
        // `files` (file browser, search) re-queries once instead of once per changed row.
        if (newFiles.isNotEmpty() || mtimeRefreshes.isNotEmpty() || toDelete.isNotEmpty()) {
            val contentDao = runCatching { database.fileContentDao() }.getOrNull()
            val embeddingDao = runCatching { database.embeddingDao() }.getOrNull()
            val linkDao = runCatching { database.linkDao() }.getOrNull()
            // Parsed outside the transaction: the write lock is held only for the inserts.
            val newLinks = newContents.flatMap { linksOf(it.path, it.content) }
            database.withTransaction {
                if (newFiles.isNotEmpty()) {
                    fileDao.insertAll(newFiles)
                    if (newContents.isNotEmpty()) {
                        contentDao?.saveContents(newContents)
                        newContents.map { it.path }.chunked(DELETE_CHUNK_SIZE).forEach { linkDao?.deleteBySources(it) }
                        if (newLinks.isNotEmpty()) linkDao?.insertAll(newLinks)
                    }
                    // Clear stale embeddings for files whose content SHA changed.
                    changedFiles.filter { it.path in dbPaths }.forEach { changed ->
                        embeddingDao?.deleteByFileId(changed.path)
                    }
                }
                mtimeRefreshes.forEach { (path, mtime) -> fileDao.updateMtime(path, mtime) }
                toDelete.chunked(DELETE_CHUNK_SIZE).forEach { chunk ->
                    fileDao.deleteFiles(chunk)
                    contentDao?.deleteContents(chunk)
                    linkDao?.deleteBySources(chunk)
                }
            }
        }
        if (newFiles.isNotEmpty()) Log.i("VaultDiscovery", "Indexed ${newFiles.size} new/modified items.")
        if (toDelete.isNotEmpty()) Log.i("VaultDiscovery", "Pruned ${toDelete.size} deleted items.")

        Log.d(
            "VaultDiscovery",
            "Scan took ${System.currentTimeMillis() - start}ms. Found ${changedFiles.size} actually-changed item(s); " +
                "${mtimeRefreshes.size} mtime-only drift(s) absorbed."
        )

        return changedFiles
    }

    suspend fun reindexFileSystem() {
        // Legacy overload
        reindexAll(0L)
    }

    suspend fun scanSingleFile(path: String) = withContext(Dispatchers.IO) {
        // Lightweight update for single file
        val file = File(rootDir, path)
        val contentDao = runCatching { database.fileContentDao() }.getOrNull()
        val linkDao = runCatching { database.linkDao() }.getOrNull()
        if (file.exists()) {
            val (entity, contentEntity) = createFileEntityWithContent(file, path)
            fileDao.insertFile(entity) // Upsert (does not cascade-delete embeddings)
            if (contentEntity != null) {
                contentDao?.saveContent(contentEntity)
                val links = linksOf(path, contentEntity.content)
                linkDao?.deleteBySources(listOf(path))
                if (links.isNotEmpty()) linkDao?.insertAll(links)
            }
        } else {
            fileDao.deleteFiles(listOf(path))
            contentDao?.deleteContents(listOf(path))
            linkDao?.deleteBySources(listOf(path))
        }
    }

    private fun linksOf(path: String, content: String): List<LinkEntity> =
        LinkExtractor.extract(content).map {
            LinkEntity(source = path, target = it.target, targetKey = it.targetKey, kind = it.kind, line = it.line, context = it.context)
        }

    private fun createFileEntityWithContent(
        file: File,
        path: String
    ): Pair<FileEntity, cloud.wafflecommons.pixelbrainreader.data.local.entity.FileContentEntity?> {
        var tags: String? = null
        var metaBlob: String? = null
        var contentSha: String? = null
        var contentEntity: cloud.wafflecommons.pixelbrainreader.data.local.entity.FileContentEntity? = null

        if (file.isFile && file.extension.equals("md", ignoreCase = true)) {
            try {
                val content = file.reader().use { it.readText() }

                val metadata = FrontmatterManager.extractMetadata(content)
                if (metadata.tags.isNotEmpty()) {
                    tags = metadata.tags.joinToString(",")
                }
                val extra = mutableMapOf<String, Any>()
                if (metadata.aliases.isNotEmpty()) extra["aliases"] = metadata.aliases
                if (extra.isNotEmpty()) {
                    metaBlob = gson.toJson(extra)
                }

                contentSha = sha1Of(content.toByteArray(Charsets.UTF_8))
                if (!path.startsWith("99_Private")) {
                    contentEntity = cloud.wafflecommons.pixelbrainreader.data.local.entity.FileContentEntity(
                        path = path,
                        content = content,
                        lastSyncedAt = file.lastModified()
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Leave metadata empty; the reindex falls back to mtime comparison.
            }
        } else if (file.isFile) {
            // Binary or encrypted file (e.g. .md.enc). Stream in 8 KB chunks to avoid OOM on large files.
            try {
                contentSha = sha1OfFile(file)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Leave SHA null; the reindex falls back to mtime comparison.
            }
        }

        val entity = FileEntity(
            path = path,
            name = file.name,
            type = if (file.isDirectory) "dir" else "file",
            sha = contentSha,
            downloadUrl = null,
            isDirty = true,
            localModifiedTimestamp = file.lastModified(),
            tags = tags,
            rawMetadata = metaBlob
        )
        return entity to contentEntity
    }

    private companion object {
        /** Directories never indexed: git internals + Obsidian app state. */
        val EXCLUDED_DIRS = setOf(
            ".git",
            ".obsidian",
            ".trash",
            ".devtool",
            ".idea",
            ".opencode",
        )

        /** Stay well under SQLite's bound-variable limit for `DELETE … WHERE path IN (…)`. */
        const val DELETE_CHUNK_SIZE = 500

        const val NAME_MATCH_LIMIT = 30
        const val BODY_MATCH_LIMIT = 50
        /** Full-text rows fetched before BM25 ranking (the MATCH itself is unordered). */
        const val FTS_CANDIDATES = 300
        /** `file_contents_fts` columns: path (≈ the title) counts double, the body once. */
        val FTS_COLUMN_WEIGHTS = doubleArrayOf(2.0, 1.0)

        val HEX_DIGITS = "0123456789abcdef".toCharArray()
    }

    /** Lower-case hex SHA-1 (same output as the old per-byte String.format, without its cost). */
    private fun sha1Of(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(bytes)
        return digestToHex(digest)
    }

    /** Streams [file] in 8 KB blocks so large binary attachments never allocate a full-file ByteArray. */
    private fun sha1OfFile(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                md.update(buf, 0, read)
            }
        }
        return digestToHex(md.digest())
    }

    private fun digestToHex(digest: ByteArray): String {
        val out = CharArray(digest.size * 2)
        digest.forEachIndexed { i, b ->
            val v = b.toInt() and 0xFF
            out[i * 2] = HEX_DIGITS[v ushr 4]
            out[i * 2 + 1] = HEX_DIGITS[v and 0x0F]
        }
        return String(out)
    }
}
