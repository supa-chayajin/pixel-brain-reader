package cloud.wafflecommons.pixelbrainreader.data.ai

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cloud.wafflecommons.pixelbrainreader.data.local.dao.EmbeddingDao
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
    private val embeddingDao: EmbeddingDao
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val isFullReindex = inputData.getBoolean("FULL_REINDEX", false)
            
            if (isFullReindex) {
                Log.w("Cortex", "🧹 FULL RE-INDEX TRIGGERED: Wiping Neural Database...")
                embeddingDao.deleteAll()
            }

            // Fix: Use appContext.filesDir directly or check if there is a 'vault' subdir?
            // The previous code had `File("")` which was wrong, but user snippet says `appContext.filesDir`
            // Let's assume files are in root of filesDir or handle it properly. 
            // The user snippet explicitly uses `appContext.filesDir`.
            val rootDir = appContext.filesDir
            if (!rootDir.exists()) return@withContext Result.failure()

            val markdownFiles = rootDir.walkTopDown()
                .filter { it.isFile && it.extension == "md" }
                .toList()

            Log.i("Cortex", "🧠 Indexing ${markdownFiles.size} files...")

            var count = 0
            markdownFiles.forEach { file ->
                if (!file.name.startsWith(".")) {
                    try {
                        indexFile(file)
                        count++
                    } catch (e: Exception) {
                        Log.e("Cortex", "Failed to index ${file.name}", e)
                    }
                }
            }
            
            Log.i("Cortex", "✅ Indexing Complete ($count files processed)")
            Result.success()
        } catch (e: Exception) {
            Log.e("Cortex", "Indexing Critical Failure", e)
            Result.failure()
        }
    }

    private suspend fun indexFile(file: File) {
        val text = file.readText()
        if (text.isBlank()) return
        val cleanContent = text.replace(Regex("^---[\\s\\S]*?---"), "").trim()
        if (cleanContent.isBlank()) return

        val chunks = cleanContent.split(Regex("(?m)^(?=#{1,3}\\s)")).filter { it.isNotBlank() }
        
        // Even in full re-index, defensive delete ensures no duplicates if logic changes
        embeddingDao.deleteByFileId(file.path)

        chunks.forEach { chunk ->
            val vector = vectorSearchEngine.embed(chunk) // Use existing robust engine
            val entity = EmbeddingEntity(
                id = UUID.randomUUID().toString(),
                fileId = file.path,
                content = chunk.trim(),
                vector = vector.toList(),
                lastUpdated = System.currentTimeMillis()
            )
            embeddingDao.insert(entity)
        }
    }
}
