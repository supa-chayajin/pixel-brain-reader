package cloud.wafflecommons.pixelbrainreader.data.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter
import android.text.Html
import android.text.Spanned
import android.os.Build

data class ImportResult(val title: String, val markdownContent: String)

object ContentSanitizer {
    // Simple URL regex
    private val URL_REGEX = "^(https?://.+)$".toRegex(RegexOption.IGNORE_CASE)

    suspend fun processSharedContent(text: CharSequence): ImportResult = withContext(Dispatchers.IO) {
        var finalTitle = "Imported Content"
        
        // 1. FETCH & PARSE / NORMALIZE
        val document: Document = when {
            text is Spanned -> {
                // RTF Handling: Convert Android Rich Text (Spanned) to HTML
                val htmlString = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    Html.toHtml(text, Html.TO_HTML_PARAGRAPH_LINES_INDIVIDUAL)
                } else {
                    @Suppress("DEPRECATION")
                    Html.toHtml(text)
                }
                Jsoup.parseBodyFragment(htmlString)
            }
            URL_REGEX.matches(text.trim()) -> {
                // If text is a URL, fetch the HTML content
                try {
                    val conn = Jsoup.connect(text.toString().trim())
                        .userAgent("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36")
                        .timeout(10000)
                    val fetchedDoc = conn.get()
                    finalTitle = fetchedDoc.title().ifBlank { finalTitle }
                    fetchedDoc
                } catch (e: Exception) {
                    // Fallback: treat the URL as text content
                    Jsoup.parseBodyFragment("<p>${text}</p>")
                }
            }
            isRtf(text) -> {
                // Raw RTF String detected (e.g. starting with {\rtf)
                // Since we lack a raw RTF parser, we treat this as a code block to preserve data
                Jsoup.parseBodyFragment("<pre>${text}</pre>")
            }
            else -> {
                // Treat input string as potential Raw HTML
                Jsoup.parse(text.toString())
            }
        }

        // 2. LOCATE MAIN CONTENT (Prioritized)
        val mainContent = findMainContent(document)

        // 3. DEEP CLEANING (Recursive on the main content)
        val cleanHtml = deepCleanElement(mainContent)

        // 4. MARKDOWN CONVERSION
        val converter = FlexmarkHtmlConverter.builder().build()
        val rawMarkdown = converter.convert(cleanHtml)

        // 5. MARKDOWN POLISH
        // Strip excessive blank lines (3 or more newlines -> 2 newlines)
        val polishedMarkdown = rawMarkdown.replace(Regex("\\n{3,}"), "\n\n").trim()

        ImportResult(finalTitle, polishedMarkdown)
    }

    private fun isRtf(text: CharSequence): Boolean {
        // Basic heuristic for Raw RTF
        val s = text.toString().trim()
        return s.startsWith("{\\rtf") || s.startsWith("{ \\rtf")
    }

    private fun findMainContent(doc: Document): Element {
        val body = doc.body() ?: return doc // Fallback if no body

        // Priority 1: Schema.org Article / BlogPosting
        val runSchemaCheck = {
            val schemaCandidates = listOf(
                "http://schema.org/Article", "https://schema.org/Article",
                "http://schema.org/BlogPosting", "https://schema.org/BlogPosting"
            )
            var found: Element? = null
            for (type in schemaCandidates) {
                found = body.selectFirst("[itemtype=$type]")
                if (found != null) break
            }
            found
        }
        val schemaElement = runSchemaCheck()
        if (schemaElement != null) return schemaElement

        // Priority 2: Semantic <article> Tag
        val article = body.selectFirst("article")
        if (article != null) return article

        // Priority 3: Common IDs or Classes
        val candidates = listOf(
            "main", "content", "main-content", "article-body", 
            "post-content", "entry-content", "page-content"
        )
        
        for (id in candidates) {
            val element = body.getElementById(id)
            if (element != null) return element
        }

        for (cls in candidates) {
            val element = body.selectFirst("div.$cls")
            if (element != null) return element
        }

        // Fallback: body
        return body
    }

    private fun deepCleanElement(root: Element): String {
        // 1. REMOVE NOISE ELEMENTS (Tags)
        // Aggressive list including link, meta, object, embed
        root.select("script, style, noscript, iframe, svg, canvas, nav, footer, header, aside, form, button, input, object, embed, link, meta").remove()

        // 2. REMOVE NOISE BY CLASS/ID (Heuristic)
        val noisePatterns = listOf(
            "sidebar", "widget", "ad-", "ads", "advertisement", 
            "comment", "share", "related", "cookie", "newsletter", "popup", "modal", "banner", "social"
        )
        
        val allElements = root.select("*")
        val iter = allElements.iterator()
        while (iter.hasNext()) {
            val el = iter.next()
            val id = el.id().lowercase()
            val cls = el.className().lowercase()
            
            val isNoise = noisePatterns.any { pattern -> 
                id.contains(pattern) || cls.contains(pattern) 
            }
            
            if (isNoise) {
                el.remove()
            }
        }

        // 3. ATTRIBUTE STRIPPING & UNWRAPPING
        // We re-select elements because the DOM has changed (removals)
        val remainingElements = root.select("*")
        
        for (element in remainingElements) {
            if (element != root && !element.hasParent()) continue

            val tagName = element.tagName().lowercase()

            // Unwrap logic: div, span, article, section, main, header, footer
            if (tagName in listOf("div", "span", "article", "section", "main", "header", "footer")) {
                if (element != root) {
                    element.unwrap()
                    continue 
                }
            }

            // Clean Attributes (Strict Whitelist)
            val attributes = element.attributes().asList().map { it.key }
            for (attr in attributes) {
                val attrLower = attr.lowercase()
                
                var keep = false
                when (tagName) {
                    "a" -> if (attrLower == "href") keep = true
                    "img" -> if (attrLower == "src" || attrLower == "alt" || attrLower == "title") keep = true
                    "td", "th" -> if (attrLower == "colspan" || attrLower == "rowspan") keep = true
                }
                
                if (!keep) {
                    element.removeAttr(attr)
                }
            }
        }

        return root.html()
    }
}
