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
    private val embeddingDao: EmbeddingDao
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val filePath = inputData.getString(KEY_FILE_PATH) ?: return Result.failure()
        
        // 1. Read Content
        // We use the repository to get the confirmed local content
        val content = repository.getFileContentFlow(filePath).firstOrNull()
        if (content.isNullOrBlank()) {
            return Result.success() // Nothing to index
        }

        // 2. Chunking Strategy (Simple Paragraph/Window split for V4.0 Start)
        // TODO: Move to a dedicated TextSplitter class later
        val chunks = content.split("\n\n").filter { it.isNotBlank() }

        // 3. Generate Embeddings (Placeholder for Gemini Nano / MediaPipe)
        // In this Foundation Sprint, we just prepare the entities.
        val embeddings = chunks.mapIndexed { index, chunk ->
            EmbeddingEntity(
                filePath = filePath,
                chunkIndex = index,
                textChunk = chunk,
                vector = ByteArray(0) // EMPTY VECTOR - AI Integration in Sprint 2
            )
        }

        // 4. Store Valid Vectors
        // Clear old embeddings for this file first (Re-indexing)
        embeddingDao.deleteEmbeddingsForFile(filePath)
        embeddingDao.insertEmbeddings(embeddings)

        return Result.success()
    }

    companion object {
        const val KEY_FILE_PATH = "file_path"
    }
}
