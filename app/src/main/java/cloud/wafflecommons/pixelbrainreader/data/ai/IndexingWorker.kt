package cloud.wafflecommons.pixelbrainreader.data.ai

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cloud.wafflecommons.pixelbrainreader.data.local.dao.EmbeddingDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.EmbeddingEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@HiltWorker
class IndexingWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val vectorSearchEngine: VectorSearchEngine,
    private val embeddingDao: EmbeddingDao,
    private val fileDao: FileDao
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val isFullReindex = inputData.getBoolean("FULL_REINDEX", false)
            
            if (isFullReindex) {
                Log.w("Cortex", "🧹 FULL RE-INDEX: Wiping Neural Memory...")
                embeddingDao.deleteAll()
            }

            val vaultDir = File(appContext.filesDir, "vault")
            if (!vaultDir.exists()) return@withContext Result.success()

            // Recursive scan for Markdown files
            val markdownFiles = vaultDir.walkTopDown()
                .filter { it.isFile && it.extension == "md" }
                .toList()

            Log.i("Cortex", "🧠 Indexing ${markdownFiles.size} notes...")

            var count = 0
            markdownFiles.forEach { file ->
                if (!file.name.startsWith(".")) { // Skip hidden files
                    try {
                        // FIX: Use relative path for fileId to match FileEntity.path and avoid FK constraint failure
                        val relativePath = file.relativeTo(vaultDir).path
                        
                        // Ensure the file exists in the files table before inserting embeddings
                        if (fileDao.existsBlocking(relativePath)) {
                            // PAUSE Indexing for AI
                            // indexFile(file, relativePath)
                            count++
                        } else {
                            Log.w("Cortex", "Skipping $relativePath: Not found in database index.")
                        }
                    } catch (e: Exception) {
                        Log.e("Cortex", "Failed to index ${file.name}", e)
                    }
                }
            }
            
            Log.i("Cortex", "✅ Indexing Complete. Processed $count files.")
            Result.success()
        } catch (e: Exception) {
            Log.e("Cortex", "Indexing Job Failed", e)
            Result.failure()
        }
    }

    private suspend fun indexFile(file: File, relativePath: String) {
        val text = file.readText()
        if (text.isBlank()) return
        
        // Remove Frontmatter (Metadata) to index only content
        val cleanContent = text.replace(Regex("^---[\\s\\S]*?---"), "").trim()
        if (cleanContent.isBlank()) return

        // Semantic Chunking by Headers
        val chunks = cleanContent.split(Regex("(?m)^(?=#{1,3}\\s)")).filter { it.isNotBlank() }
        
        // Always clear previous entries for this file to avoid duplicates
        embeddingDao.deleteByFileId(relativePath)

        chunks.forEach { chunk ->
            // Vectorize
            val vector = vectorSearchEngine.embed(chunk) 
            
            val entity = EmbeddingEntity(
                id = UUID.randomUUID().toString(),
                fileId = relativePath,
                content = chunk.trim(),
                vector = vector.toList(),
                lastUpdated = System.currentTimeMillis()
            )
            embeddingDao.insert(entity)
        }
    }
}
