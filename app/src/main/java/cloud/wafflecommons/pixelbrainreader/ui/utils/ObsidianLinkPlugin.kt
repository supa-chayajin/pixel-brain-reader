package cloud.wafflecommons.pixelbrainreader.ui.utils

import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import io.noties.markwon.AbstractMarkwonPlugin
import java.util.regex.Pattern

class ObsidianLinkPlugin(
    private val onWikiLinkClick: (String) -> Unit
) : AbstractMarkwonPlugin() {

    private val pattern = Pattern.compile("\\[\\[([^|\\]]+)(?:\\|([^\\]]+))?\\]\\]")

    override fun beforeSetText(textView: TextView, markdown: Spanned) {
        if (markdown !is Spannable) return
        val content = markdown.toString()
        val matcher = pattern.matcher(content)

        while (matcher.find()) {
            val target = matcher.group(1) ?: continue
            val label = matcher.group(2) ?: target
            
            val start = matcher.start()
            val end = matcher.end()

            // We can't easily "replace" the text in a Spannable that's already been rendered
            // However, we can apply a ClickableSpan over the region.
            // Obsidian typically shows [[Target|Label]] as "Label".
            // Since Markwon already parsed the bracketed text as literal text, 
            // we apply a span that hides the brackets and shows the label if different.
            
            // Actually, a better way is to do this in the MarkwonVisitor or a pre-processor.
            // For now, let's keep it simple: Make the whole [[...]] clickable.
            
            markdown.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        onWikiLinkClick(target)
                    }

                    override fun updateDrawState(ds: TextPaint) {
                        super.updateDrawState(ds)
                        ds.isUnderlineText = false
                    }
                },
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
}
