package cloud.wafflecommons.pixelbrainreader.ui.utils

import io.noties.markwon.AbstractMarkwonPlugin
import java.util.regex.Pattern

/**
 * Turns Obsidian image embeds (`![[image.png]]`, `![[image.png|300]]`) into standard markdown
 * images. [resolveUri] maps an embed target to a loadable URI (or null when unknown); it must be
 * a pure in-memory lookup — this runs while parsing, and the plugin never touches the disk.
 * Unknown targets become an `<obsidian-image>` tag that the HTML plugin resolves by path.
 */
class ObsidianImagePlugin(
    private val resolveUri: (target: String) -> String? = { null }
) : AbstractMarkwonPlugin() {

    private val pattern = Pattern.compile("!\\[\\[(.*?)\\]\\]")

    override fun processMarkdown(markdown: String): String {
        val matcher = pattern.matcher(markdown)
        val sb = StringBuffer()
        while (matcher.find()) {
            val rawTarget = matcher.group(1) ?: continue
            val target = rawTarget.substringBefore("|").trim()
            val uri = resolveUri(target)
            val replacement = if (uri != null) {
                "![${target.substringAfterLast('/')}]($uri)"
            } else {
                "<obsidian-image path=\"${rawTarget.replace("\"", "&quot;")}\" />"
            }
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement))
        }
        matcher.appendTail(sb)
        return sb.toString()
    }
}
