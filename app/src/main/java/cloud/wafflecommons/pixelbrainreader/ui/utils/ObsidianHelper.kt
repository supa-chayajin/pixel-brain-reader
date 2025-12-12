package cloud.wafflecommons.pixelbrainreader.ui.utils

data class ParsedMarkdown(
    val metadata: Map<String, String>, 
    val tags: List<String>, 
    val cleanContent: String
)

object ObsidianHelper {
    // Regex for Frontmatter: Matches start of file, --- OR +++, content, then --- OR +++
    // Use (?s) via DOT_MATCHES_ALL option
    private val FRONTMATTER_REGEX = Regex("^(?:---|\\+\\+\\+)\\n([\\s\\S]*?)\\n(?:---|\\+\\+\\+)", RegexOption.MULTILINE)
    
    val WIKI_LINK_REGEX = Regex("\\[\\[([^|\\]]+)(?:\\|([^\\]]+))?\\]\\]")
    val CALLOUT_REGEX = Regex("^>\\s\\[!(\\w+)\\]\\s(.*)$", RegexOption.MULTILINE)

    fun parse(content: String): ParsedMarkdown {
        val match = FRONTMATTER_REGEX.find(content)
        
        if (match != null) {
            val yamlBlock = match.groupValues[1]
            // Remove the ENTIRE matched frontmatter block from the start of the string
            val cleanContent = content.substring(match.range.last + 1).trimStart()
            
            val (metadata, tags) = parseYamlMetadata(yamlBlock)
            return ParsedMarkdown(metadata, tags, cleanContent)
        } else {
            return ParsedMarkdown(emptyMap(), emptyList(), content)
        }
    }

    private fun parseYamlMetadata(yaml: String): Pair<Map<String, String>, List<String>> {
        val metadata = mutableMapOf<String, String>()
        val tags = mutableListOf<String>()
        var currentKey: String? = null
        
        yaml.lines().forEach { line ->
            val cleanLine = line.trim()
            if (cleanLine.isEmpty() || cleanLine.startsWith("#")) return@forEach

            if (cleanLine.startsWith("- ") && currentKey != null) {
                // List item for the current key
                val value = cleanLine.removePrefix("- ").trim()
                if (currentKey == "tags") {
                    tags.add(value.removeSurrounding("\"").removeSurrounding("'"))
                } else {
                    // For other keys, we could append to a list, but our metadata map is String->String.
                    // For now, let's append comma-separated or just ignore if not supported by UI.
                    // User asked to capture all keys. Let's append to existing value if any.
                    val existing = metadata[currentKey!!] ?: ""
                    metadata[currentKey!!] = if (existing.isEmpty()) value else "$existing, $value"
                }
            } else if (cleanLine.contains(":")) {
                val parts = cleanLine.split(":", limit = 2)
                val key = parts[0].trim().lowercase()
                val value = parts.getOrNull(1)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
                
                currentKey = key
                
                if (key == "tags") {
                    // Handle inline array [a, b]
                    if (value.startsWith("[") && value.endsWith("]")) {
                        tags.addAll(
                            value.removeSurrounding("[", "]")
                                .split(",")
                                .map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
                                .filter { it.isNotEmpty() }
                        )
                        currentKey = null // Inline format usually ends the block for this key
                    }
                } else {
                    metadata[key] = value
                }
            }
        }
        return metadata to tags
    }
}
