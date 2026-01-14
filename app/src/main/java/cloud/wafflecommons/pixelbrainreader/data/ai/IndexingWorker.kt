package cloud.wafflecommons.pixelbrainreader.data.ai

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cloud.wafflecommons.pixelbrainreader.data.local.dao.EmbeddingDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.EmbeddingEntity
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.firstOrNull

@HiltWorker
class IndexingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: FileRepository,
    private val embeddingDao: EmbeddingDao,
    private val vectorSearchEngine: VectorSearchEngine
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val filePath = inputData.getString(KEY_FILE_PATH) ?: return Result.failure()
        
        // 1. Read Content
        // We use the repository to get the confirmed local content
        val content = repository.getFileContentFlow(filePath).firstOrNull()
        if (content.isNullOrBlank()) {
            return Result.success() // Nothing to index
        }

        // 2. Chunking Strategy (Split by Headers or Paragraphs)
        // For better RAG, splitting by headers (#) gives more semantic contexts than just newlines.
        // Falls back to double newline if no headers found.
        val chunks = if (content.contains("#")) {
             content.split(Regex("(?=^# )|(?=^## )|(?=^### )", RegexOption.MULTILINE))
                 .filter { it.isNotBlank() }
        } else {
            content.split("\n\n").filter { it.isNotBlank() }
        }

        // 2.5 Ensure Model File Ready (Copy-to-Cache Strategy)
        // We replicate this logic here to ensure the file exists before invoking engine
        try {
            val file = java.io.File(applicationContext.cacheDir, "universal_sentence_encoder.tflite")
            
            // A. Purge Corrupted
            if (file.exists() && file.length() < 1024) {
                 android.util.Log.w("Cortex", "IndexingWorker: Corrupted cached model. Deleting...")
                 file.delete()
            }
            
            // B. Copy if needed
            if (!file.exists()) {
                 android.util.Log.d("Cortex", "IndexingWorker: Copying universal_sentence_encoder.tflite to cache...")
                 applicationContext.assets.open("universal_sentence_encoder.tflite").use { inputStream ->
                     if (inputStream.available() < 1024) {
                         throw java.io.IOException("Asset is corrupted/empty.")
                     }
                     file.outputStream().use { outputStream ->
                         inputStream.copyTo(outputStream)
                     }
                 }
            }
        } catch (e: Exception) {
             android.util.Log.e("Cortex", "IndexingWorker: Model copy failed.", e)
             return Result.failure()
        }

        // 3. Generate Embeddings using MediaPipe
        val embeddings = chunks.mapNotNull { chunk ->
            try {
                // Generate Embedding calling the Engine
                val vectorFloat = vectorSearchEngine.embed(chunk)
                
                EmbeddingEntity(
                    // id generated automatically (UUID)
                    fileId = filePath,
                    content = chunk.trim(),
                    vector = vectorFloat.toList(),
                    lastUpdated = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                // If embedding fails for one chunk, we skip it but Log it safe
                android.util.Log.w("Cortex", "Embedding failed for chunk: ${e.message}")
                null
            }
        }

        // 4. Store Valid Vectors
        // Clear old embeddings for this file first (Re-indexing)
        if (embeddings.isNotEmpty()) {
            embeddingDao.deleteEmbeddingsForFile(filePath)
            embeddingDao.insertAll(embeddings)
        }

        return Result.success()
    }

    companion object {
        const val KEY_FILE_PATH = "file_path"
    }
}
