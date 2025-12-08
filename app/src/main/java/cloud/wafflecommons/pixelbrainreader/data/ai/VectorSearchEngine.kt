package cloud.wafflecommons.pixelbrainreader.data.ai

import cloud.wafflecommons.pixelbrainreader.data.local.dao.EmbeddingDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Singleton
class VectorSearchEngine @Inject constructor(
    private val embeddingDao: EmbeddingDao
) {
    // Placeholder for actual MediaPipe TextEmbedder
    // In V4.0 Sprint 2, we simulate embedding generation.
    private fun embed(text: String): FloatArray {
        // TODO: Initialize MediaPipe TextEmbedder here using model asset
        // For now, return a random vector or a hash-based deterministic vector for testing
        val hash = text.hashCode().toFloat()
        return FloatArray(128) { i -> (hash + i) % 100 / 100f } // Mock Vector
    }

    suspend fun findRelevantContext(query: String): List<String> {
        val queryVector = embed(query)

        // V1 Naive Search: Fetch all embeddings and compute Cosine Similarity in-memory
        // Optimization: Filter by file if needed, or limit to recent files.
        val allEmbeddings = embeddingDao.getAllEmbeddings() 

        val topMatches = allEmbeddings.map { entity ->
            val similarity = cosineSimilarity(queryVector, toFloatArray(entity.vector))
            entity to similarity
        }
        .sortedByDescending { it.second }
        .take(3) // Top 3 Chunks

        return topMatches.map { it.first.textChunk }
    }

    private fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        if (v1.size != v2.size) return 0f
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f
        for (i in v1.indices) {
            dotProduct += v1[i] * v2[i]
            normA += v1[i] * v1[i]
            normB += v2[i] * v2[i]
        }
        return if (normA > 0 && normB > 0) dotProduct / (sqrt(normA) * sqrt(normB)) else 0f
    }

    // Helper to convert Blob (ByteArray) back to FloatArray
    // Assuming simple verification serialization for now (placeholder)
    private fun toFloatArray(byteArray: ByteArray): FloatArray {
        if (byteArray.isEmpty()) return FloatArray(128) // Fallback for Stub Indexing
        // Real logic would involve ByteBuffer or DataInputStream
        return FloatArray(128)
    }
}
