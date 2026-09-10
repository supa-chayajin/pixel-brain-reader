package cloud.wafflecommons.pixelbrainreader.data.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class TaskCompletionDetectorTest {

    private val note = """
        # Today
        - [ ] Water the plants #home
        - [x] Morning run #vig
          - [ ] Nested stretch
        Some notes
    """.trimIndent()

    @Test
    fun `checking a box reports that task`() {
        val edited = note.replace("- [ ] Water the plants", "- [x] Water the plants")

        assertEquals(listOf("- [x] Water the plants #home"), TaskCompletionDetector.newlyCompletedTasks(note, edited))
    }

    @Test
    fun `an upper-case X and indented tasks count too`() {
        val edited = note.replace("- [ ] Nested stretch", "- [X] Nested stretch")

        assertEquals(listOf("  - [X] Nested stretch"), TaskCompletionDetector.newlyCompletedTasks(note, edited))
    }

    @Test
    fun `typing elsewhere never re-reports tasks that were already checked`() {
        val edited = note + "\nMore notes"

        assertEquals(emptyList<String>(), TaskCompletionDetector.newlyCompletedTasks(note, edited))
    }

    @Test
    fun `a task written already checked is not a completion`() {
        val edited = note + "\n- [x] Pasted done task"

        assertEquals(emptyList<String>(), TaskCompletionDetector.newlyCompletedTasks(note, edited))
    }

    @Test
    fun `matches the legacy detection on random edits`() {
        val random = Random(7)
        val labels = listOf("alpha", "beta", "gamma", "delta", "epsilon")
        repeat(300) {
            val old = randomNote(random, labels)
            val new = old.lines().joinToString("\n") { line ->
                if (random.nextInt(4) == 0 && line.trimStart().startsWith("- [")) {
                    val box = if (random.nextBoolean()) "- [x]" else if (random.nextBoolean()) "- [X]" else "- [ ]"
                    box + line.trimStart().drop(5)
                } else {
                    line
                }
            }
            assertEquals(legacy(old, new), TaskCompletionDetector.newlyCompletedTasks(old, new))
        }
    }

    private fun randomNote(random: Random, labels: List<String>): String =
        (0 until random.nextInt(1, 12)).joinToString("\n") {
            val indent = if (random.nextBoolean()) "" else "  "
            val box = listOf("- [ ]", "- [x]", "- [X]", "*").random(random)
            "$indent$box ${labels.random(random)}"
        }

    /** Verbatim port of the former MainViewModel.detectAndProcessTaskCompletion. */
    private fun legacy(oldContent: String, newContent: String): List<String> {
        val oldLines = oldContent.lines()
        val newLines = newContent.lines()
        val result = mutableListOf<String>()
        for (newLine in newLines) {
            val trimmed = newLine.trimStart()
            if (trimmed.startsWith("- [x]", ignoreCase = true)) {
                val uncheckedVersion1 = newLine.replaceFirst("- [x]", "- [ ]", ignoreCase = true)
                val uncheckedVersion2 = newLine.replaceFirst("- [X]", "- [ ]")
                if ((oldLines.contains(uncheckedVersion1) || oldLines.contains(uncheckedVersion2)) && !oldLines.contains(newLine)) {
                    result += newLine
                }
            }
        }
        return result
    }
}
