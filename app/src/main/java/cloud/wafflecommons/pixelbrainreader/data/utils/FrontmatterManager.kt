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
    /**
     * Injects/Updates Weather (and other keys) into the Standard Frontmatter Block.
     * Non-destructive: Updates existing keys or appends new ones.
     */
    fun injectWeather(content: String, newValues: Map<String, String>): String {
        // Regex to match the FIRST YAML block content captured in group 1
        val frontmatterPattern = "(?s)^---\\s*\\n(.*?)\\n---\\s*\\n?".toRegex()
        val match = frontmatterPattern.find(content)
        
        if (match != null) {
            // 1. Extract existing YAML content
            var yamlContent = match.groupValues[1]
            
            // 2. Update/Add keys
            newValues.forEach { (key, value) ->
                // Ensure value is quoted if it contains spaces and not already quoted
                val safeValue = if (value.contains(" ") && !value.startsWith("\"")) "\"$value\"" else value
                yamlContent = updateYamlKey(yamlContent, key, safeValue)
            }
            
            // 3. Reconstruct
            return content.replaceRange(match.range, "---\n$yamlContent\n---\n")
        } else {
            // Create new block if missing
            return buildYamlBlock(newValues) + content
        }
    }

    // Helper to replace or append a key in YAML string
    private fun updateYamlKey(yaml: String, key: String, value: String): String {
        val keyRegex = "(?m)^$key:.*$".toRegex()
        return if (keyRegex.containsMatchIn(yaml)) {
            yaml.replace(keyRegex, "$key: $value")
        } else {
            "$yaml\n$key: $value"
        }
    }

    private fun buildYamlBlock(map: Map<String, String>): String {
        return buildString {
            append("---\n")
            map.forEach { (k, v) ->
                val safeValue = if (v.contains(" ") && !v.startsWith("\"")) "\"$v\"" else v
                append("$k: $safeValue\n")
            }
            append("---\n")
        }
    }
}
