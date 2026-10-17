package cloud.wafflecommons.pixelbrainreader.data.ai

import android.util.Log
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

/** A dictated note split into the to-dos it contains and the rest, as short markdown bullets. */
data class CompiledVoiceResult(
    val rawTranscript: String,
    val structuredMarkdown: String,
    val detectedTasks: List<String>
)

/**
 * Turns a raw speech-to-text transcript into to-dos plus a tidy markdown note.
 *
 * Gemini Nano structures it when the model is ready. When it isn't — or when its answer doesn't
 * follow the requested format — an offline heuristic does the job, so capturing a thought never
 * depends on the model. Failures come back as a [Result] from [LocalAiManager.generateResponse]
 * (never as content), and cancellation propagates.
 */
@Singleton
class VoiceCompiler @Inject constructor(
    private val localAiManager: LocalAiManager
) {
    suspend fun compile(transcript: String): CompiledVoiceResult {
        val text = transcript.trim()
        if (text.isEmpty()) return CompiledVoiceResult("", "", emptyList())

        val generated = localAiManager.generateResponse(buildPrompt(text.take(MAX_TRANSCRIPT_CHARS)))
        val parsed = generated.getOrNull()?.let { parseAiOutput(text, it) }
        if (parsed == null) {
            val reason = generated.exceptionOrNull()?.javaClass?.simpleName ?: "unexpected format"
            Log.i(TAG, "Voice note compiled offline ($reason)")
        }
        return parsed ?: fallbackCompile(text)
    }

    private fun buildPrompt(transcript: String): String = """
        Tu es l'assistant de capture vocale de l'utilisateur. Voici la transcription brute d'une note dictée.
        1. Extrais chaque action concrète à faire, une par ligne, au format « - [ ] action », sans hashtag.
        2. Résume le reste de la pensée en quelques puces Markdown claires et fidèles, sans rien inventer.

        Réponds STRICTEMENT dans ce format, sans autre texte :
        TACHES:
        - [ ] action
        NOTE:
        - idée

        Transcription :
        « $transcript »

        ${AiLanguage.DIRECTIVE}
    """.trimIndent()

    /** Reads the TACHES/NOTE answer; null when the model did not follow the format. */
    internal fun parseAiOutput(rawTranscript: String, aiOutput: String): CompiledVoiceResult? {
        val tasks = mutableListOf<String>()
        val note = mutableListOf<String>()
        var section: String? = null
        var sawMarker = false

        for (line in aiOutput.lines()) {
            val marker = sectionMarker(line)
            if (marker != null) {
                section = marker
                sawMarker = true
                continue
            }
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            when (section) {
                SECTION_TASKS -> taskLabel(trimmed)?.let { tasks += it }
                SECTION_NOTE -> if (!isPlaceholder(trimmed)) note += line.trimEnd()
            }
        }
        if (!sawMarker) return null
        return CompiledVoiceResult(
            rawTranscript = rawTranscript,
            structuredMarkdown = note.joinToString("\n").trim(),
            detectedTasks = tasks
        )
    }

    /**
     * Offline compiler: sentences that ask for an action become tasks (split on "," / "et" /
     * "puis" only inside such sentences), every other sentence becomes a note bullet.
     */
    fun fallbackCompile(transcript: String): CompiledVoiceResult {
        val tasks = mutableListOf<String>()
        val notes = mutableListOf<String>()
        transcript.split(SENTENCE_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }.forEach { sentence ->
            if (ACTION.containsMatchIn(sentence)) {
                sentence.split(CLAUSE_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }.forEach { clause ->
                    if (ACTION.containsMatchIn(clause)) {
                        tasks += clause.replace(LEADING_REMINDER, "").trim().replaceFirstChar { it.uppercase() }
                    } else {
                        notes += "- ${clause.replaceFirstChar { it.uppercase() }}"
                    }
                }
            } else {
                notes += "- ${sentence.replaceFirstChar { it.uppercase() }}"
            }
        }
        return CompiledVoiceResult(
            rawTranscript = transcript,
            structuredMarkdown = notes.joinToString("\n"),
            detectedTasks = tasks
        )
    }

    companion object {
        private const val TAG = "VoiceCompiler"
        private const val MAX_TRANSCRIPT_CHARS = 4_000
        private const val SECTION_TASKS = "TASKS"
        private const val SECTION_NOTE = "NOTE"

        private val SENTENCE_SPLIT = Regex("[.;!?\\n]+")
        private val CLAUSE_SPLIT = Regex("(?U),|\\bet\\b|\\bpuis\\b|\\band\\b|\\bthen\\b")
        private val ACTION = Regex(
            "(?iU)\\b(penser à|pense à|il faut|je dois|ne pas oublier|n'oublie pas|acheter|appeler|" +
                "rappeler|envoyer|réparer|fixer|finir|terminer|vérifier|auditer|réserver|payer|" +
                "prendre rendez-vous|écrire|préparer|buy|call|send|fix|finish|check|book|pay|remember to)\\b"
        )
        private val LEADING_REMINDER = Regex(
            "(?iU)^(penser à|pense à|il faut|je dois|ne pas oublier de|n'oublie pas de|remember to)\\s*"
        )

        /** One-line summary of a compiled note, for its timeline entry. */
        fun summaryLine(result: CompiledVoiceResult, maxChars: Int = 60): String {
            val first = result.structuredMarkdown.lineSequence()
                .map { it.trim().removePrefix("-").removePrefix("*").trim() }
                .firstOrNull { it.isNotEmpty() }
                ?: result.rawTranscript.trim()
            return if (first.length <= maxChars) first else first.take(maxChars - 1).trimEnd() + "…"
        }

        private fun sectionMarker(line: String): String? {
            val bare = Normalizer.normalize(line.trim().trim('*', '#', ' ', '_'), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .uppercase()
            return when (bare.removeSuffix(":").trim()) {
                "TACHES", "TASKS" -> SECTION_TASKS
                "NOTE", "NOTES", "CONTENT" -> SECTION_NOTE
                else -> null
            }
        }

        private fun taskLabel(line: String): String? {
            val label = line
                .removePrefix("-").removePrefix("*").trim()
                .replace(Regex("^\\[[ xX]?]\\s*"), "")
                .replace(Regex("\\s+#\\S+"), "") // no hashtags in task labels
                .trim()
            return label.takeUnless { it.isEmpty() || isPlaceholder(it) }
        }

        /** The model sometimes echoes the format template ("- [ ] action", "- idée", "…"). */
        private fun isPlaceholder(text: String): Boolean {
            val t = text.removePrefix("-").removePrefix("*").trim().trim('.', '…', ' ')
            return t.isEmpty() || t.equals("action", ignoreCase = true) || t.equals("idée", ignoreCase = true)
        }
    }
}
