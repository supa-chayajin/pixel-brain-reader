package cloud.wafflecommons.pixelbrainreader.data.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import cloud.wafflecommons.pixelbrainreader.BuildConfig

@Singleton
class GeminiRagManager @Inject constructor(
    private val vectorSearchEngine: VectorSearchEngine,
    private val userPrefs: cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
) {
    
    private suspend fun getModel(): GenerativeModel {
        val modelName = userPrefs.llmModelName.first()
        return GenerativeModel(
            modelName = modelName,
            apiKey = BuildConfig.geminiApiKey
        )
    }

    suspend fun analyzeFolder(files: List<Pair<String, String>>): String {
        if (files.isEmpty()) return "No files to analyze in this folder."

        // Construct Prompt with File Contexts
        val fileContexts = files.joinToString("\n---\n") { (name, content) ->
            "File: $name\nContent:\n${content.take(2000)}" // Truncate per file
        }

        val userLanguage = java.util.Locale.getDefault().displayLanguage

        val prompt = """
            You are an expert archivist.
            Context: The user's system language is $userLanguage.
            Task: Analyze the following files and generate a comprehensive summary in Markdown format.
            Output Language: STRICTLY $userLanguage.

            Files:
            $fileContexts
            
            Task Details:
            1. Generate a comprehensive Markdown summary of this folder.
            2. Create a Table of Contents.
            3. Provide a brief summary of each file's key points.
            4. Identify connections or themes between files.
            
            Output strictly in Markdown.
        """.trimIndent()

        return try {
            val response = getModel().generateContent(prompt)
            response.text ?: "AI returned no content."
        } catch (e: Exception) {
            "Analysis Failed: ${e.localizedMessage}. Please check API Key or Model Name."
        }
    }

    // Legacy RAG method replaced by generateResponse


    // Expose retrieving sources for UI
    suspend fun findSources(query: String): List<String> {
        return vectorSearchEngine.search(query).map { it.fileId }.distinct()
    }

    /**
     * Gemini Nano (Edge) Generation with RAG support.
     * Function: generateResponse
     */
    suspend fun generateResponse(userMessage: String, useRAG: Boolean = true): Flow<String> {
        var prompt = userMessage
        
        if (useRAG) {
            // 1. Retrieve Context
            val relevantDocs = vectorSearchEngine.search(userMessage, limit = 3)
            
            if (relevantDocs.isNotEmpty()) {
                val contextBlock = relevantDocs.joinToString("\n\n") { 
                    it.content
                }

                // 2. Build RAG Prompt
                prompt = """
                    CONTEXT:
                    $contextBlock
                    
                    USER QUESTION: $userMessage
                    
                    INSTRUCTION: Answer using ONLY the context above. If the answer is not there, say you don't know.
                """.trimIndent()
            }
        }

        // 3. Generate Stream (Simulating Nano via GenerativeModel for now, 
        // as direct aicore dependency requires specific setup not present in build.gradle)
        return try {
            // 3. Generate Stream (Simulating Nano via GenerativeModel for now)
             getModel().generateContentStream(prompt).map { 
                it.text ?: ""
            }
        } catch (e: Exception) {
            android.util.Log.e("Cortex", "Gemini Generation Failed", e)
             kotlinx.coroutines.flow.flowOf("Cortex Error: Gemini Nano n'est pas encore prêt. Laissez le téléphone en charge sur Wi-Fi. Details: ${e.message}")
        }
    }
}

