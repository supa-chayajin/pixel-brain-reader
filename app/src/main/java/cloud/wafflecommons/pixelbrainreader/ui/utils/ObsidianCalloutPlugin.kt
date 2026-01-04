package cloud.wafflecommons.pixelbrainreader.ui.utils

import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonVisitor
import org.commonmark.node.BlockQuote
import org.commonmark.node.Paragraph
import org.commonmark.node.Text
import java.util.regex.Pattern

class ObsidianCalloutPlugin : AbstractMarkwonPlugin() {

    private val CALLOUT_HEADER_PATTERN = Pattern.compile("^\\[!([a-zA-Z0-9_-]+)\\](.*?)$")

    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
        builder.on(BlockQuote::class.java) { visitor, blockQuote ->
            // Logic to check if it's an Obsidian Callout
            val firstChild = blockQuote.firstChild
            if (firstChild is Paragraph) {
                val textNode = firstChild.firstChild
                if (textNode is Text) {
                    val literal = textNode.literal
                    val firstLine = literal.split("\n")[0]
                    val matcher = CALLOUT_HEADER_PATTERN.matcher(firstLine)
                    if (matcher.find()) {
                        val type = matcher.group(1)!!.lowercase()
                        val userTitle = matcher.group(2)?.trim()
                        
                        // It's a callout!
                        // Suppress the first line [!type] by modifying the node
                        val lines = literal.split("\n")
                        textNode.literal = if (lines.size > 1) lines.drop(1).joinToString("\n") else ""
                        
                        val (colorHex, icon) = getCalloutStyle(type)
                        val color = android.graphics.Color.parseColor(colorHex)
                        val bg = (0x1F shl 24) or (color and 0x00FFFFFF)
                        val title = if (!userTitle.isNullOrBlank()) userTitle else type.replaceFirstChar { it.uppercase() }
                        
                        val start = visitor.length()
                        visitor.visitChildren(blockQuote)
                        visitor.setSpans(start, CalloutSpan(bg, color, icon, title))
                        return@on
                    }
                }
            }
            
            // If not a callout, do default processing
            val start = visitor.length()
            visitor.visitChildren(blockQuote)
            // Use default quote span
            visitor.setSpans(start, android.text.style.QuoteSpan())
        }
    }

    private fun getCalloutStyle(type: String): Pair<String, String> {
        return when (type) {
            "note" -> "#607D8B" to "✏️"
            "abstract", "summary", "tldr" -> "#00BCD4" to "📋"
            "info", "todo" -> "#2196F3" to "ℹ️"
            "tip", "hint", "important" -> "#00BCD4" to "💡"
            "success", "check", "done" -> "#4CAF50" to "✅"
            "question", "help", "faq" -> "#FF9800" to "❓"
            "warning", "caution", "attention" -> "#FFC107" to "⚠️"
            "failure", "fail", "missing" -> "#F44336" to "❌"
            "danger", "error", "bug" -> "#D32F2F" to "🔥"
            "example" -> "#9C27B0" to "🟣"
            "quote", "cite" -> "#9E9E9E" to "💬"
            else -> "#607D8B" to "📝"
        }
    }
}
