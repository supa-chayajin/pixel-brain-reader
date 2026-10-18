package cloud.wafflecommons.pixelbrainreader.data.vault

/** A fenced code block of a note: its language (empty when none), its code, the heading above it, its first line (1-based). */
data class CodeBlock(val path: String, val language: String, val code: String, val heading: String?, val line: Int)

/** The fenced code blocks of a note (E4), for the snippet index, runbooks and copy buttons. */
object CodeBlocks {

    fun extract(path: String, content: String): List<CodeBlock> {
        val blocks = mutableListOf<CodeBlock>()
        var heading: String? = null
        var fence: String? = null
        var language = ""
        var start = 0
        val code = StringBuilder()
        content.lines().forEachIndexed { index, line ->
            val stripped = line.trim()
            val open = fence
            if (open != null) {
                if (stripped.length >= open.length && stripped.all { it == open[0] }) {
                    blocks += CodeBlock(path, language, code.toString().trimEnd('\n'), heading, start)
                    fence = null
                    code.setLength(0)
                } else {
                    code.append(line).append('\n')
                }
                return@forEachIndexed
            }
            val marker = stripped.firstOrNull()?.takeIf { it == '`' || it == '~' }
            val length = marker?.let { m -> stripped.takeWhile { it == m }.length } ?: 0
            if (marker != null && length >= 3 && !(marker == '`' && stripped.drop(length).contains('`'))) {
                fence = marker.toString().repeat(length)
                language = stripped.drop(length).trim().substringBefore(' ').substringBefore('{').lowercase()
                start = index + 1
                return@forEachIndexed
            }
            if (stripped.startsWith("#")) {
                val level = stripped.takeWhile { it == '#' }.length
                if (level in 1..6 && stripped.getOrNull(level)?.isWhitespace() == true) heading = stripped.drop(level).trim()
            }
        }
        return blocks.filter { it.code.isNotBlank() }
    }
}
