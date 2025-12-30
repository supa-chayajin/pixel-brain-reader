package cloud.wafflecommons.pixelbrainreader.ui.utils

data class ParsedMarkdown(
    val metadata: Map<String, String>, 
    val tags: List<String>, 
    val cleanContent: String
)

object ObsidianHelper {
    // Regex for Frontmatter: Matches start of file, --- OR +++, content, then --- OR +++
    private val FRONTMATTER_REGEX = Regex("^(?:---|\\+\\+\\+)\\n([\\s\\S]*?)\\n(?:---|\\+\\+\\+)", RegexOption.MULTILINE)
    
    val WIKI_LINK_REGEX = Regex("\\[\\[([^|\\]]+)(?:\\|([^\\]]+))?\\]\\]")
    val CALLOUT_REGEX = Regex("^>\\s\\[!(\\w+)\\]\\s(.*)$", RegexOption.MULTILINE)

    fun parse(content: String): ParsedMarkdown {
        val match = FRONTMATTER_REGEX.find(content)
        
        if (match != null) {
            val yamlBlock = match.groupValues[1]
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
            val trimmedLine = line.trim()
            
            // 1. Detect Start: > [!TYPE] Title
            val startMatch = CALLOUT_REGEX.find(line)
            
            if (startMatch != null) {
                if (inCallout) sb.append("</div></div>\n") // Close previous content and container
                val type = startMatch.groupValues[1]
                val customTitle = startMatch.groupValues[2].trim()
                sb.append(generateHtmlForCallout(type, if (customTitle.isEmpty()) null else customTitle)).append("\n")
                inCallout = true
            } 
            // 2. Detect Body: > content
            else if (inCallout && line.trimStart().startsWith(">")) {
                val contentLine = line.trimStart().removePrefix(">").removePrefix(" ")
                sb.append(contentLine).append("\n")
            }
            // 3. Detect End: Empty line or non-quote line
            else if (inCallout && trimmedLine.isEmpty()) {
                sb.append("</div></div>\n\n")
                inCallout = false
            }
            else {
                if (inCallout) {
                    sb.append("</div></div>\n")
                    inCallout = false
                }
                sb.append(line).append("\n")
            }
        }
        if (inCallout) sb.append("</div></div>\n")
        
        return sb.toString()
    }

    private fun generateHtmlForCallout(type: String, userTitle: String?): String {
        val (color, icon, defaultTitle) = getCalloutStyle(type)
        val displayTitle = userTitle ?: defaultTitle
        val bgColor = hexToRgba(color, 0.12f)
        
        return """
            <div style="
                background-color: $bgColor;
                border-left: 5px solid $color;
                border-radius: 8px;
                padding: 12px 16px;
                margin-bottom: 16px;
                color: currentColor;
            ">
                <div style="
                    color: $color;
                    font-weight: bold;
                    font-size: 1.1em;
                    margin-bottom: 6px;
                    display: block;
                ">
                    <span style="margin-right: 8px;">$icon</span>
                    <span>$displayTitle</span>
                </div>
                <div style="opacity: 0.9; line-height: 1.4;">
        """.trimIndent()
    }

    fun hexToRgba(hex: String, alpha: Float): String {
        val color = hex.replace("#", "")
        if (color.length != 6) return "rgba(0,0,0,$alpha)"
        val r = color.substring(0, 2).toInt(16)
        val g = color.substring(2, 4).toInt(16)
        val b = color.substring(4, 6).toInt(16)
        return "rgba($r, $g, $b, $alpha)"
    }

    private fun getCalloutStyle(type: String): Triple<String, String, String> {
        return when (type.lowercase()) {
            "info", "todo" -> Triple("#2196F3", "ℹ️", "Info")
            "tip", "hint", "important" -> Triple("#00BCD4", "💡", "Tip")
            "success", "check", "done" -> Triple("#4CAF50", "✅", "Success")
            "question", "help", "faq" -> Triple("#FF9800", "❓", "Question")
            "warning", "caution", "attention" -> Triple("#FFC107", "⚠️", "Warning")
            "failure", "fail", "missing" -> Triple("#F44336", "❌", "Failure")
            "danger", "error", "bug" -> Triple("#D32F2F", "🐞", "Error")
            "example" -> Triple("#9C27B0", "🟣", "Example")
            "quote", "cite" -> Triple("#9E9E9E", "❝", "Quote")
            else -> Triple("#607D8B", "📝", type.replaceFirstChar { it.uppercase() })
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
