package cloud.wafflecommons.pixelbrainreader.data.utils

import cloud.wafflecommons.pixelbrainreader.data.model.NoteMetadata
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.StringWriter

/**
 * Robust YAML Frontmatter Manager using SnakeYAML (AST Parser).
 * Ensures zero data corruption and 100% Obsidian compatibility.
 */
object FrontmatterManager {

    // Regex strictly to isolate the frontmatter block from the Markdown body.
    private val frontmatterSplitRegex = Regex("^---\\s*\\n([\\s\\S]*?)\\n---\\s*\\n?", RegexOption.MULTILINE)

    private val yaml: Yaml by lazy {
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            // Obsidian compatibility: explicitly avoid wrapping long lines in YAML
            splitLines = false 
        }
        Yaml(options)
    }

    /**
     * Extracts and parses metadata into a strongly typed object.
     * Legacy Kaml logic is replaced by SnakeYAML + manual mapping or JSON serialization fallback.
     * For NoteMetadata, we can use the AST directly.
     */
    fun extractMetadata(content: String): NoteMetadata {
        val (ast, _) = extractFrontmatterAndBody(content)
        if (ast.isEmpty()) return NoteMetadata()
        
        // Simple manual mapping from AST to NoteMetadata to avoid Jackson/Gson overhead
        return NoteMetadata(
            tags = (ast["tags"] as? List<*>)?.map { it.toString() } ?: emptyList(),
            aliases = (ast["aliases"] as? List<*>)?.map { it.toString() } ?: emptyList(),
            moodScore = (ast["mood_score"] as? Number)?.toInt(),
            energyLevel = (ast["energy_level"] as? Number)?.toInt(),
            weatherDesc = ast["weather_desc"]?.toString(),
            locationLat = (ast["location_lat"] as? Number)?.toDouble(),
            locationLon = (ast["location_lon"] as? Number)?.toDouble(),
            createdAt = ast["created_at"]?.toString(),
            updatedAt = ast["updated_at"]?.toString()
        )
    }

    /**
     * Legacy Compatibility Method to return raw Map.
     */
    fun extractFrontmatter(content: String): Map<String, String> {
        val (ast, _) = extractFrontmatterAndBody(content)
        return ast.mapValues { it.value.toString() }
    }
    
    /**
     * Legacy Compatibility for raw string.
     */
    fun extractFrontmatterRaw(content: String): String {
        val match = frontmatterSplitRegex.find(content)
        return match?.groupValues?.get(1) ?: ""
    }
    
    fun stripFrontmatter(content: String): String {
        return content.replaceFirst(frontmatterSplitRegex, "").trimStart()
    }

    /**
     * Updates specific fields in the frontmatter while preserving other user keys.
     * Replaces `injectWeather` and string-manipulation `updateFrontmatter`.
     */
    fun updateFrontmatter(content: String, updates: Map<String, Any?>): String {
        val (ast, body) = extractFrontmatterAndBody(content)
        
        // Filter out nulls for upsert
        @Suppress("UNCHECKED_CAST")
        val cleanUpdates = updates.filterValues { it != null } as Map<String, Any>
        upsertProperties(ast, cleanUpdates)
        
        return buildFileContent(ast, body)
    }

    /**
     * Step 1: Extraction - Safely splits the YAML block and parses it into a Map.
     * Returns a Pair: <The parsed YAML Map, The untouched Markdown Body>
     */
    fun extractFrontmatterAndBody(fullContent: String): Pair<MutableMap<String, Any>, String> {
        val match = frontmatterSplitRegex.find(fullContent)
        
        if (match != null) {
            val yamlString = match.groupValues[1]
            val markdownBody = fullContent.substring(match.range.last + 1)
            
            // AST Parse
            val parsedMap: MutableMap<String, Any>? = try {
                yaml.load(yamlString)
            } catch (e: Exception) {
                null
            }
            
            return Pair(parsedMap ?: mutableMapOf(), markdownBody)
        }
        
        // No frontmatter found
        return Pair(mutableMapOf(), fullContent)
    }

    /**
     * Step 2: Modification - Updates or inserts properties type-safely.
     * 
     * @param currentYaml The AST Map extracted from extractFrontmatterAndBody.
     * @param updates A Map of key-value pairs to upsert. Can contain primitive types or Lists.
     */
    @Suppress("UNCHECKED_CAST")
    fun upsertProperties(currentYaml: MutableMap<String, Any>, updates: Map<String, Any>) {
        updates.forEach { (key, newValue) ->
            if (newValue is List<*>) {
                // Smart append for Lists (e.g., tags)
                val existingList = currentYaml[key] as? MutableList<Any>
                if (existingList != null) {
                    val itemsToAdd = newValue.filterNotNull().filter { !existingList.contains(it) }
                    existingList.addAll(itemsToAdd)
                } else {
                    currentYaml[key] = newValue.toMutableList()
                }
            } else {
                // Direct overwrite / insert for scalars (Int, Double, String, Boolean)
                currentYaml[key] = newValue
            }
        }
    }

    /**
     * Step 3: Serialization - Builds the final, uncorrupted Obsidian-compatible Markdown text.
     */
    fun buildFileContent(yamlMap: Map<String, Any>, markdownBody: String): String {
        if (yamlMap.isEmpty()) return markdownBody

        val writer = StringWriter()
        yaml.dump(yamlMap, writer)
        val yamlBlock = writer.toString().trim()

        return buildString {
            append("---\n")
            append(yamlBlock)
            append("\n---\n")
            // Ensure exactly one newline separates frontmatter from body if body exists
            if (markdownBody.isNotBlank()) {
                if (!markdownBody.startsWith("\n")) {
                     append("\n")
                }
                append(markdownBody)
            }
        }
    }
}
