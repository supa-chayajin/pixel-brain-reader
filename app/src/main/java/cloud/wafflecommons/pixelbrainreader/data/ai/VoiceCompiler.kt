package cloud.wafflecommons.pixelbrainreader.data.ai

import android.util.Log
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Singleton

data class CompiledVoiceResult(
    val rawTranscript: String,
    val structuredMarkdown: String,
    val detectedTasks: List<String>,
    val suggestedCategory: String
)

/**
 * Context-Aware Voice Compiler.
 * Translates raw, messy speech-to-text transcripts into structured Obsidian Markdown,
 * extracting actionable tasks `- [ ]` and classifying into Johnny Decimal categories.
 */
@Singleton
class VoiceCompiler @Inject constructor(
    private val geminiRagManager: GeminiRagManager
) {
    suspend fun compile(transcript: String): CompiledVoiceResult {
        if (transcript.isBlank()) {
            return CompiledVoiceResult(
                rawTranscript = "",
                structuredMarkdown = "",
                detectedTasks = emptyList(),
                suggestedCategory = "10_Journal"
            )
        }

        return try {
            val prompt = """
                Tu es le compilateur de notes vocales de PixelBrain (Life-OS & Obsidian Vault).
                Analyse la transcription vocale brute suivante et compile-la :
                1. Extrais toutes les actions ou tâches concrètes et formate-les sous forme de checklist : `- [ ] <description> #tag`
                2. Détermine la catégorie Johnny Decimal appropriée (10_Journal, 20_Projects, 30_Areas, 40_Resources).
                3. Structure le reste de la pensée sous forme de note Markdown claire et synthétique avec puces.
                
                Format strict de sortie :
                CATEGORY: <10_Journal|20_Projects|30_Areas|40_Resources>
                TASKS:
                - [ ] ...
                CONTENT:
                ...
                
                Transcription brute :
                "$transcript"
            """.trimIndent()

            val flow = geminiRagManager.generateResponse(prompt, useRAG = false)
            var response = ""
            flow.collect { text ->
                if (!text.startsWith("Thinking")) response = text
            }

            if (response.isBlank() || response.contains("indisponible", ignoreCase = true)) {
                fallbackCompile(transcript)
            } else {
                parseAiOutput(transcript, response)
            }
        } catch (e: Exception) {
            Log.w("VoiceCompiler", "AI compilation failed, falling back to heuristic parsing", e)
            fallbackCompile(transcript)
        }
    }

    private fun parseAiOutput(rawTranscript: String, aiOutput: String): CompiledVoiceResult {
        var category = "10_Journal"
        val tasks = mutableListOf<String>()
        val contentBuilder = StringBuilder()

        var currentSection = ""
        for (line in aiOutput.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("CATEGORY:", ignoreCase = true) -> {
                    category = trimmed.substringAfter(":").trim()
                }
                trimmed.equals("TASKS:", ignoreCase = true) -> {
                    currentSection = "TASKS"
                }
                trimmed.equals("CONTENT:", ignoreCase = true) -> {
                    currentSection = "CONTENT"
                }
                currentSection == "TASKS" && trimmed.startsWith("- [ ]") -> {
                    tasks.add(trimmed.removePrefix("- [ ]").trim())
                }
                currentSection == "CONTENT" -> {
                    if (contentBuilder.isNotEmpty()) contentBuilder.append("\n")
                    contentBuilder.append(line)
                }
            }
        }

        val structured = if (contentBuilder.isNotBlank()) {
            contentBuilder.toString().trim()
        } else {
            "- $rawTranscript"
        }

        return CompiledVoiceResult(
            rawTranscript = rawTranscript,
            structuredMarkdown = structured,
            detectedTasks = tasks,
            suggestedCategory = category
        )
    }

    /**
     * Fast, zero-dependency offline heuristic compiler when local AI / Gemini is unavailable.
     */
    fun fallbackCompile(transcript: String): CompiledVoiceResult {
        val actionKeywords = listOf(
            "penser à", "acheter", "appeler", "fixer", "réparer", "faire",
            "envoyer", "finir", "terminer", "auditer", "vérifier", "check", "buy", "fix", "call"
        )

        val clauses = transcript.split(Regex("[,.;]|\\bet\\b|\\bpuis\\b")).map { it.trim() }.filter { it.isNotBlank() }
        val tasks = mutableListOf<String>()
        val notes = mutableListOf<String>()

        for (clause in clauses) {
            val lower = clause.lowercase()
            val isAction = actionKeywords.any { lower.contains(it) }
            if (isAction) {
                val cleaned = clause.replace(Regex("^(penser à|il faut|pense à)\\s*", RegexOption.IGNORE_CASE), "")
                tasks.add(cleaned.replaceFirstChar { it.uppercase() })
            } else {
                notes.add("- ${clause.replaceFirstChar { it.uppercase() }}")
            }
        }

        val category = when {
            transcript.contains("projet", ignoreCase = true) || transcript.contains("waffle", ignoreCase = true) -> "20_Projects"
            transcript.contains("maison", ignoreCase = true) || transcript.contains("santé", ignoreCase = true) -> "30_Areas"
            else -> "10_Journal"
        }

        val structured = if (notes.isNotEmpty()) notes.joinToString("\n") else "- $transcript"

        return CompiledVoiceResult(
            rawTranscript = transcript,
            structuredMarkdown = structured,
            detectedTasks = tasks,
            suggestedCategory = category
        )
    }
}
