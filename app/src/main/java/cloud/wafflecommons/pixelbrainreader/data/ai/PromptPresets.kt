package cloud.wafflecommons.pixelbrainreader.data.ai

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.Normalizer
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A chat preset: a persona and an answer format the user keeps in their vault. [useNotes] grounds
 * it on the vault (RAG), like Cortex. [prompt] is the user's text; [systemPrompt] adds the
 * French-answer directive, which a preset can't remove.
 */
data class PromptPreset(
    val id: String,
    val title: String,
    val description: String,
    val useNotes: Boolean,
    val prompt: String
) {
    val systemPrompt: String get() = "$prompt\n\n${AiLanguage.SYSTEM_DIRECTIVE}"
}

/**
 * `99_System/presets.md`: one `##` section per preset. Option lines (`notes: yes`,
 * `description: …`) open a section; the rest of it is the prompt, capped so that it leaves Gemini
 * Nano room for the notes, the history and the question (roadmap 2.7).
 */
object PromptPresetFormat {

    const val MAX_PROMPT_CHARS = 1_500

    fun parse(markdown: String): List<PromptPreset> {
        val presets = mutableListOf<PromptPreset>()
        val ids = mutableSetOf<String>()
        var title: String? = null
        val body = mutableListOf<String>()

        fun flush() {
            val heading = title ?: return
            preset(heading, body)?.let { parsed ->
                var id = parsed.id
                var n = 2
                while (!ids.add(id)) id = "${parsed.id}-${n++}"
                presets += parsed.copy(id = id)
            }
            body.clear()
        }

        for (line in withoutFrontmatter(markdown).lines()) {
            if (line.startsWith("## ")) {
                flush()
                title = line.removePrefix("## ").trim()
            } else if (title != null) {
                body += line
            }
        }
        flush()
        return presets
    }

    fun render(presets: List<PromptPreset>, date: LocalDate): String = buildString {
        append(VaultSchema.frontmatter(title = "Prompt presets", path = PromptPresetRepository.PATH, date = date, tags = listOf("ai", "presets")))
        append("# Prompt presets\n\n")
        append("Each `##` section is a preset in the chat's preset menu. Start a section with options:\n")
        append("`notes: yes` grounds it on your notes, `description:` is its line in the menu. The rest is\n")
        append("the prompt, written in French (Gemini Nano follows French prompts best). The app adds the\n")
        append("answer-in-French rule itself and keeps the first ${MAX_PROMPT_CHARS} characters of a prompt.\n")
        presets.forEach { preset ->
            append("\n## ").append(preset.title).append('\n')
            append("notes: ").append(if (preset.useNotes) "yes" else "no").append('\n')
            if (preset.description.isNotBlank()) append("description: ").append(preset.description).append('\n')
            append('\n').append(preset.prompt.trim()).append('\n')
        }
    }

    private fun preset(title: String, lines: List<String>): PromptPreset? {
        var useNotes = false
        var description = ""
        var index = 0
        while (index < lines.size && lines[index].isBlank()) index++
        while (index < lines.size) {
            val key = lines[index].substringBefore(':', "").trim().lowercase()
            val value = lines[index].substringAfter(':', "").trim()
            when (key) {
                "notes" -> useNotes = value.lowercase() in TRUE_WORDS
                "description" -> description = value
                else -> break
            }
            index++
        }
        val prompt = lines.drop(index).joinToString("\n").trim().take(MAX_PROMPT_CHARS)
        if (prompt.isEmpty() || title.isEmpty()) return null
        return PromptPreset(slug(title), title, description, useNotes, prompt)
    }

    private fun withoutFrontmatter(markdown: String): String {
        val text = markdown.replace("\r\n", "\n")
        if (!text.startsWith("---\n")) return text
        val end = text.indexOf("\n---", startIndex = 3)
        return if (end < 0) text else text.substring(end + 4).removePrefix("\n")
    }

    /** "🏛️ Boardroom" → "boardroom": letters and digits, accents dropped. */
    internal fun slug(title: String): String {
        val ascii = Normalizer.normalize(title, Normalizer.Form.NFD).filter { it.code < 128 }
        return ascii.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
            .split('-').filter { it.isNotEmpty() }.joinToString("-")
            .ifEmpty { "preset" }
    }

    private val TRUE_WORDS = setOf("yes", "true", "oui", "on", "1")

    /** Built in: used when the vault has no presets file, and written to it on "Edit presets". */
    val DEFAULTS: List<PromptPreset> = listOf(
        PromptPreset(
            id = "boardroom",
            title = "🏛️ Boardroom",
            description = "Three experts debate a technical dilemma, then decide",
            useNotes = true,
            prompt = """
                Tu es le Boardroom de Cortex : trois experts débattent d'un dilemme technique de l'utilisateur, puis tranchent.
                - Alexandre, architecte DDD : frontières du domaine, couplage, abstractions.
                - Bastien, DevSecOps : sécurité, secrets, fiabilité, modes de défaillance.
                - Camille, tech lead pragmatique : rapport effort/valeur, YAGNI, livraison.

                FORMAT OBLIGATOIRE, 200 mots maximum au total :
                ### 🏛️ Alexandre
                Deux phrases.
                ### 🛡️ Bastien
                Deux phrases.
                ### ⚡ Camille
                Deux phrases.
                ### 🎯 Décision
                Deux ou trois phrases, avec la prochaine étape concrète.

                Appuie-toi sur les INFORMATIONS DE RÉFÉRENCE quand elles sont présentes ; n'invente pas le contenu de ses notes.
            """.trimIndent()
        ),
        PromptPreset(
            id = "coach",
            title = "🧭 Coach",
            description = "A pocket coach for a lead: one open question at a time",
            useNotes = false,
            prompt = """
                Tu es un coach de poche pour un tech lead : écoute active, questions ouvertes, aucune leçon.

                MÉTHODE :
                1. Reformule la situation en une phrase.
                2. Pose UNE seule question ouverte qui l'aide à avancer : son objectif, ses options, l'obstacle ou la prochaine étape.
                3. S'il demande ton avis, propose au plus deux pistes concrètes, chacune avec son risque.

                CONCISION : 80 mots maximum. Pas de jargon de coaching, pas de diagnostic, aucun conseil médical ou juridique.
                Ne nomme jamais un client ni un collègue : parle de rôles.
            """.trimIndent()
        )
    )
}

/**
 * The presets, read from the vault (so they are edited in Obsidian and synced), the built-in
 * defaults when the file doesn't exist or holds none.
 */
@Singleton
class PromptPresetRepository @Inject constructor(
    private val fileRepository: FileRepository,
    private val pushScheduler: VaultPushScheduler
) {
    private val _presets = MutableStateFlow(PromptPresetFormat.DEFAULTS)
    val presets: StateFlow<List<PromptPreset>> = _presets.asStateFlow()

    suspend fun reload() {
        val content = try {
            fileRepository.readFile(PATH)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Presets unreadable: ${e.message}")
            null
        }
        _presets.value = content?.let(PromptPresetFormat::parse)?.takeIf { it.isNotEmpty() } ?: PromptPresetFormat.DEFAULTS
    }

    /** The presets file's path, after writing the defaults there if it doesn't exist yet. */
    suspend fun ensureFile(): String {
        if (!fileRepository.fileExists(PATH)) {
            fileRepository.saveFileLocally(PATH, PromptPresetFormat.render(PromptPresetFormat.DEFAULTS, LocalDate.now()))
            pushScheduler.requestPush()
        }
        return PATH
    }

    companion object {
        const val PATH = "99_System/presets.md"
        private const val TAG = "PromptPresets"
    }
}
