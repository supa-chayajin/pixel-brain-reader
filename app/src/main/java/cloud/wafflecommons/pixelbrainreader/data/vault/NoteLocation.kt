package cloud.wafflecommons.pixelbrainreader.data.vault

/**
 * Where a note lives, as its header says under the title (13.0, D5): "when I open a note from a
 * wikilink, I don't know in which directory it is located".
 */
object NoteLocation {
    /** What the vault's top level is called: it has no folder name of its own. */
    const val ROOT = "Vault root"

    private const val SEPARATOR = " › "

    /** The folder holding [path], as the file list opens it: "" at the vault's top level. */
    fun folderOf(path: String): String = path.trim('/').substringBeforeLast('/', missingDelimiterValue = "")

    /** [folder] on one line, "20_Projects › 21_App › Notes", or [ROOT] at the top. */
    fun label(folder: String): String = parts(folder).ifEmpty { listOf(ROOT) }.joinToString(SEPARATOR)

    /** What a screen reader says for [folder]: its folders one after the other, never "›". */
    fun spoken(folder: String): String = "In " + parts(folder).ifEmpty { listOf(ROOT) }.joinToString(", ")

    private fun parts(folder: String): List<String> = folder.split('/').filter { it.isNotBlank() }
}
