package cloud.wafflecommons.pixelbrainreader.guard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Settled decision #5 (CLAUDE.md): UI copy is English; AI answers are French. 866e0d5 shipped
 * ~38 French UI strings at once — this guard fails the build on the next one instead.
 *
 * It scans the string literals of the UI layers (and of the data classes that produce UI labels)
 * for French: accented letters, or common French UI words. AI PROMPTS are French on purpose (they
 * steer Gemini Nano): raw `"""` strings are skipped, and the two UI-package files that build
 * prompts with plain strings are allow-listed.
 */
class UiLanguageGuardTest {

    private val sourceRoot: File = listOf("src/main/java", "app/src/main/java")
        .map { File(it, "cloud/wafflecommons/pixelbrainreader") }
        .first { it.isDirectory }

    /** Folders and files whose literals are user-facing. */
    private val scanned = listOf("ui", "widget", "data/repository/MemoryEchoRepository.kt")

    /** French by design: AI prompt text, never shown as UI copy. */
    private val promptFiles = setOf(
        "ui/ai/ChatViewModel.kt",
        "ui/privatevault/PrivateJournalViewModel.kt"
    )

    private val accented = Regex("[éèêëàâäùûüçîïôöœÉÈÊÀÂÇÎÔŒ]")
    private val frenchWords = Regex(
        "\\b(Fermer|Actualiser|Annuler|Supprimer|Enregistrer|Restaurer|Liens?|Orphelines?|Aucune?|" +
            "Consulter|Marquer|Retour|Rappel|Perle|jours|Réessayer|Paramètres|Tâches?)\\b"
    )
    private val literal = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")

    @Test
    fun `UI string literals are English`() {
        val violations = mutableListOf<String>()
        scanned.map { File(sourceRoot, it) }
            .flatMap { if (it.isDirectory) it.walkTopDown().filter { f -> f.extension == "kt" }.toList() else listOf(it) }
            .filter { it.relativeTo(sourceRoot).invariantSeparatorsPath !in promptFiles }
            .forEach { file ->
                var inRawString = false
                file.readLines().forEachIndexed { index, line ->
                    val code = line.trim()
                    if (line.windowed(3).count { it == "\"\"\"" } % 2 == 1) {
                        inRawString = !inRawString
                        return@forEachIndexed
                    }
                    if (inRawString || code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) {
                        return@forEachIndexed
                    }
                    literal.findAll(line).map { it.groupValues[1] }
                        .filter { accented.containsMatchIn(it) || frenchWords.containsMatchIn(it) }
                        .forEach { text ->
                            violations += "${file.relativeTo(sourceRoot).invariantSeparatorsPath}:${index + 1}  \"$text\""
                        }
                }
            }
        assertTrue(
            "French UI copy found (UI is English — see settled decision #5):\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }
}
