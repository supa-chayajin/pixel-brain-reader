package cloud.wafflecommons.pixelbrainreader.data.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter

data class ImportResult(val title: String, val markdownContent: String)

object ContentSanitizer {
    // Simple URL regex, can be improved
    private val URL_REGEX = "^(https?://.+)$".toRegex(RegexOption.IGNORE_CASE)

    suspend fun processSharedContent(text: String): ImportResult = withContext(Dispatchers.IO) {
        val trimmedText = text.trim()
        
        if (URL_REGEX.matches(trimmedText)) {
             try {
                 // Fetch URL content
                 val doc = Jsoup.connect(trimmedText)
                    .userAgent("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36")
                    .timeout(10000)
                    .get()
                 
                 val title = doc.title().ifBlank { "Untitled Link" }
                 
                 // Basic Sanitization: Remove clutter
                 doc.select("script, style, nav, footer, header, aside, noscript, iframe").remove()
                 // Remove usually useless classes (heuristic)
                 doc.select(".ad, .advertisement, .cookie-banner, .subscribe-popup").remove()
                 
                 val html = doc.body().html()
                 
                 // Convert to Markdown
                 val converter = FlexmarkHtmlConverter.builder().build()
                 val markdown = converter.convert(html)
                 
                 // Add Source link at the bottom
                 val finalContent = "$markdown\n\n---\nSource: [$trimmedText]($trimmedText)"
                 
                 ImportResult(title, finalContent)
             } catch (e: Exception) {
                 // Fallback if offline or fail
                 ImportResult("Bookmark", "[$trimmedText]($trimmedText)\n\n> Error fetching content: ${e.message}")
             }
        } else {
             // Check if input is raw HTML block
             if (trimmedText.startsWith("<") && trimmedText.contains(">")) {
                  try {
                      val converter = FlexmarkHtmlConverter.builder().build()
                      val markdown = converter.convert(trimmedText)
                      ImportResult("Imported Snippet", markdown)
                  } catch (e: Exception) {
                      ImportResult("Imported Text", trimmedText)
                  }
             } else {
                  // Plain Text
                  // Title from first line?
                  val lines = trimmedText.lineSequence().take(1).toList()
                  val potentialTitle = if (lines.isNotEmpty()) lines[0].take(50) else "Imported Note"
                  ImportResult(potentialTitle, trimmedText)
             }
        }
    }
}
