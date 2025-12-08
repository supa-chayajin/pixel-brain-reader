package cloud.wafflecommons.pixelbrainreader.data.ai.stub

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A Stub implementation of GenerativeModel to allow compilation and UI testing
 * while the com.google.ai.edge.aicore dependency is unavailable.
 */
class GenerativeModelStub {
    
    fun generateContentStream(prompt: String): Flow<String> = flow {
        // Simulate thinking time
        delay(500)
        
        emit("Thinking...\n")
        delay(800)

        // Mock Response based on prompt keywords
        val response = when {
            prompt.contains("persona: Tech Writer", ignoreCase = true) -> 
                "**Tech Writer Mode**\nHere is a structured technical breakdown of the requested topic. The architecture relies on modular components..."
            prompt.contains("persona: Coder", ignoreCase = true) -> 
                "**Coder Mode**\n```kotlin\nfun main() {\n    println(\"Hello Neural Vault!\")\n}\n```\nHere is the code snippet you requested."
            prompt.contains("Context:", ignoreCase = true) ->
                "**RAG Response**\nBased on your documents, I found relevant information. The vector search returned context indicating that 'Pixel Brain' is a privacy-first reader."
            else -> 
                "I am the Neural Scribe. I see you asked: \"$prompt\". How can I assist further?"
        }

        // Stream the response
        response.split(" ").forEach { word ->
            emit("$word ")
            delay(50)
        }
    }
}
