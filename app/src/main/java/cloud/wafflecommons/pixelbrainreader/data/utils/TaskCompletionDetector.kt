package cloud.wafflecommons.pixelbrainreader.data.utils

/**
 * Finds the markdown tasks an edit just checked off (`- [ ] task` → `- [x] task`), to award XP.
 *
 * Runs on every keystroke of the note editor, so it stays linear in the note length: the previous
 * inline version looked each checked line up in the old content's line LIST (`List.contains`),
 * which is quadratic.
 */
object TaskCompletionDetector {

    private const val CHECKED = "- [x]"
    private const val UNCHECKED = "- [ ]"

    fun newlyCompletedTasks(oldContent: String, newContent: String): List<String> {
        // No checked task at all → nothing can have been completed (and no line split needed).
        if (!newContent.contains(CHECKED, ignoreCase = true)) return emptyList()

        var oldLines: Set<String>? = null
        val completed = mutableListOf<String>()
        newContent.lineSequence().forEach { line ->
            if (!line.trimStart().startsWith(CHECKED, ignoreCase = true)) return@forEach
            val previous = oldLines ?: oldContent.lines().toHashSet().also { oldLines = it }
            val uncheckedAnyCase = line.replaceFirst(CHECKED, UNCHECKED, ignoreCase = true)
            val uncheckedUpperCase = line.replaceFirst("- [X]", UNCHECKED)
            // It was unchecked before, and this checked line is new.
            if ((uncheckedAnyCase in previous || uncheckedUpperCase in previous) && line !in previous) {
                completed += line
            }
        }
        return completed
    }
}
