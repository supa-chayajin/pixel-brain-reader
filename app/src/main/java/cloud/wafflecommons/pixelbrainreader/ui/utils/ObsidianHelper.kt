package cloud.wafflecommons.pixelbrainreader.ui.utils

data class ParsedMarkdown(
    val metadata: Map<String, String>, 
    val tags: List<String>, 
    val cleanContent: String
)

object ObsidianHelper {
    // Frontmatter is ONLY a fenced YAML block at the very TOP of the file (offset 0).
    // A `---`/`+++` line anywhere else is a Markdown thematic break (horizontal rule),
    // never frontmatter. We anchor to \A (start of input) and drop RegexOption.MULTILINE so
    // that mid-file `---` separators (common in diaries) can no longer be mis-parsed as
    // metadata. The closing fence must be its own line.
    private val FRONTMATTER_REGEX =
        Regex("\\A(?:---|\\+\\+\\+)[ \\t]*\\r?\\n([\\s\\S]*?)\\r?\\n(?:---|\\+\\+\\+)[ \\t]*(?:\\r?\\n|\\z)")
    
    val WIKI_LINK_REGEX = Regex("\\[\\[([^|\\]]+)(?:\\|([^\\]]+))?\\]\\]")
    fun parse(content: String): ParsedMarkdown {
        val match = FRONTMATTER_REGEX.find(content)
        
        if (match != null) {
            val yamlBlock = match.groupValues[1]
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
                val value = cleanLine.removePrefix("- ").trim()
                if (currentKey == "tags") {
                    tags.add(value.removeSurrounding("\"").removeSurrounding("'"))
                } else {
                    val existing = metadata[currentKey!!] ?: ""
                    metadata[currentKey!!] = if (existing.isEmpty()) value else "$existing, $value"
                }
            } else if (cleanLine.contains(":")) {
                val parts = cleanLine.split(":", limit = 2)
                val key = parts[0].trim().lowercase()
                val value = parts.getOrNull(1)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
                
                currentKey = key
                
                if (key == "tags") {
                    if (value.startsWith("[") && value.endsWith("]")) {
                        tags.addAll(
                            value.removeSurrounding("[", "]")
                                .split(",")
                                .map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
                                .filter { it.isNotEmpty() }
                        )
                        currentKey = null 
                    }
                } else {
                    metadata[key] = value
                }
            }
        }
        return metadata to tags
    }
}
