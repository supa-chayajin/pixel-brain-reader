package cloud.wafflecommons.pixelbrainreader.data.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.room.withTransaction
import cloud.wafflecommons.pixelbrainreader.data.local.security.CryptoManager
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.repository.VaultDiscoveryRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * RAG embedding worker.
 *
 * Enqueued by Settings → "Index Knowledge Vault" AND automatically on app start
 * (PixelBrainApplication.scheduleVaultIndexing, unique + KEEP). Each run:
 *   1. Reindexes the vault filesystem into the `files` Room table and returns the
 *      set of files whose content fingerprint changed since the last scan (delta).
 *   2. Unions that delta with markdown files that have no embedding row yet
 *      (getFilesWithoutEmbeddings backfill), and embeds + stores chunks for them.
 *      No mood/habit/chore reconcile — that's handled elsewhere
 *      (PixelBrainApplication.runStartupReconcile + SyncOrchestrator Phase 4).
 *
 * When there is nothing to embed it returns early WITHOUT loading the model, so
 * the startup enqueue is a cheap no-op once the index is warm. This is what
 * rebuilds embeddings automatically after a destructive Room migration or an
 * EMBEDDER_SCHEMA_VERSION bump (both wipe the embeddings table).
 */
@HiltWorker
class IndexingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val vaultDiscoveryRepository: VaultDiscoveryRepository,
    private val vectorSearchEngine: cloud.wafflecommons.pixelbrainreader.data.ai.VectorSearchEngine,
    private val embeddingDao: cloud.wafflecommons.pixelbrainreader.data.local.dao.EmbeddingDao,
    private val fileDao: cloud.wafflecommons.pixelbrainreader.data.local.dao.FileDao,
    private val database: cloud.wafflecommons.pixelbrainreader.data.local.AppDatabase,
    private val userPrefs: cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository,
    private val cryptoManager: CryptoManager,
    private val secretManager: SecretManager
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val startTime = System.currentTimeMillis()
            val embeddingsBefore = runCatching { embeddingDao.count() }.getOrDefault(-1)
            val lastTime = userPrefs.lastIndexTime.first()
            val lastTimeHuman = if (lastTime == 0L) "never" else java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                java.util.Locale.US
            ).format(java.util.Date(lastTime))
            Log.i(
                "RAG_DEBUG",
                "IndexingWorker start: lastTime=$lastTime ($lastTimeHuman) embeddings_in_db=$embeddingsBefore"
            )

            // Apply any embedder schema-version migration (cheap — a pref check +
            // table wipe, no model load) BEFORE the backfill query below, so a
            // bumped EMBEDDER_SCHEMA_VERSION wipes stale embeddings first and
            // getFilesWithoutEmbeddings then returns everything to re-embed.
            vectorSearchEngine.ensureSchemaVersion()

            // Vault scan: reindexAll uses content-SHA comparison and returns ONLY
            // files whose stored fingerprint differs from on-disk content. Files
            // whose mtime drifted but content matched are absorbed via UPDATE
            // (no CASCADE) and not returned here.
            val deltaFiles = try {
                val changes = vaultDiscoveryRepository.reindexAll(lastTime)
                embeddingDao.deleteOrphans()
                changes
            } catch (e: Exception) {
                Log.e("IndexingWorker", "Vault scan failed", e)
                emptyList()
            }

            Log.i("RAG_DEBUG", "IndexingWorker: ${deltaFiles.size} file(s) changed since last index")

            // Backfill: union the delta with markdown files that exist in the
            // `files` table but have NO embedding row. Without this, files
            // indexed by a previous worker run that died before embedding
            // (e.g. when the .git walker drowned us in dir entries) stay
            // permanently unembedded — their mtime never drifts, so the
            // delta scan keeps skipping them. The DAO query already exists
            // for exactly this case; it just wasn't wired here.
            val unembedded = try {
                fileDao.getFilesWithoutEmbeddings()
            } catch (e: Exception) {
                Log.w("RAG_DEBUG", "getFilesWithoutEmbeddings failed: ${e.message}")
                emptyList()
            }
            val unionFiles = (deltaFiles + unembedded).distinctBy { it.path }
            Log.i(
                "RAG_DEBUG",
                "IndexingWorker: delta=${deltaFiles.size} unembedded-backfill=${unembedded.size} " +
                    "union=${unionFiles.size}"
            )

            // Nothing to embed → return WITHOUT loading the ~470MB model, so the
            // now-automatic startup enqueue is a cheap no-op once the index is warm.
            if (unionFiles.isEmpty()) {
                Log.d("IndexingWorker", "No file changes and no unembedded backlog — nothing to embed.")
                userPrefs.setLastIndexTime(startTime)
                return@withContext Result.success()
            }

            // Fail-fast: probe the embedder before embedding. A broken init would
            // otherwise turn every chunk into a per-chunk catch — the job "succeeds"
            // but inserts zero embeddings, which looks like "indexing is broken".
            val probe = try {
                vectorSearchEngine.embed("vector engine readiness probe")
            } catch (e: Exception) {
                Log.e("RAG_DEBUG", "IndexingWorker ABORT: vector engine init failed: ${e.message}", e)
                return@withContext Result.failure()
            }
            Log.i("RAG_DEBUG", "IndexingWorker: embedder OK (dim=${probe.size})")

            processEmbeddings(unionFiles)

            userPrefs.setLastIndexTime(startTime)

            val embeddingsAfter = runCatching { embeddingDao.count() }.getOrDefault(-1)
            Log.i(
                "RAG_DEBUG",
                "IndexingWorker done: embeddings_in_db ${embeddingsBefore} -> ${embeddingsAfter}"
            )
            Result.success()
        } catch (e: Exception) {
            Log.e("IndexingWorker", "Fatal Indexing Error", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private suspend fun processEmbeddings(files: List<cloud.wafflecommons.pixelbrainreader.data.local.entity.FileEntity>) {
        val vaultRoot = java.io.File(applicationContext.filesDir, "vault")
        val todayStr = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_DATE)
        val todayPath = "10_Journal/$todayStr.md"

        val candidates = files.filter {
            it.type == "file" &&
            (it.path.endsWith(".md", ignoreCase = true) ||
                it.path.endsWith(".md.enc", ignoreCase = true)) &&
            it.path != todayPath &&                  // SHIELD: skip today's active journal
            !it.path.contains("99_System")           // SHIELD: system isolation
        }
        Log.i("RAG_DEBUG", "processEmbeddings: ${files.size} changed file(s) -> ${candidates.size} embedding candidate(s) after filter")

        // Visibility for the "269 in, 0 out" failure mode: print a breakdown of
        // what was rejected so we don't have to grep blind ever again.
        if (candidates.isEmpty() && files.isNotEmpty()) {
            val byType = files.groupingBy { it.type }.eachCount()
            val mdCount = files.count { it.path.endsWith(".md", ignoreCase = true) }
            val mdEncCount = files.count { it.path.endsWith(".md.enc", ignoreCase = true) }
            val droppedToday = files.count { it.path == todayPath }
            val droppedSystem = files.count { it.path.contains("99_System") }
            val firstFive = files.take(5).map { "${it.type}:${it.path}" }
            Log.d(
                "RAG_DEBUG",
                "processEmbeddings: 0 candidates from ${files.size} changed items.  " +
                    "byType=$byType  .md=$mdCount  .md.enc=$mdEncCount  todaySkipped=$droppedToday  " +
                    "systemSkipped=$droppedSystem.  First 5: $firstFive"
            )
        }

        // Cache the vault password ONCE per worker run. Returns null when the
        // user has never unlocked the vault on this install; in that case all
        // private files are skipped with a log marker (idempotent — they'll
        // be picked up next time the user re-presses the button after unlock).
        val vaultPassword = secretManager.getVaultPassword()

        var totalChunks = 0
        var totalEmbedded = 0
        var totalInserted = 0
        var skippedFiles = 0
        var privateProcessed = 0
        var privateSkippedLocked = 0

        candidates.forEach { entity ->
            try {
                val isPrivate = entity.path.startsWith("99_Private/") ||
                    entity.path.endsWith(".md.enc", ignoreCase = true)

                if (isPrivate && vaultPassword.isNullOrBlank()) {
                    Log.d("RAG_DEBUG", "  skip ${entity.path}: vault locked (no password cached)")
                    privateSkippedLocked++
                    skippedFiles++
                    return@forEach
                }

                val file = java.io.File(vaultRoot, entity.path)
                if (!file.exists()) {
                    Log.d("RAG_DEBUG", "  skip ${entity.path}: file not on disk")
                    skippedFiles++
                    return@forEach
                }

                val plaintext = if (isPrivate) {
                    val pwd = vaultPassword!!.toCharArray()
                    try {
                        cryptoManager.decrypt(file.readBytes(), pwd)
                    } catch (e: Exception) {
                        Log.d("RAG_DEBUG", "  decrypt failed for ${entity.path}: ${e.message}")
                        skippedFiles++
                        return@forEach
                    } finally {
                        java.util.Arrays.fill(pwd, ' ')
                    }
                } else {
                    file.readText()
                }

                if (plaintext.isBlank()) {
                    Log.d("RAG_DEBUG", "  skip ${entity.path}: blank content")
                    skippedFiles++
                    return@forEach
                }

                val chunks = chunkText(plaintext)
                Log.d("RAG_DEBUG", "  ${entity.path}: ${chunks.size} chunk(s) from ${plaintext.length} chars (private=$isPrivate)")
                totalChunks += chunks.size

                // Embed OUTSIDE any DB transaction. TFLite inference can take
                // seconds per chunk; holding the Room write lock open across
                // dozens of chunks would starve every other DAO call.
                val embeddingEntities = chunks.mapNotNull { chunk ->
                    try {
                        val vector = vectorSearchEngine.embed(chunk)
                        totalEmbedded++

                        val storedContent = if (isPrivate) {
                            val pwd = vaultPassword!!.toCharArray()
                            val ciphertext = try {
                                cryptoManager.encrypt(chunk, pwd)
                            } finally {
                                java.util.Arrays.fill(pwd, ' ')
                            }
                            android.util.Base64.encodeToString(ciphertext, android.util.Base64.NO_WRAP)
                        } else {
                            chunk
                        }

                        cloud.wafflecommons.pixelbrainreader.data.local.entity.EmbeddingEntity(
                            fileId = entity.path,
                            content = storedContent,
                            vector = vector.toList(),
                            isPrivate = isPrivate
                        )
                    } catch (e: Exception) {
                        Log.d("RAG_DEBUG", "  embed/encrypt failed for a chunk of ${entity.path}: ${e.message}")
                        null
                    }
                }

                if (embeddingEntities.isEmpty()) {
                    Log.d("RAG_DEBUG", "  ${entity.path}: 0 embeddings produced — skipping DB write")
                    return@forEach
                }

                database.withTransaction {
                    embeddingDao.deleteByFileId(entity.path)
                    embeddingEntities.forEach { embeddingDao.insert(it) }
                }
                totalInserted += embeddingEntities.size
                if (isPrivate) privateProcessed++
                Log.d("RAG_DEBUG", "  ${entity.path}: inserted ${embeddingEntities.size} embedding(s) (private=$isPrivate)")
            } catch (e: Exception) {
                Log.d("RAG_DEBUG", "Failed to embed ${entity.path}", e)
            }
        }

        Log.i(
            "RAG_DEBUG",
            "processEmbeddings done: chunks=$totalChunks embedded=$totalEmbedded inserted=$totalInserted " +
                "privateProcessed=$privateProcessed privateSkippedLocked=$privateSkippedLocked skippedFiles=$skippedFiles"
        )
    }

    /**
     * Sentence/paragraph-aware chunker. The TFLite embedder truncates at
     * MAX_SEQ_LEN (256 tokens); the old fixed 1000-char window routinely exceeded
     * that, so each chunk's tail was silently dropped from its vector. We now split
     * on sentence terminators + blank lines and greedily pack units into
     * ~[targetSize]-char chunks that stay within the token window and don't cut
     * mid-thought. A single oversized unit is hard-split as a fallback.
     */
    private fun chunkText(text: String, targetSize: Int = 400): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.length <= targetSize) return listOf(trimmed)

        val units = trimmed
            .split(Regex("(?<=[.!?。！？])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (unit in units) {
            // Hard-split any single unit that alone exceeds the target.
            if (unit.length > targetSize) {
                if (current.isNotEmpty()) {
                    chunks.add(current.toString().trim())
                    current.setLength(0)
                }
                var s = 0
                while (s < unit.length) {
                    val e = (s + targetSize).coerceAtMost(unit.length)
                    chunks.add(unit.substring(s, e))
                    s = e
                }
                continue
            }
            if (current.isNotEmpty() && current.length + unit.length + 1 > targetSize) {
                chunks.add(current.toString().trim())
                current.setLength(0)
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(unit)
        }
        if (current.isNotBlank()) chunks.add(current.toString().trim())
        return chunks
    }

    companion object {
        /** WorkManager unique-work name for the manual indexing button. */
        const val UNIQUE_WORK_NAME = "ManualVaultIndexing"
    }
}
