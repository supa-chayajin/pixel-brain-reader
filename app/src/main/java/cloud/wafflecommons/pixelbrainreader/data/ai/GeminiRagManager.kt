package cloud.wafflecommons.pixelbrainreader.data.ai

import cloud.wafflecommons.pixelbrainreader.data.ai.stub.GenerativeModelStub
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeminiRagManager @Inject constructor(
    private val vectorSearchEngine: VectorSearchEngine
) {
    // Using Stub until AI Core SDK is available
    private val model = GenerativeModelStub()

    suspend fun askQuestion(query: String): Flow<String> {
        // 1. Retrieve Context
        val relevantContexts = vectorSearchEngine.findRelevantContext(query)
        val contextBlock = relevantContexts.joinToString("\n\n")

        // 2. Construct Prompt
        val finalPrompt = """
            Context:
            $contextBlock
            
            Question:
            $query
            
            Answer the question based ONLY on the context provided above.
        """.trimIndent()

        // 3. Generate (Stream)
        return model.generateContentStream(finalPrompt)
    }
}
