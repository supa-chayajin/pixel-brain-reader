package cloud.wafflecommons.pixelbrainreader.data.utils

import cloud.wafflecommons.pixelbrainreader.data.model.NoteMetadata
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import org.yaml.snakeyaml.DumperOptions
import java.io.StringWriter
import org.yaml.snakeyaml.Yaml as SnakeYaml

/**
 * YAML frontmatter manager.
 *
 * Two parsers, by design:
 *  - kaml (kotlinx.serialization) for typed reads into [NoteMetadata]. Strict
 *    mode is disabled so Obsidian's free-form user keys do not break decoding.
 *  - SnakeYAML for read/modify/write round-trips. Unknown keys are preserved
 *    verbatim because we operate on the raw Map<String, Any>.
 *
 * Delimiter detection is line-based — not regex — so the "double frontmatter"
 * pattern emitted by legacy daily-note templates (a hollow `---\n---` block
 * followed by the real `---…---` block) is recovered transparently.
 */
object FrontmatterManager {

    private val kaml: Yaml by lazy {
        Yaml(configuration = YamlConfiguration(strictMode = false))
    }

    private val snakeYaml: SnakeYaml by lazy {
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            splitLines = false // Obsidian-compatible: never wrap long lines
        }
        SnakeYaml(options)
    }

    // --- Public API: typed read ----------------------------------------------

    fun extractMetadata(content: String): NoteMetadata {
        val yamlBlock = locate(content)?.yamlText ?: return NoteMetadata()
        if (yamlBlock.isBlank()) return NoteMetadata()
        return try {
            kaml.decodeFromString(NoteMetadata.serializer(), yamlBlock)
        } catch (e: Exception) {
            // Malformed frontmatter must never fail a read; surface empty metadata.
            NoteMetadata()
        }
    }

    fun extractFrontmatterRaw(content: String): String =
        locate(content)?.yamlText.orEmpty()

    fun stripFrontmatter(content: String): String {
        val loc = locate(content) ?: return content
        return loc.body.trimStart('\n')
    }

    // --- Public API: round-trip read/modify/write -----------------------------

    fun extractFrontmatterAndBody(fullContent: String): Pair<MutableMap<String, Any>, String> {
        val loc = locate(fullContent) ?: return Pair(mutableMapOf(), fullContent)
        @Suppress("UNCHECKED_CAST")
        val parsed: MutableMap<String, Any>? = try {
            (snakeYaml.load(loc.yamlText) as? Map<String, Any>)?.toMutableMap()
        } catch (e: Exception) {
            null
        }
        return Pair(parsed ?: mutableMapOf(), loc.body)
    }

    @Suppress("UNCHECKED_CAST")
    fun upsertProperties(currentYaml: MutableMap<String, Any>, updates: Map<String, Any>) {
        updates.forEach { (key, newValue) ->
            if (newValue is List<*>) {
                val existing = currentYaml[key] as? MutableList<Any>
                if (existing != null) {
                    val toAdd = newValue.filterNotNull().filter { it !in existing }
                    existing.addAll(toAdd)
                } else {
                    currentYaml[key] = newValue.filterNotNull().toMutableList()
                }
            } else {
                currentYaml[key] = newValue
            }
        }
    }

    fun updateFrontmatter(content: String, updates: Map<String, Any?>): String {
        val (ast, body) = extractFrontmatterAndBody(content)
        @Suppress("UNCHECKED_CAST")
        val clean = updates.filterValues { it != null } as Map<String, Any>
        upsertProperties(ast, clean)
        return buildFileContent(ast, body)
    }

    fun buildFileContent(yamlMap: Map<String, Any>, markdownBody: String): String {
        if (yamlMap.isEmpty()) return markdownBody
        val writer = StringWriter()
        snakeYaml.dump(yamlMap, writer)
        val yamlBlock = writer.toString().trim()
        return buildString {
            append("---\n")
            append(yamlBlock)
            append("\n---\n")
            if (markdownBody.isNotBlank()) {
                if (!markdownBody.startsWith("\n")) append("\n")
                append(markdownBody)
            }
        }
    }

    // --- Delimiter location ---------------------------------------------------

    private data class Location(val yamlText: String, val body: String)

    private fun locate(content: String): Location? {
        if (content.isEmpty()) return null
        val lines = content.lines()

        // Skip leading blank lines.
        var i = 0
        while (i < lines.size && lines[i].isBlank()) i++
        if (i >= lines.size || lines[i].trimEnd() != "---") return null
        i++

        val firstClose = findCloseLine(lines, i) ?: return null
        val firstYaml = lines.subList(i, firstClose).joinToString("\n")

        if (firstYaml.isNotBlank()) {
            // Only treat the leading block as frontmatter if it actually looks like YAML.
            // A file that opens with `---` used as a thematic break (horizontal rule) and
            // contains a later `---` must NOT have the text between them swallowed as
            // frontmatter — that `---` is body content, not a delimiter.
            return if (looksLikeYaml(firstYaml)) {
                Location(firstYaml, bodyFrom(lines, firstClose + 1))
            } else {
                null
            }
        }

        // Empty leading block — recovery path for the legacy daily-note template
        // that wrote `---\n---\n<yaml>\n---`. Treat the next `---` line as the
        // real closing marker, provided the captured block looks like YAML.
        val recoveryClose = findCloseLine(lines, firstClose + 1)
            ?: return Location("", bodyFrom(lines, firstClose + 1))
        val recovered = lines.subList(firstClose + 1, recoveryClose).joinToString("\n")
        return if (recovered.isNotBlank() && looksLikeYaml(recovered)) {
            Location(recovered, bodyFrom(lines, recoveryClose + 1))
        } else {
            Location("", bodyFrom(lines, firstClose + 1))
        }
    }

    private fun findCloseLine(lines: List<String>, from: Int): Int? {
        for (k in from until lines.size) {
            if (lines[k].trimEnd() == "---") return k
        }
        return null
    }

    private fun bodyFrom(lines: List<String>, lineIndex: Int): String =
        if (lineIndex >= lines.size) "" else lines.subList(lineIndex, lines.size).joinToString("\n")

    private fun looksLikeYaml(block: String): Boolean =
        block.lineSequence().any { line ->
            val t = line.trimStart()
            t.isNotEmpty() && !t.startsWith('#') && t.contains(':')
        }
}
