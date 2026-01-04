package cloud.wafflecommons.pixelbrainreader.ui.utils

import io.noties.markwon.AbstractMarkwonPlugin
import java.util.regex.Pattern

class ObsidianImagePlugin : AbstractMarkwonPlugin() {

    private val pattern = Pattern.compile("!\\[\\[(.*?)\\]\\]")

    override fun processMarkdown(markdown: String): String {
        val matcher = pattern.matcher(markdown)
        val sb = StringBuffer()
        while (matcher.find()) {
            val fileName = matcher.group(1) ?: continue
            // Transform ![[file.png]] -> <obsidian-image path="file.png" />
            matcher.appendReplacement(sb, "<obsidian-image path=\"$fileName\" />")
        }
        matcher.appendTail(sb)
        return sb.toString()
    }
}
