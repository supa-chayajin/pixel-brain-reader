package cloud.wafflecommons.pixelbrainreader.data.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.room.withTransaction
import cloud.wafflecommons.pixelbrainreader.data.repository.ChoreRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.VaultDiscoveryRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@HiltWorker
class IndexingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val vaultDiscoveryRepository: VaultDiscoveryRepository,
    private val moodRepository: MoodRepository,
    private val habitRepository: HabitRepository,
    private val choreRepository: ChoreRepository,
    private val vectorSearchEngine: cloud.wafflecommons.pixelbrainreader.data.ai.VectorSearchEngine,
    private val embeddingDao: cloud.wafflecommons.pixelbrainreader.data.local.dao.EmbeddingDao,
    private val fileDao: cloud.wafflecommons.pixelbrainreader.data.local.dao.FileDao,
    private val database: cloud.wafflecommons.pixelbrainreader.data.local.AppDatabase,
    private val userPrefs: cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.i("IndexingWorker", "Starting Smart Delta Indexing")
            val isFullReindex = inputData.getBoolean("FULL_REINDEX", false)
            
            // 1. Get Delta Timestamp
            val lastTime = if (isFullReindex) 0L else userPrefs.lastIndexTime.first()
            val startTime = System.currentTimeMillis()
            val embeddingsBefore = runCatching { embeddingDao.count() }.getOrDefault(-1)
            Log.i(
                "RAG_DEBUG",
                "IndexingWorker start: fullReindex=$isFullReindex lastTime=$lastTime " +
                    "embeddings_in_db=$embeddingsBefore"
            )

            // Trigger Mood/Habit Sync in parallel (Background)
            // They are independent of the file scan for now, or at least we don't block Vault Scan on them.
            // But user requirement says: "Step A: Scan filesystem... Once Step A is 100% complete... Step C: Generate embeddings"

            // Step A: Vault Scan (Updates FileEntity Table)
            Log.d("IndexingWorker", "Step A: Starting Delta Vault Scan (since $lastTime)...")
            val deltaFiles = try {
                 val changes = vaultDiscoveryRepository.reindexAll(lastTime)
                 // Prune Orphans explicitly
                 embeddingDao.deleteOrphans()
                 changes
            } catch (e: Exception) {
                 Log.e("IndexingWorker", "Vault Scan Failed", e)
                 emptyList()
            }

            // Step A.5: Backfill — pick up indexed files that have NO embeddings yet.
            // The delta above never sees them: their lastModified() is older than
            // lastIndexTime, so reindexAll skips them. This is the path that heals a
            // cold embeddings table after a destructive Room migration or after the
            // embedding pipeline was first enabled on an existing install.
            val missingFiles = try {
                fileDao.getFilesWithoutEmbeddings()
            } catch (e: Exception) {
                Log.e("IndexingWorker", "Backfill scan failed", e)
                emptyList()
            }
            Log.i(
                "RAG_DEBUG",
                "IndexingWorker scan: delta=${deltaFiles.size} backfill=${missingFiles.size}"
            )

            // Combine + dedupe — a file modified now AND missing embeddings should
            // be embedded only once this run.
            val targetFiles = (deltaFiles + missingFiles).distinctBy { it.path }

            // Step B & C: Verified Embedding
            if (targetFiles.isNotEmpty()) {
                 Log.d(
                     "IndexingWorker",
                     "Step B/C: ${targetFiles.size} file(s) to embed (delta=${deltaFiles.size} + " +
                         "backfill=${missingFiles.size}). Processing..."
                 )
                 processEmbeddings(targetFiles)
            } else {
                 Log.d("IndexingWorker", "No file changes and no backfill needed.")
            }

            // Sync Moods/Habits (Can run after or parallel, let's await them for "Indexing Complete" correctness)
            val otherSyncs = listOf(
                async { try { moodRepository.syncWithFileSystem() } catch (e: Exception) { Log.e("IndexingWorker", "Mood Sync Failed", e) } },
                async { try { habitRepository.syncWithFileSystem() } catch (e: Exception) { Log.e("IndexingWorker", "Habit Sync Failed", e) } },
                async { try { choreRepository.syncWithFileSystem() } catch (e: Exception) { Log.e("IndexingWorker", "Chore Sync Failed", e) } }
            )
            otherSyncs.awaitAll()
            
            // Update Timestamp only if successful
            userPrefs.setLastIndexTime(startTime)

            val embeddingsAfter = runCatching { embeddingDao.count() }.getOrDefault(-1)
            Log.i(
                "RAG_DEBUG",
                "IndexingWorker done: embeddings_in_db ${embeddingsBefore} -> ${embeddingsAfter}"
            )
            Log.i("IndexingWorker", "Indexing Complete")
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
            it.path.endsWith(".md", ignoreCase = true) &&
            it.path != todayPath &&                  // SHIELD: skip today's active journal
            !it.path.contains("99_System") &&        // SHIELD: system isolation
            !it.path.contains("99_Private") &&       // SHIELD: private isolation
            !it.path.endsWith(".enc", ignoreCase = true)
        }
        Log.i("RAG_DEBUG", "processEmbeddings: ${files.size} changed file(s) -> ${candidates.size} embedding candidate(s) after filter")

        var totalChunks = 0
        var totalEmbedded = 0
        var totalInserted = 0
        var skippedFiles = 0

        candidates.forEach { entity ->
            try {
                val file = java.io.File(vaultRoot, entity.path)
                if (!file.exists()) {
                    Log.w("RAG_DEBUG", "  skip ${entity.path}: file not on disk")
                    skippedFiles++
                    return@forEach
                }
                val content = file.readText()
                if (content.isBlank()) {
                    Log.d("RAG_DEBUG", "  skip ${entity.path}: blank content")
                    skippedFiles++
                    return@forEach
                }

                val chunks = chunkText(content)
                Log.d("RAG_DEBUG", "  ${entity.path}: ${chunks.size} chunk(s) from ${content.length} chars")
                totalChunks += chunks.size

                // Embed OUTSIDE any DB transaction. TFLite inference can take
                // seconds per chunk; holding the Room write lock open across
                // dozens of chunks would starve every other DAO call.
                val embeddingEntities = chunks.mapNotNull { chunk ->
                    try {
                        val vector = vectorSearchEngine.embed(chunk)
                        totalEmbedded++
                        cloud.wafflecommons.pixelbrainreader.data.local.entity.EmbeddingEntity(
                            fileId = entity.path,
                            content = chunk,
                            vector = vector.toList()
                        )
                    } catch (e: Exception) {
                        Log.w("RAG_DEBUG", "  embed failed for a chunk of ${entity.path}: ${e.message}")
                        null
                    }
                }

                if (embeddingEntities.isEmpty()) {
                    Log.w("RAG_DEBUG", "  ${entity.path}: 0 embeddings produced — skipping DB write")
                    return@forEach
                }

                // Small atomic write per file: drop old chunks, insert new ones.
                // Suspending withTransaction so we don't block on inference inside the txn.
                database.withTransaction {
                    embeddingDao.deleteByFileId(entity.path)
                    embeddingEntities.forEach { embeddingDao.insert(it) }
                }
                totalInserted += embeddingEntities.size
                Log.d("RAG_DEBUG", "  ${entity.path}: inserted ${embeddingEntities.size} embedding(s)")
            } catch (e: Exception) {
                Log.e("RAG_DEBUG", "Failed to embed ${entity.path}", e)
                // Continue to next file
            }
        }

        Log.i(
            "RAG_DEBUG",
            "processEmbeddings done: chunks=$totalChunks embedded=$totalEmbedded inserted=$totalInserted skippedFiles=$skippedFiles"
        )
    }
    
    private fun chunkText(text: String, windowSize: Int = 1000, overlap: Int = 200): List<String> {
        if (text.length <= windowSize) return listOf(text)
        
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = (start + windowSize).coerceAtMost(text.length)
            chunks.add(text.substring(start, end))
            start += (windowSize - overlap)
        }
        return chunks
    }
}
