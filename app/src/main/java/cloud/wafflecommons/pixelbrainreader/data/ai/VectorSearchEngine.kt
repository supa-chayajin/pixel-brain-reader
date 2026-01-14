package cloud.wafflecommons.pixelbrainreader.data.ai

import android.content.Context
import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.local.dao.EmbeddingDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.EmbeddingEntity
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Singleton
class VectorSearchEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val embeddingDao: EmbeddingDao
) {
    // Nullable safe instance
    private var textEmbedder: TextEmbedder? = null
    
    // Constant for model name to ensure consistency
    private val MODEL_NAME = "universal_sentence_encoder.tflite"

    /**
     * Safe Initialization Logic.
     * Prevents SIGSEGV by verifying asset existence before Native Init.
     */
    /**
     * Helper: Copy Asset to Cache (Crash Prevention Strategy).
     * Loading directly from assets often causes SIGSEGV with C++ libraries.
     */
    private fun setupModelFile(context: Context): String {
        val file = java.io.File(context.cacheDir, MODEL_NAME)
        
        // 1. Check existing file integrity
        if (file.exists() && file.length() < 1024) {
             Log.w("Cortex", "Cached model corrupted (<1KB). Deleting...")
             file.delete()
        }

        // 2. Copy if missing
        if (!file.exists()) {
             Log.d("Cortex", "Copying $MODEL_NAME to cache...")
             context.assets.open(MODEL_NAME).use { inputStream ->
                 if (inputStream.available() < 1024) {
                     throw java.io.IOException("Asset $MODEL_NAME is corrupted/empty (<1KB). Cannot copy.")
                 }
                 file.outputStream().use { outputStream ->
                     inputStream.copyTo(outputStream)
                 }
             }
        }
        
        // 3. Final Check
        if (file.length() < 1024) {
            file.delete()
            throw java.io.IOException("Model copy failed: Resulting file is too small.")
        }
        
        return file.absolutePath
    }

    /**
     * Safe Initialization Logic.
     * Prevents SIGSEGV by using File Path (Cache) instead of Asset FD.
     */
    private fun getEmbedderSafe(): TextEmbedder? {
        synchronized(this) {
            if (textEmbedder != null) return textEmbedder

            try {
                // 1. Copy Model to Cache (Robust Loading)
                val modelPath = try {
                     setupModelFile(context)
                } catch (e: Exception) {
                     Log.e("Cortex", "Failed to copy model to cache: ${e.message}")
                     return null
                }
                
                // 2. Safe Init inside Try-Catch
                Log.d("Cortex", "Initializing MediaPipe TextEmbedder from $modelPath")
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath(modelPath) // Path to Cache File
                    .build()
                
                val options = TextEmbedder.TextEmbedderOptions.builder()
                    .setBaseOptions(baseOptions)
                    .build()

                textEmbedder = TextEmbedder.createFromOptions(context, options)
                Log.d("Cortex", "MediaPipe Init Success")
            } catch (e: Exception) {
                Log.e("Cortex", "Native Init Failed", e)
                return null
            }
        }
        return textEmbedder
    }

    /**
     * Converts text to a FloatArray embedding using MediaPipe.
     * Throws exception if engine is not ready, to be caught by IndexingWorker.
     */
    suspend fun embed(text: String): FloatArray = withContext(Dispatchers.Default) {
        val embedder = getEmbedderSafe() ?: throw IllegalStateException("Vector Engine not ready (Asset missing or Init failed)")
        
        val embeddingResult = embedder.embed(text)
        val embedding = embeddingResult.embeddingResult().embeddings().first()
        embedding.floatEmbedding()
    }

    /**
     * Search for relevant notes using Cosine Similarity.
     * Returns empty list safely if engine is unavailable.
     */
    suspend fun search(query: String, limit: Int = 3): List<EmbeddingEntity> = withContext(Dispatchers.IO) {
        // 1. Safe Embed (Fail Soft)
        val embedder = getEmbedderSafe()
        if (embedder == null) {
            Log.w("Cortex", "Search skipped: Vector Engine not ready.")
            return@withContext emptyList()
        }

        val queryVector = try {
            val result = embedder.embed(query)
            result.embeddingResult().embeddings().first().floatEmbedding()
        } catch (e: Exception) {
            Log.w("Cortex", "Query embedding failed: ${e.message}")
            return@withContext emptyList()
        }

        // 2. Load all embeddings (Brute-force Local RAG)
        val allEmbeddings = embeddingDao.getAllEmbeddings()

        // 3. Calculate Similarity & Sort
        val results = allEmbeddings.map { entity ->
            val entityVector = entity.vector.toFloatArray() // List<Float> -> FloatArray
            val similarity = cosineSimilarity(queryVector, entityVector)
            entity to similarity
        }
        .sortedByDescending { it.second }
        .take(limit)
        .map { it.first }

        return@withContext results
    }

    private fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        if (v1.size != v2.size) return 0.0f
        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        
        for (i in v1.indices) {
            dotProduct += v1[i] * v2[i]
            normA += v1[i] * v1[i]
            normB += v2[i] * v2[i]
        }
        
        if (normA == 0.0f || normB == 0.0f) return 0.0f
        
        return dotProduct / (sqrt(normA) * sqrt(normB))
    }
}
