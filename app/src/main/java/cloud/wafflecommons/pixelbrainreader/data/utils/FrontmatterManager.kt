package cloud.wafflecommons.pixelbrainreader.data.utils

/**
 * Robust YAML Frontmatter Manager.
 * Handles extraction and stripping of standard Obsidian/Markdown YAML blocks.
 */
object FrontmatterManager {

    // Regex to match ANY YAML block (start to end dashes)
    private val genericYamlRegex = Regex("^---\\n[\\s\\S]*?\\n---\\n?", RegexOption.MULTILINE)
    
    // Regex for specific keys
    private val pixelBrainToken = "pixel_brain_log: true"
    
    // We strictly identify Block A (Standard) as NOT having the token.
    // We strictly identify Block B (PixelBrain) as HAVING the token.

    /**
     * Extracts frontmatter from the FIRST block found.
     * (Legacy compatibility - likely Block A).
     */
    fun extractFrontmatter(content: String): Map<String, String> {
        val match = genericYamlRegex.find(content) ?: return emptyMap()
        return parseYaml(match.value)
    }

    private fun parseYaml(block: String): Map<String, String> {
        val yaml = block.trim().removeSurrounding("---").trim()
        return yaml.lines()
            .map { it.split(":", limit = 2) }
            .filter { it.size == 2 }
            .associate { it[0].trim() to it[1].trim().removeSurrounding("\"").removeSurrounding("'") }
    }

    /**
     * Removes ONLY the PixelBrain metadata block (Block B).
     * Keeps Standard Frontmatter (Block A) visible.
     */
    fun stripPixelBrainMetadata(content: String): String {
        return genericYamlRegex.replace(content) { matchResult ->
            if (matchResult.value.contains(pixelBrainToken)) {
                "" // Remove this block
            } else {
                matchResult.value // Keep this block (Block A)
            }
        }.trimStart() // Clean up empty lines at start if Block B was first (unlikely) or second.
    }
    
    // Legacy alias - defaults to stripping PixelBrain for display
    /**
     * Removes the FIRST YAML block found at the start of the file.
     * Used for display purposes to hide raw metadata.
     */
    fun stripFrontmatter(content: String): String {
        // Regex to remove the FIRST YAML block found at the start of the file.
        // Matches from start (---) to end (---) non-greedily.
        val frontmatterPattern = "(?s)^---\\s*\\n.*?\\n---\\s*\\n?"
        
        return content.replaceFirst(Regex(frontmatterPattern), "").trimStart()
    }
    fun prepareContentForDisplay(content: String): String = stripPixelBrainMetadata(content)

    /**
     * Injects Weather into Block A (Standard Frontmatter).
     * Creates Block A if missing.
     * Guaranteed NOT to touch Block B (PixelBrain).
     */
    fun injectWeather(content: String, newValues: Map<String, String>): String {
        // 1. Find Block A (First block that is NOT PixelBrain)
        val matches = genericYamlRegex.findAll(content).toList()
        val blockA = matches.firstOrNull { !it.value.contains(pixelBrainToken) }
        
        if (blockA != null) {
            // Update Existing Block A
            val currentMap = parseYaml(blockA.value).toMutableMap()
            currentMap.putAll(newValues)
            
            val newBlock = buildYamlBlock(currentMap)
            // Replace the EXACT string range of Block A
            return content.replaceRange(blockA.range, newBlock.trimEnd() + "\n")
        } else {
            // Create New Block A at the top
            val newBlock = buildYamlBlock(newValues)
            // If there's a Block B at start, we prepend before it? 
            // Standard convention is Frontmatter at specific line 1.
            // If Block B is there, we insert before it. 
            return newBlock + content
        }
    }
    
    // Legacy alias for updating (redirects to strict weather injection or similar logic)
    // For now, mapping updateFrontmatter to injectWeather to satisfy existing calls safely
    fun updateFrontmatter(content: String, newValues: Map<String, String>): String {
        return injectWeather(content, newValues)
    }

    private fun buildYamlBlock(map: Map<String, String>): String {
        return buildString {
            append("---\n")
            map.forEach { (k, v) ->
                append("$k: \"$v\"\n")
            }
            append("---\n")
        }
    }
}
