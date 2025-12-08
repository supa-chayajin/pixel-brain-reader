package cloud.wafflecommons.pixelbrainreader.data.ai

import cloud.wafflecommons.pixelbrainreader.data.ai.stub.GenerativeModelStub
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

enum class ScribePersona(val description: String) {
    TECH_WRITER("You are an expert Technical Writer. Be concise, structured, and use Markdown."),
    CODER("You are a Senior Software Engineer. Provide clean, documented Kotlin/Python code."),
    PLANNER("You are a Project Manager. Break down tasks into actionable steps.")
}

@Singleton
class GeminiScribeManager @Inject constructor() {
    
    private val model = GenerativeModelStub()

    fun generateText(prompt: String, persona: ScribePersona): Flow<String> {
        val finalPrompt = "System: ${persona.description}\n\nUser: $prompt"
        return model.generateContentStream(finalPrompt)
    }
}
