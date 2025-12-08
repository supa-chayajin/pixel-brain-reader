package cloud.wafflecommons.pixelbrainreader.data.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter
import android.text.Html
import android.text.Spanned
import android.os.Build

data class ImportResult(val title: String, val markdownContent: String)

object ContentSanitizer {
    // Simple URL regex, can be improved
    private val URL_REGEX = "^(https?://.+)$".toRegex(RegexOption.IGNORE_CASE)

    suspend fun processSharedContent(text: CharSequence): ImportResult = withContext(Dispatchers.IO) {
        val convertedText = if (text is Spanned) {
             // Convert Rich Text to HTML
             if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                 Html.toHtml(text, Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE)
             } else {
                 @Suppress("DEPRECATION")
                 Html.toHtml(text)
             }
        } else {
             text.toString()
        }

        val trimmedText = convertedText.trim()

        if (URL_REGEX.matches(trimmedText)) {
            try {
                // Fetch URL content
                val doc = Jsoup.connect(trimmedText)
                    .userAgent("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36")
                    .timeout(10000)
                    .get()

                val title = doc.title().ifBlank { "Untitled Link" }

                // Deep Cleaning for URL Content too
                val cleanHtml = deepCleanHtml(doc.body().html())

                // Convert to Markdown
                val converter = FlexmarkHtmlConverter.builder().build()
                val markdown = converter.convert(cleanHtml)

                // Add Source link at the bottom
                val finalContent = "$markdown\n\n---\nSource: [$trimmedText]($trimmedText)"

                ImportResult(title, finalContent)
            } catch (e: Exception) {
                // Fallback if offline or fail
                ImportResult("Bookmark", "[$trimmedText]($trimmedText)\n\n> Error fetching content: ${e.message}")
            }
        } else {
            // Check if input is likely HTML (Rich Text)
            // If it starts with <, we treat it as HTML. 
            // Also if it came from EXTRA_HTML_TEXT it's definitely HTML but we only have string here.
            // Requirement: "If input is Rich Text, convert to HTML first." 
            // (Assuming 'text' is the HTML representation or we treat it as such if it looks like it)
            if (trimmedText.startsWith("<") && trimmedText.contains(">")) {
                try {
                    val cleanHtml = deepCleanHtml(trimmedText)
                    val converter = FlexmarkHtmlConverter.builder().build()
                    val markdown = converter.convert(cleanHtml)
                    ImportResult("Imported Snippet", markdown)
                } catch (e: Exception) {
                    ImportResult("Imported Text", trimmedText)
                }
            } else {
                // Plain Text
                val lines = trimmedText.lineSequence().take(1).toList()
                val potentialTitle = if (lines.isNotEmpty()) lines[0].take(50) else "Imported Note"
                ImportResult(potentialTitle, trimmedText)
            }
        }
    }

    private fun deepCleanHtml(rawHtml: String): String {
        // Parse the body fragment
        val doc = Jsoup.parseBodyFragment(rawHtml)
        
        // 1. Strip classes and styles (attributes)
        val allElements = doc.select("*")
        for (element in allElements) {
            element.removeAttr("class")
            element.removeAttr("style")
            element.removeAttr("id") // Also often used for styling
        }
        
        // 2. Unwrap non-semantic tags (div, span) but keep their content
        doc.select("div, span, article, section, nav, aside, main, header, footer").forEach { 
            it.unwrap() // Removes the tag but keeps children/text
        }

        // 3. Keep only semantic tags: p, h1-h6, ul, li, b, i, code, strong, em, pre, br
        // Removing script/style tags completely
        doc.select("script, style").remove()
        
        // At this point, we have unwrapped containers. 
        // We rely on Jsoup to maintain the structure of remaining tags.
        // Flexmark will handle the rest.
        
        return doc.body().html()
    }
}
