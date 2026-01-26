package cloud.wafflecommons.pixelbrainreader.data.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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
            
            // Trigger Mood/Habit Sync in parallel (Background)
            // They are independent of the file scan for now, or at least we don't block Vault Scan on them.
            // But user requirement says: "Step A: Scan filesystem... Once Step A is 100% complete... Step C: Generate embeddings"
            
            // Step A: Vault Scan (Updates FileEntity Table)
            Log.d("IndexingWorker", "Step A: Starting Delta Vault Scan (since $lastTime)...")
            val changedFiles = try {
                 // Safety Prune: Ensure no orphans exist from previous crashes (Wait for Step A first? No, before is safer? Or after?)
                 // Prompt says: "Once Step A is 100% complete... retrieve list... Generate... ONLY for paths verified"
                 // And "In the DAO, add a method to delete all embeddings where the filePath does not exist"
                 // Let's run it AFTER Step A to clean up anything that Step A might have deleted (via Cascade anyway), 
                 // but specifically to clean up old mess.
                 
                 val changes = vaultDiscoveryRepository.reindexAll(lastTime)
                 
                 // Prune Orphans explicitly
                 embeddingDao.deleteOrphans()
                 
                 changes
            } catch (e: Exception) {
                 Log.e("IndexingWorker", "Vault Scan Failed", e)
                 emptyList()
            }
            
            // Step B & C: Verified Embedding
            if (changedFiles.isNotEmpty()) {
                 Log.d("IndexingWorker", "Step B/C: Found ${changedFiles.size} potential changes. Processing Embeddings...")
                 processEmbeddings(changedFiles)
            } else {
                 Log.d("IndexingWorker", "No file changes detected.")
            }

            // Sync Moods/Habits (Can run after or parallel, let's await them for "Indexing Complete" correctness)
            val otherSyncs = listOf(
                async { try { moodRepository.syncWithFileSystem() } catch (e: Exception) { Log.e("IndexingWorker", "Mood Sync Failed", e) } },
                async { try { habitRepository.syncWithFileSystem() } catch (e: Exception) { Log.e("IndexingWorker", "Habit Sync Failed", e) } }
            )
            otherSyncs.awaitAll()
            
            // Update Timestamp only if successful
            userPrefs.setLastIndexTime(startTime)
            
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
        
        files.filter { 
            it.type == "file" && 
            it.path.endsWith(".md", ignoreCase = true) &&
            it.path != todayPath // SHIELD: Skip today's active journal
        }.forEach { entity ->
            try {
                // Per-File Transaction

                
                // REPLACING WITH ROBUST BLOCKING TRANSACTION
                database.runInTransaction {
                     if (!fileDao.existsBlocking(entity.path)) {
                         Log.w("IndexingWorker", "Skipping embedding for ${entity.path}: File not found in DB.")
                         return@runInTransaction
                     }
                     
                     val file = java.io.File(vaultRoot, entity.path)
                     if (!file.exists()) return@runInTransaction
                     
                     val content = file.readText()
                     if (content.isBlank()) return@runInTransaction
                     
                     embeddingDao.deleteByFileIdBlocking(entity.path)
                     
                     val chunks = chunkText(content)
                     chunks.forEach { chunk ->
                         val vector = vectorSearchEngine.embed(chunk)
                         val embeddingEntity = cloud.wafflecommons.pixelbrainreader.data.local.entity.EmbeddingEntity(
                            fileId = entity.path,
                            content = chunk,
                            vector = vector.toList()
                         )
                         embeddingDao.insertBlocking(embeddingEntity)
                     }
                }
            } catch (e: Exception) {
                Log.e("IndexingWorker", "Failed to embed ${entity.path}", e)
                // Continue to next file
            }
        }
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
