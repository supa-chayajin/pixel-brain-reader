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
            val transformedContent = transformCallouts(cleanContent)
            return ParsedMarkdown(metadata, tags, transformedContent)
        } else {
            return ParsedMarkdown(emptyMap(), emptyList(), transformCallouts(content))
        }
    }

    private fun transformCallouts(content: String): String {
        val sb = StringBuilder()
        val lines = content.lines()
        var inCallout = false
        
        for (line in lines) {
            val trimmed = line.trim()
            
            // 1. Detect Start: > [!TYPE] Title
            val startMatch = CALLOUT_REGEX.find(line)
            
            if (startMatch != null) {
                if (inCallout) sb.append("</div>\n") // Close previous if unclosed
                val type = startMatch.groupValues[1]
                val title = startMatch.groupValues[2]
                sb.append(generateHtmlForCallout(type, title)).append("\n")
                inCallout = true
            } 
            // 2. Detect Body: > content
            else if (inCallout && line.trimStart().startsWith(">")) {
                // Strip the > and one space if present
                val contentLine = line.trimStart().removePrefix(">").removePrefix(" ")
                sb.append(contentLine).append("\n")
            }
            // 3. Detect End: Empty line or non-quote line
            else if (inCallout && trimmed.isEmpty()) {
                if (line.trim().isEmpty()) {
                    sb.append("</div>\n\n")
                    inCallout = false
                } else {
                     // Non-empty, non-quote line -> End of block
                    sb.append("</div>\n")
                    sb.append(line).append("\n")
                    inCallout = false
                }
            }
            else {
                if (inCallout) {
                    sb.append("</div>\n")
                    inCallout = false
                }
                sb.append(line).append("\n")
            }
        }
        if (inCallout) sb.append("</div>\n")
        
        return sb.toString()
    }

    private fun generateHtmlForCallout(type: String, title: String): String {
        val color = getCalloutColor(type)
        val rgba = hexToRgba(color, 0.1f)
        
        // CSS transparency (rgba) and inheritance (currentColor)
        // This makes callouts look good on BOTH Light and Dark themes without duplicate code.
        return """<div style="background-color: $rgba; border-left: 4px solid $color; padding: 12px; margin: 8px 0; border-radius: 4px; color: currentColor;"><strong>$title</strong><br/>"""
    }

    fun hexToRgba(hex: String, alpha: Float): String {
        val color = hex.replace("#", "")
        if (color.length != 6) return "rgba(0,0,0,$alpha)"
        val r = color.substring(0, 2).toInt(16)
        val g = color.substring(2, 4).toInt(16)
        val b = color.substring(4, 6).toInt(16)
        return "rgba($r, $g, $b, $alpha)"
    }

    private fun getCalloutColor(type: String): String {
        return when (type.uppercase()) {
            "TIP", "GOAL", "SUCCESS", "DONE" -> "#4CAF50"
            "INFO", "NOTE", "EXAMPLE" -> "#2196F3"
            "WARNING", "CAUTION", "ATTENTION" -> "#FF9800"
            "DANGER", "ERROR", "BUG", "FAIL" -> "#F44336"
            else -> "#9E9E9E"
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

