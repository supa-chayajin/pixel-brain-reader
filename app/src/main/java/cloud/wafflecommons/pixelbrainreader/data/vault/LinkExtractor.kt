package cloud.wafflecommons.pixelbrainreader.data.vault

/** One link found in a note. [targetKey] is what links resolve by (see [LinkExtractor.keyOf]). */
data class ExtractedLink(
    val target: String,
    val targetKey: String,
    val kind: String,
    val line: Int,
    val context: String
)

/**
 * Finds the links of a markdown note: `[[wikilinks]]`, `![[embeds]]` and relative `[text](note.md)`
 * links, outside fenced code and inline code. Targets are kept as written; [keyOf] gives the
 * Obsidian-style resolution key (the basename, case-insensitive, without `.md`), so a link
 * resolves whenever a note with that name exists — even one created after the link.
 */
object LinkExtractor {

    const val KIND_WIKI = "wiki"
    const val KIND_EMBED = "embed"
    const val KIND_MARKDOWN = "md"

    // Brackets are escaped everywhere: these patterns must compile on Android's ICU engine too.
    private val WIKI = Regex("""(!?)\[\[([^\]|#^\n]+)(?:[#^][^\]|\n]*)?(?:\|[^\]\n]*)?\]\]""")
    private val MARKDOWN = Regex("""(?<!!)\[[^\]\n]*\]\(([^)\s]+?\.md)(?:#[^)\s]*)?\)""")
    private val INLINE_CODE = Regex("""`[^`\n]*`""")
    private val FENCE = Regex("""^\s*(```|~~~)""")
    private const val CONTEXT_CHARS = 160

    fun extract(content: String): List<ExtractedLink> {
        val out = mutableListOf<ExtractedLink>()
        var fence: String? = null
        content.lineSequence().forEachIndexed { index, rawLine ->
            val fenceMatch = FENCE.find(rawLine)
            if (fenceMatch != null) {
                val marker = fenceMatch.groupValues[1]
                fence = if (fence == null) marker else if (fence == marker) null else fence
                return@forEachIndexed
            }
            if (fence != null) return@forEachIndexed
            val line = INLINE_CODE.replace(rawLine) { " ".repeat(it.value.length) }
            val context = rawLine.trim().take(CONTEXT_CHARS)
            WIKI.findAll(line).forEach { m ->
                val target = m.groupValues[2].trim()
                if (target.isNotEmpty()) {
                    val kind = if (m.groupValues[1] == "!") KIND_EMBED else KIND_WIKI
                    out += ExtractedLink(target, keyOf(target), kind, index + 1, context)
                }
            }
            MARKDOWN.findAll(line).forEach { m ->
                val target = decode(m.groupValues[1])
                if (!target.contains("://")) {
                    out += ExtractedLink(target, keyOf(target), KIND_MARKDOWN, index + 1, context)
                }
            }
        }
        return out
    }

    /** `folder/My Note.md` → `my note`; `image.png` → `image.png`. */
    fun keyOf(target: String): String {
        val base = target.trim().replace('\\', '/').substringAfterLast('/')
        val withoutMd = if (base.endsWith(".md", ignoreCase = true)) base.dropLast(3) else base
        return withoutMd.lowercase()
    }

    /** The key a vault file answers to (`20_Projects/A/My Note.md` → `my note`). */
    fun keyOfPath(path: String): String = keyOf(path)

    private fun decode(raw: String): String = try {
        java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
    } catch (e: IllegalArgumentException) {
        raw
    }
}
