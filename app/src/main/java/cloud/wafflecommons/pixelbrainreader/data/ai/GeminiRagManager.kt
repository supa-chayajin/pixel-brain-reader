package cloud.wafflecommons.pixelbrainreader.data.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import cloud.wafflecommons.pixelbrainreader.BuildConfig

@Singleton
class GeminiRagManager @Inject constructor(
    private val vectorSearchEngine: VectorSearchEngine
) {
    // Real Gemini Model with requested config
    private val model by lazy {
        GenerativeModel(
            modelName = "gemini-2.5-flash-lite",
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
            val response = model.generateContent(prompt)
            response.text ?: "AI returned no content."
        } catch (e: Exception) {
            "Analysis Failed: ${e.localizedMessage}. Please check API Key or Model Name."
        }
    }

    // Legacy/Pivot Placeholder
    suspend fun askQuestion(query: String): Flow<String> {
        // ... kept for compatibility but practically replaced by Scribe/FolderInsight
        val relevantContexts = vectorSearchEngine.findRelevantContext(query)
        val contextBlock = relevantContexts.joinToString("\n\n")
        val finalPrompt = "Context:\n$contextBlock\n\nQuestion:\n$query\nAnswer based on context."
        return model.generateContentStream(finalPrompt).map { 
            it.text ?: ""
        }
    }
}

