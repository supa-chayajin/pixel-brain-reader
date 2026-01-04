package cloud.wafflecommons.pixelbrainreader.data.utils

/**
 * Robust YAML Frontmatter Manager.
 * Handles extraction and stripping of standard Obsidian/Markdown YAML blocks.
 */
object FrontmatterManager {

    // Matches standard YAML block at the start of the file
    private val frontmatterRegex = Regex("^---\\n(?:(?!---)[\\s\\S])*?\\n---\\n?", RegexOption.MULTILINE)

    /**
     * Extracts frontmatter as a simple key-value map.
     * Note: This is a basic parser. It doesn't handle nested YAML objects or lists deeply.
     */
    fun extractFrontmatter(content: String): Map<String, String> {
        val match = frontmatterRegex.find(content) ?: return emptyMap()
        val yaml = match.value.trim().removeSurrounding("---").trim()
        
        return yaml.lines()
            .map { it.split(":", limit = 2) }
            .filter { it.size == 2 }
            .associate { it[0].trim() to it[1].trim().removeSurrounding("\"").removeSurrounding("'") }
    }

    /**
     * Removes the first YAML frontmatter block found at the top of the content.
     */
    fun stripFrontmatter(content: String): String {
        return content.replaceFirst(frontmatterRegex, "")
    }

    /**
     * Smart Content Cleaning for Display.
     * Removes the standard YAML block for reading mode.
     */
    fun prepareContentForDisplay(content: String): String {
        return stripFrontmatter(content)
    }
}
