package cloud.wafflecommons.pixelbrainreader.data.web

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter
import com.vladsch.flexmark.util.data.MutableDataSet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.text.Normalizer
import java.util.IdentityHashMap

/** How much of the page made it: the article, part of it, or nothing usable (script-built or paywalled page). */
enum class ClipQuality { FULL, PARTIAL, EMPTY }

/** A web page as a note: its metadata and its article body in markdown. */
data class WebClip(
    val url: String,
    val title: String,
    val siteName: String?,
    val author: String?,
    val published: String?,
    val description: String?,
    val heroImage: String?,
    val markdown: String,
    val words: Int,
    val isVideo: Boolean = false,
    /** The page's layout was recognised (a video, a Q&A thread): the clip is whole, however short. */
    val whole: Boolean = isVideo
) {
    val quality: ClipQuality
        get() = when {
            whole || words >= FULL_WORDS -> ClipQuality.FULL
            words >= PARTIAL_WORDS -> ClipQuality.PARTIAL
            else -> ClipQuality.EMPTY
        }

    private companion object {
        const val FULL_WORDS = 150
        const val PARTIAL_WORDS = 40
    }
}

/**
 * Reader mode for imports: keeps a page's article, drops its chrome, and converts it to
 * Obsidian-flavoured markdown — a compact port of the ideas of Mozilla's Readability. Metadata comes
 * from JSON-LD first, then Open Graph / Twitter tags, then the page title. Links and images become
 * absolute, lazy-loaded images get their real source, code blocks keep their language, the
 * article's own H1 gives way to the note's (rule R3).
 *
 * Pure (jsoup + flexmark): the network lives in the callers.
 */
object WebClipper {

    private val UNLIKELY = Regex(
        "-ad-|ai2html|banner|breadcrumbs|combx|comment|community|cover-wrap|disqus|extra|footer|gdpr|header|legends|menu|" +
            "related|remark|replies|rss|shoutbox|sidebar|skyscraper|social|sponsor|supplemental|ad-break|agegate|pagination|" +
            "pager|popup|yom-remote|newsletter|subscribe|cookie|consent|share|sharing|promo|outbrain|taboola|recirc",
        RegexOption.IGNORE_CASE
    )
    private val MAYBE = Regex("and|article|body|column|content|main|shadow", RegexOption.IGNORE_CASE)
    private val POSITIVE = Regex("article|body|content|entry|hentry|h-entry|main|page|post|text|blog|story|prose|markdown", RegexOption.IGNORE_CASE)
    private val NEGATIVE = Regex(
        "-ad-|hidden|banner|combx|comment|com-|contact|footer|footnote|gdpr|masthead|meta|outbrain|promo|related|scroll|share|" +
            "shoutbox|sidebar|skyscraper|sponsor|shopping|tags|tool|widget|newsletter|subscribe|cookie",
        RegexOption.IGNORE_CASE
    )
    private val BLOCK_TAGS = setOf(
        "a", "blockquote", "dl", "div", "img", "ol", "p", "pre", "table", "ul", "section", "article", "figure", "h1", "h2", "h3",
        "h4", "h5", "h6", "header", "footer", "main", "aside", "nav", "form", "iframe", "video", "picture"
    )
    private val TITLE_SEPARATORS = Regex("""\s+[|–—·•»\-]\s+""")
    private val ARTICLE_TYPES = setOf(
        "article", "newsarticle", "blogposting", "techarticle", "scholarlyarticle", "report", "reportagenewsarticle",
        "analysisnewsarticle", "opinionnewsarticle", "socialmediaposting", "webpage", "howto", "recipe"
    )
    private val SPACES = Regex("""\s+""")
    private val LANGUAGE_CLASS = Regex("""(?:^|\s)(?:mw-highlight-lang-|language-|lang-|highlight-source-|brush:\s*)([A-Za-z0-9+#_.\-]+)""")
    private val NO_LANGUAGE = setOf("none", "plaintext", "plain", "text", "txt", "nohighlight")
    private const val LANGUAGE_ATTR = "data-pbr-lang"
    private val TRACKING_PARAM = Regex("""^(utm_[a-z]+|fbclid|gclid|dclid|mc_cid|mc_eid|igshid|ref_src|_hsenc|_hsmi|mkt_tok)$""", RegexOption.IGNORE_CASE)

    fun clip(html: String, url: String): WebClip = clip(Jsoup.parse(html, url), url)

    fun clip(doc: Document, url: String): WebClip {
        val meta = Metadata.read(doc, url)
        val thread = questionAndAnswers(doc)?.also { prepare(it, meta.title, Pass.LOOSE) }
        val article = thread ?: bestArticle(doc, meta.title)
        val markdown = article?.let { toMarkdown(it) }.orEmpty()
        return WebClip(
            url = cleanUrl(meta.canonical ?: url),
            title = meta.title,
            siteName = meta.siteName,
            author = meta.author,
            published = meta.published,
            description = meta.description,
            heroImage = meta.image?.let(::cleanUrl)?.takeIf { image -> !inArticle(image, markdown) },
            markdown = markdown,
            words = markdown.split(SPACES).count { word -> word.any(Char::isLetterOrDigit) },
            whole = thread != null
        )
    }

    /** The page's image is in the article already, maybe as a thumbnail of the same file ("250px-Kodee.svg.png"). */
    private fun inArticle(image: String, markdown: String): Boolean {
        val file = image.substringBefore('?').substringAfterLast('/')
        return markdown.contains(image) || (file.length >= MIN_DISTINCT_FILE_NAME && markdown.contains(file))
    }

    private const val MIN_DISTINCT_FILE_NAME = 12

    private val TITLE_TAG = Regex("(?is)<title[^>]*>(.*?)</title>")

    /** Whole titles of bot checks and block pages (an article may well have "Un instant" in its title). */
    private val BLOCKED_TITLE = Regex(
        "^(?:just a moment|un instant|einen moment|attention required!?(?: \\| cloudflare)?|access denied|accès refusé|accès restreint|" +
            "access to this page has been denied|are you a robot\\??|verify you are human|human verification|robot check|" +
            "checking your browser|please enable (?:javascript|cookies)|403 forbidden|request blocked|request rejected|" +
            "pardon our interruption|ddos-guard|security check|vérification de sécurité)[\\s.…!?]*$",
        RegexOption.IGNORE_CASE
    )

    /**
     * Markup only challenge and block pages carry. Not the scripts protected sites inject into
     * every real page (Cloudflare's `/cdn-cgi/challenge-platform/scripts/jsd/`, DataDome's tag).
     */
    private val BLOCKED_MARKUP = Regex(
        "_cf_chl_opt|cf-browser-verification|challenge-platform/h/[a-z]/orchestrate|Incapsula incident ID|px-captcha|captcha-delivery\\.com",
        RegexOption.IGNORE_CASE
    )

    /** A bot check or a block page instead of the page: the import must not keep it as the article. */
    fun looksBlocked(html: String): Boolean {
        val title = TITLE_TAG.find(html.take(BLOCKED_SCAN))?.groupValues?.get(1)
            ?.let { Parser.unescapeEntities(it, false) }?.replace(SPACES, " ")?.trim().orEmpty()
        // "Accès restreint - Le Monde": the site's name may follow.
        val head = title.split(TITLE_SEPARATORS).first()
        return BLOCKED_TITLE.matches(title) || BLOCKED_TITLE.matches(head) || BLOCKED_MARKUP.containsMatchIn(html.take(BLOCKED_SCAN))
    }

    private const val BLOCKED_SCAN = 200_000

    /** [url] without the tracking parameters marketing links carry, nor a highlight's `#:~:text=` directive. */
    fun cleanUrl(url: String): String {
        val fragment = url.substringAfter('#', "").substringBefore(":~:").let { if (it.isEmpty()) "" else "#$it" }
        val address = url.substringBefore('#')
        val query = address.substringAfter('?', "")
        val kept = query.split('&').filter { param -> param.isNotEmpty() && !TRACKING_PARAM.matches(param.substringBefore('=')) }
        val base = address.substringBefore('?')
        return base + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + fragment
    }

    /** The page's own description (its meta tags), without reading the article. */
    fun describe(html: String, url: String): String? = Metadata.read(Jsoup.parse(html, url), url).description

    /** A shared HTML selection (no page around it): cleaned and converted whole, no article detection. */
    fun fragment(html: String, baseUrl: String? = null): String {
        val body = Jsoup.parseBodyFragment(html, baseUrl.orEmpty()).body()
        fixLazyImages(body)
        body.select("script, style, link, meta, template, svg, canvas, noscript, form, button, input, select, textarea, dialog, object, embed").remove()
        embedsAsLinks(body)
        prepare(body, title = "", pass = Pass.LOOSE)
        return toMarkdown(body)
    }

    // --- Metadata -------------------------------------------------------------

    private data class Metadata(
        val title: String,
        val siteName: String?,
        val author: String?,
        val published: String?,
        val description: String?,
        val image: String?,
        val canonical: String?
    ) {
        companion object {
            fun read(doc: Document, url: String): Metadata {
                val ld = jsonLd(doc)
                val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
                val declaredSite = meta(doc, "og:site_name") ?: meta(doc, "application-name")
                val ogTitle = meta(doc, "og:title", "twitter:title")?.let { splitTitle(it, declaredSite, host) }
                val tagTitle = splitTitle(doc.title(), declaredSite, host)
                // og:title first: JSON-LD "headline" is sometimes a description (Wikipedia).
                val title = (ogTitle?.title ?: ld?.headline ?: tagTitle?.title)
                    ?.let(::oneLine)?.takeIf { it.isNotBlank() }
                    ?: doc.selectFirst("h1")?.text()?.let(::oneLine)?.takeIf { it.isNotBlank() }
                    ?: host.ifEmpty { "Web page" }
                // The site as it names itself: declared, else the title's suffix ("… — Wikipédia"), else the publisher.
                val siteName = declaredSite ?: ogTitle?.site ?: tagTitle?.site ?: ld?.publisher
                val author = ld?.author ?: meta(doc, "author", "article:author", "twitter:creator", "parsely-author", "sailthru.author")
                    ?.takeIf { !it.startsWith("http") }
                val published = (ld?.published ?: meta(doc, "article:published_time", "og:article:published_time", "datePublished", "date", "dc.date", "parsely-pub-date"))
                    ?.take(10)?.takeIf { ISO_DATE.matches(it) }
                val image = (ld?.image ?: meta(doc, "og:image", "og:image:url", "twitter:image", "twitter:image:src"))?.let { absolute(doc, it) }
                val canonical = (doc.selectFirst("link[rel=canonical]")?.attr("abs:href")?.takeIf { it.startsWith("http") }
                    ?: meta(doc, "og:url")?.takeIf { it.startsWith("http") })
                return Metadata(
                    title = title,
                    siteName = siteName?.let(::oneLine),
                    author = author?.let(::oneLine),
                    published = published,
                    description = (ld?.description ?: meta(doc, "og:description", "description", "twitter:description"))?.let(::oneLine),
                    image = image,
                    canonical = canonical
                )
            }

            private fun meta(doc: Document, vararg keys: String): String? {
                for (key in keys) {
                    for (attr in listOf("property", "name", "itemprop")) {
                        doc.selectFirst("meta[$attr=$key]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
                return null
            }

            /**
             * "Title | Site", "Title — Site name": the title, and the site's name when it was there.
             * Otherwise a suffix is the site's name only when the address or the declared name says
             * so ("Wikipédia" on wikipedia.org, "MDN" for "MDN Web Docs"): "Mon article - partie 2"
             * keeps its end.
             */
            private fun splitTitle(raw: String, siteName: String?, host: String): SplitTitle? {
                val title = raw.trim().takeIf { it.isNotEmpty() } ?: return null
                val separators = TITLE_SEPARATORS.findAll(title).toList()
                if (separators.isEmpty()) return SplitTitle(title, null)
                // Cut at a separator rather than re-join the parts: the title keeps its own dashes.
                val first = title.substring(0, separators.first().range.first).trim()
                val afterFirst = title.substring(separators.first().range.last + 1).trim()
                val last = title.substring(separators.last().range.last + 1).trim()
                val beforeLast = title.substring(0, separators.last().range.first).trim()
                if (siteName != null && last.equals(siteName, ignoreCase = true)) return SplitTitle(beforeLast, siteName)
                if (siteName != null && first.equals(siteName, ignoreCase = true)) return SplitTitle(afterFirst, siteName)
                val named = last.split(' ').size <= 4 && (
                    words(last).any { it.length >= 3 && it in words(host).joinToString("") } ||
                        (siteName != null && words(last).all { it in words(siteName) })
                    )
                return if (named) SplitTitle(beforeLast, last) else SplitTitle(title, null)
            }

            /** Lower-case words without accents: "Wikipédia" → "wikipedia". */
            private fun words(text: String): List<String> =
                Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "").lowercase().split(NON_ALPHANUMERIC).filter { it.isNotEmpty() }

            private fun absolute(doc: Document, maybeRelative: String): String =
                runCatching { java.net.URI(doc.baseUri()).resolve(maybeRelative.trim()).toString() }.getOrDefault(maybeRelative)

            private fun oneLine(text: String): String = text.replace(SPACES, " ").trim()
        }
    }

    private data class SplitTitle(val title: String, val site: String?)

    private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    private val MARKS = Regex("""\p{M}+""")
    private val NON_ALPHANUMERIC = Regex("""[^\p{L}\p{N}]+""")

    private data class LinkedData(
        val headline: String?,
        val author: String?,
        val published: String?,
        val publisher: String?,
        val description: String?,
        val image: String?
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The first JSON-LD node that describes an article (or a web page), searched through arrays and @graph. */
    private fun jsonLd(doc: Document): LinkedData? {
        val nodes = doc.select("script[type=application/ld+json]").flatMap { script ->
            runCatching { flatten(json.parseToJsonElement(script.data())) }.getOrDefault(emptyList())
        }
        val node = nodes.firstOrNull { types(it).any { t -> t in ARTICLE_TYPES && t != "webpage" } }
            ?: nodes.firstOrNull { "webpage" in types(it) }
            ?: return null
        return LinkedData(
            headline = string(node["headline"]) ?: string(node["name"]),
            author = names(node["author"]).joinToString(", ").ifBlank { null },
            published = string(node["datePublished"]) ?: string(node["dateCreated"]),
            publisher = names(node["publisher"]).firstOrNull(),
            description = string(node["description"]),
            image = images(node["image"]).firstOrNull()
        )
    }

    private fun flatten(element: JsonElement): List<JsonObject> = when (element) {
        is JsonArray -> element.flatMap(::flatten)
        is JsonObject -> listOf(element) + (element["@graph"]?.let(::flatten) ?: emptyList())
        else -> emptyList()
    }

    private fun types(node: JsonObject): List<String> = when (val type = node["@type"]) {
        is JsonPrimitive -> listOfNotNull(type.contentOrNull?.lowercase())
        is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() }
        else -> emptyList()
    }

    private fun string(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun names(element: JsonElement?): List<String> = when (element) {
        is JsonPrimitive -> listOfNotNull(string(element))
        is JsonObject -> listOfNotNull(string(element["name"]))
        is JsonArray -> element.flatMap(::names)
        else -> emptyList()
    }

    private fun images(element: JsonElement?): List<String> = when (element) {
        is JsonPrimitive -> listOfNotNull(string(element))
        is JsonObject -> listOfNotNull(string(element["url"]) ?: string(element["contentUrl"]))
        is JsonArray -> element.flatMap(::images)
        else -> emptyList()
    }

    // --- Article extraction (after Readability) --------------------------------

    /** Readability's passes: strict first, then without the class/id filters, so a page whose wrapper has an unlucky class still yields its article. */
    private enum class Pass(val stripUnlikely: Boolean, val weightClasses: Boolean, val cleanConditionally: Boolean) {
        STRICT(true, true, true), KEEP_UNLIKELY(false, true, true), NO_WEIGHTS(false, false, true), LOOSE(false, false, false)
    }

    private const val ENOUGH_TEXT = 500

    private fun bestArticle(doc: Document, title: String): Element? {
        var best: Element? = null
        for (pass in Pass.entries) {
            val article = extractArticle(doc, title, pass) ?: continue
            prepare(article, title, pass)
            val length = article.text().length
            if (length >= ENOUGH_TEXT) return article
            if (best == null || length > best.text().length) best = article
        }
        return best
    }

    private fun extractArticle(source: Document, title: String, pass: Pass): Element? {
        val doc = source.clone()
        val body = doc.body() ?: return null
        fixLazyImages(body)
        body.select("script, style, link, meta, template, svg, canvas, noscript, form, button, input, select, textarea, dialog, object, embed").remove()
        body.select("nav, aside, footer").remove()
        body.select("[role=navigation], [role=menu], [role=menubar], [role=complementary], [role=contentinfo], [role=search], [role=dialog], [role=alertdialog], [aria-hidden=true], [hidden]").remove()
        body.select("[style~=(?i)display\\s*:\\s*none], [style~=(?i)visibility\\s*:\\s*hidden]").remove()
        embedsAsLinks(body)
        if (pass.stripUnlikely) removeUnlikely(body, title)
        divsWithoutBlocksAsParagraphs(body)

        val scores = IdentityHashMap<Element, Double>()
        for (element in body.select("p, pre, td, section, h2, h3, h4, h5, h6, blockquote, li")) {
            val text = element.text()
            if (text.length < 25) continue
            val score = 1.0 + text.count { it == ',' || it == '，' } + minOf(text.length / 100, 3)
            var level = 0
            var ancestor = element.parent()
            while (ancestor != null && level < 5 && ancestor !== body.parent()) {
                if (ancestor.tagName() !in setOf("body", "html") || ancestor === body) {
                    if (ancestor !in scores) scores[ancestor] = baseScore(ancestor, pass.weightClasses)
                    val divider = when (level) { 0 -> 1.0; 1 -> 2.0; else -> level * 3.0 }
                    scores[ancestor] = scores.getValue(ancestor) + score / divider
                }
                ancestor = ancestor.parent()
                level++
            }
        }
        val top = scores.entries
            .map { (element, score) -> element to score * (1 - linkDensity(element)) }
            .maxByOrNull { it.second }
            ?: return body
        val (candidate, topScore) = top
        if (candidate === body || candidate.tagName() == "html") return body

        // Siblings that belong to the article too (a byline, a lede, the rest of a split article).
        val parent = candidate.parent() ?: return candidate
        val threshold = maxOf(10.0, topScore * 0.2)
        val article = doc.createElement("div")
        for (sibling in parent.children().toList()) {
            val keep = sibling === candidate || run {
                val bonus = if (sibling.className().isNotEmpty() && sibling.className() == candidate.className()) topScore * 0.2 else 0.0
                val score = (scores[sibling] ?: 0.0) * (1 - linkDensity(sibling)) + bonus
                when {
                    score >= threshold -> true
                    sibling.tagName() == "p" -> {
                        val text = sibling.text()
                        val density = linkDensity(sibling)
                        (text.length > 80 && density < 0.25) || (text.length in 1..80 && density == 0.0 && Regex("""\.( |$)""").containsMatchIn(text))
                    }
                    else -> false
                }
            }
            if (keep) article.appendChild(sibling.clone())
        }
        return article
    }

    private fun baseScore(element: Element, weightClasses: Boolean): Double {
        val tag = when (element.tagName()) {
            "div", "article", "main" -> 5.0
            "pre", "td", "blockquote" -> 3.0
            "address", "ol", "ul", "dl", "dd", "dt", "li", "form" -> -3.0
            "h1", "h2", "h3", "h4", "h5", "h6", "th" -> -5.0
            else -> 0.0
        }
        return tag + if (weightClasses) classWeight(element) else 0.0
    }

    private fun classWeight(element: Element): Double {
        var weight = 0.0
        for (value in listOf(element.className(), element.id())) {
            if (value.isEmpty()) continue
            if (NEGATIVE.containsMatchIn(value)) weight -= 25
            if (POSITIVE.containsMatchIn(value)) weight += 25
        }
        if (element.tagName() == "article" || element.tagName() == "main" || element.attr("itemprop") == "articleBody") weight += 25
        return weight
    }

    private fun linkDensity(element: Element): Double {
        val length = element.text().length
        if (length == 0) return 0.0
        val links = element.select("a").sumOf { a -> if (a.attr("href").startsWith("#")) a.text().length * 0.3 else a.text().length.toDouble() }
        return links / length
    }

    /**
     * Drops elements whose class or id says navigation, ads, comments… unless they also say content,
     * or hold the article itself (`main`, `article`, the page's own H1): a layout wrapper named
     * "with-sidebar" must not take the article with it.
     */
    private fun removeUnlikely(body: Element, title: String) {
        val titleKey = title
        for (element in body.select("*").toList()) {
            if (element === body || element.parent() == null) continue
            if (element.tagName() in setOf("a", "body", "html", "article", "main", "table", "tbody", "thead", "tr", "td", "th", "pre", "code")) continue
            if (element.parents().any { it.tagName() in setOf("table", "pre", "code") }) continue
            val match = element.className() + " " + element.id()
            if (match.isBlank()) continue
            if (!UNLIKELY.containsMatchIn(match) || MAYBE.containsMatchIn(match)) continue
            val holdsArticle = element.selectFirst("main, article, [role=main], [itemprop=articleBody]") != null ||
                element.select("h1").any { sameTitle(it.text(), titleKey) }
            if (!holdsArticle) element.remove()
        }
    }

    /** A div holding only text and inline elements reads as a paragraph (many sites write them so). */
    private fun divsWithoutBlocksAsParagraphs(body: Element) {
        for (div in body.select("div").toList()) {
            if (div.parent() == null) continue
            if (div.select("*").none { it !== div && it.tagName() in BLOCK_TAGS }) div.tagName("p")
        }
    }

    /** Lazy-loaded images carry their real source in data attributes; placeholders and empty images go. */
    private fun fixLazyImages(body: Element) {
        // A <noscript> fallback image replaces the lazy placeholder before it.
        for (noscript in body.select("noscript").toList()) {
            val fallback = Jsoup.parseBodyFragment(noscript.html()).selectFirst("img") ?: continue
            val previous = noscript.previousElementSibling()
            if (previous != null && previous.tagName() == "img") previous.attr("src", fallback.attr("src")) else noscript.after(fallback.outerHtml())
        }
        for (img in body.select("img").toList()) {
            val current = img.attr("src")
            val placeholder = current.isBlank() || current.startsWith("data:") || Regex("(?i)placeholder|blank\\.gif|spacer|1x1|pixel\\.gif").containsMatchIn(current)
            if (placeholder) {
                val real = listOf("data-src", "data-original", "data-lazy-src", "data-url", "data-hi-res-src", "data-lazy")
                    .firstNotNullOfOrNull { img.attr(it).takeIf { v -> v.isNotBlank() && !v.startsWith("data:") } }
                    ?: bestFromSrcset(img.attr("data-srcset").ifBlank { img.attr("srcset") })
                    ?: img.parent()?.takeIf { it.tagName() == "picture" }?.selectFirst("source[srcset]")?.attr("srcset")?.let(::bestFromSrcset)
                if (real == null) img.remove() else img.attr("src", real)
            }
        }
    }

    private fun bestFromSrcset(srcset: String): String? = srcset.split(",")
        .mapNotNull { candidate ->
            val parts = candidate.trim().split(SPACES)
            val url = parts.firstOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("data:") } ?: return@mapNotNull null
            val width = parts.getOrNull(1)?.trimEnd('w', 'x')?.toDoubleOrNull() ?: 1.0
            url to width
        }
        .maxByOrNull { it.second }?.first

    /** Video embeds can't play in a note: they become links. */
    private fun embedsAsLinks(body: Element) {
        for (frame in body.select("iframe, video").toList()) {
            val src = frame.attr("abs:src").ifBlank { frame.selectFirst("source")?.attr("abs:src").orEmpty() }
            if (src.startsWith("http") && Regex("(?i)youtube|youtu\\.be|vimeo|dailymotion|\\.mp4").containsMatchIn(src)) {
                val link = Element("p")
                link.appendElement("a").attr("href", src.replace("/embed/", "/watch?v=")).text("▶ Vidéo")
                frame.replaceWith(link)
            } else {
                frame.remove()
            }
        }
    }

    /**
     * Stack Exchange Q&A (Stack Overflow, Super User…): the question and its best answers, accepted
     * first — a scorer would keep one block of the thread.
     */
    private fun questionAndAnswers(doc: Document): Element? {
        val question = doc.selectFirst("#question .js-post-body") ?: return null
        val article = doc.createElement("div")
        article.appendChild(question.clone())
        doc.select("#answers .answer")
            .sortedWith(compareByDescending<Element> { it.hasClass("accepted-answer") }.thenByDescending { it.attr("data-score").toIntOrNull() ?: 0 })
            .take(MAX_ANSWERS)
            .forEach { answer ->
                val body = answer.selectFirst(".js-post-body") ?: return@forEach
                val label = if (answer.hasClass("accepted-answer")) "Réponse acceptée" else "Réponse"
                article.appendElement("h2").text(label + (answer.attr("data-score").toIntOrNull()?.let { " (score $it)" }.orEmpty()))
                article.appendChild(body.clone())
            }
        return article
    }

    private const val MAX_ANSWERS = 3

    // --- Clean-up and conversion ------------------------------------------------

    /** The same title, give or take a suffix ("404 Not Found" and "404 Not Found – HTTP"). */
    private fun sameTitle(a: String, b: String): Boolean {
        val x = a.trim().lowercase()
        val y = b.trim().lowercase()
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || (x.length >= 8 && y.startsWith(x)) || (y.length >= 8 && x.startsWith(y))
    }

    private fun prepare(article: Element, title: String, pass: Pass) {
        // Each code block's language, before the attributes that carry it go.
        article.select("pre").forEach { pre -> codeLanguage(pre)?.let { pre.attr(LANGUAGE_ATTR, it) } }
        // Wikipedia and friends: edit links, citation markers, navigation and info boxes.
        article.select(
            ".mw-editsection, sup.reference, .navbox, .vertical-navbox, .noprint, .catlinks, .mw-jump-link, #toc, .toc, .portalbox, " +
                ".sistersitebox, .mw-authority-control, .infobox, .mw-empty-elt, .hatnote, .metadata, .ambox, .bandeau-container, " +
                ".bandeau-portail, .mw-indicators"
        ).remove()
        // Headings link to themselves on docs sites: keep their text only; icon-only anchors go.
        article.select("h1 a[href], h2 a[href], h3 a[href], h4 a[href], h5 a[href], h6 a[href]").forEach { a ->
            if (a.attr("href").startsWith("#") || a.absUrl("href").substringBefore('#') == a.baseUri().substringBefore('#')) a.unwrap()
        }
        article.select("a").forEach { a -> if (a.text().isBlank() && a.selectFirst("img") == null) a.remove() }
        // Cards linking a heading: the link goes inside the heading ("[## Title](…)" isn't markdown).
        for (a in article.select("a[href]:has(h1, h2, h3, h4, h5, h6)").toList()) {
            val href = a.absUrl("href").ifEmpty { a.attr("href") }
            a.select("h1, h2, h3, h4, h5, h6").forEach { heading ->
                val link = Element("a").attr("href", href)
                heading.childNodes().toList().forEach { link.appendChild(it) }
                heading.appendChild(link)
            }
            a.unwrap()
        }
        // Link lists and widgets that slipped through: mostly links, little text.
        if (pass.cleanConditionally) {
            for (element in article.select("ul, ol, div, section, table, p").toList()) {
                if (element.parent() == null || element.selectFirst("pre, img") != null) continue
                val text = element.text().length
                val density = linkDensity(element)
                val linkList = if (element.tagName() == "p") density > 0.8 && text < 160 else density > 0.5 && text < 600
                if (linkList || (pass.weightClasses && classWeight(element) < 0 && density > 0.2)) element.remove()
            }
        }
        // The note has its own H1 (R3): the article's title goes, other H1s become H2.
        article.select("h1").forEach { h1 -> if (sameTitle(h1.text(), title)) h1.remove() else h1.tagName("h2") }
        article.select("h2, h3").firstOrNull()?.takeIf { sameTitle(it.text(), title) }?.remove()
        // Absolute links and images; scripts in links go.
        article.select("a[href]").forEach { a ->
            val href = a.attr("abs:href")
            if (href.startsWith("http")) a.attr("href", cleanUrl(href)) else if (a.attr("href").startsWith("javascript:")) a.unwrap()
        }
        article.select("img").forEach { img ->
            val src = img.attr("abs:src")
            if (src.startsWith("http")) img.attr("src", cleanUrl(src)) else img.remove()
        }
        // Only what markdown keeps: a link's target, an image's source and text, table spans.
        article.select("*").forEach { el ->
            val keep = when (el.tagName()) {
                "a" -> setOf("href")
                "img" -> setOf("src", "alt")
                "td", "th" -> setOf("colspan", "rowspan")
                "pre" -> setOf(LANGUAGE_ATTR)
                else -> emptySet()
            }
            el.attributes().asList().map { it.key }.filter { it !in keep }.forEach(el::removeAttr)
        }
        // Figures: the image, then its caption in italics.
        for (figure in article.select("figure").toList()) {
            val caption = figure.selectFirst("figcaption")?.text()?.trim()
            figure.select("figcaption").remove()
            if (!caption.isNullOrEmpty()) figure.appendElement("p").appendElement("em").text(caption)
            figure.tagName("div")
        }
        // Empty paragraphs and wrappers. Not spans: a space between two words, or in code, is one.
        article.select("p, div, section").reversed().forEach { el ->
            if (el.parent() != null && el.text().isBlank() && el.select("img, pre, table, iframe, hr").isEmpty() && el.closest("pre") == null) el.remove()
        }
    }

    /** From `data-language` / `data-lang`, or a class: `language-kotlin`, `lang-js`, `highlight-source-go`, `mw-highlight-lang-kotlin`. */
    private fun codeLanguage(pre: Element): String? {
        val around = listOfNotNull(pre, pre.selectFirst("code"), pre.parent(), pre.parent()?.parent())
        val raw = around.firstNotNullOfOrNull { el -> el.attr("data-language").ifBlank { el.attr("data-lang") }.trim().ifEmpty { null } }
            ?: around.firstNotNullOfOrNull { el -> LANGUAGE_CLASS.find(el.className())?.groupValues?.get(1) }
        return raw?.lowercase()?.takeIf { it !in NO_LANGUAGE }
    }

    private fun toMarkdown(article: Element): String {
        // Code blocks are set aside and written back verbatim, with their language: converters
        // re-indent and escape code.
        val codes = mutableListOf<Pair<String, String>>()
        for (pre in article.select("pre").toList()) {
            if (pre.parent() == null) continue
            pre.select("br").forEach { it.replaceWith(TextNode("\n")) }
            // Highlighters that put each line in a block of its own: one line each.
            pre.select("div, p").filter { it !== pre }.forEach { line ->
                val next = (line.nextSibling() as? TextNode)?.wholeText.orEmpty()
                if (!line.wholeText().endsWith("\n") && !next.startsWith("\n")) line.after(TextNode("\n"))
            }
            val language = pre.attr(LANGUAGE_ATTR)
            codes += language to pre.wholeText().trimEnd().trimStart('\n')
            pre.replaceWith(Element("p").text("$CODE_PLACEHOLDER${codes.size - 1}"))
        }
        // Images too: the converter writes an image whose file is named like an emoji (cat.png,
        // star.svg) as ":cat:".
        val images = mutableListOf<String>()
        for (img in article.select("img").toList()) {
            val alt = img.attr("alt").replace(SPACES, " ").trim().replace("[", "(").replace("]", ")")
            val src = img.attr("src").replace(" ", "%20").replace("(", "%28").replace(")", "%29")
            images += "![$alt]($src)"
            img.replaceWith(TextNode("$IMAGE_PLACEHOLDER${images.size - 1}$PLACEHOLDER_END"))
        }
        val options = MutableDataSet()
            .set(FlexmarkHtmlConverter.SETEXT_HEADINGS, false)
            .set(FlexmarkHtmlConverter.OUTPUT_ATTRIBUTES_ID, false)
            .set(FlexmarkHtmlConverter.UNORDERED_LIST_DELIMITER, '-')
            .set(FlexmarkHtmlConverter.THEMATIC_BREAK, "---")
            .set(FlexmarkHtmlConverter.BR_AS_EXTRA_BLANK_LINES, false)
            .set(FlexmarkHtmlConverter.DIV_AS_PARAGRAPH, true)
            .set(FlexmarkHtmlConverter.TYPOGRAPHIC_QUOTES, false)
            .set(FlexmarkHtmlConverter.TYPOGRAPHIC_SMARTS, false)
        val converted = FlexmarkHtmlConverter.builder(options).build().convert(article.html())
            .replace("\\&", "&")
            // The converter spaces out "**bold**." (no language puts a space there).
            .replace(SPACE_BEFORE_PUNCTUATION, "$1")
            .replace(TRAILING_SPACES, "\n")
            .replace(EXTRA_BLANK_LINES, "\n\n")
            .replace(IMAGE_TOKEN) { images[it.groupValues[1].toInt()] }
        // Each fence takes the place of its placeholder, indented to stay in its list item or quote.
        return PLACEHOLDER_LINE.replace(converted) { match ->
            val (quote, marker, text) = LINE_START.find(match.groupValues[1])!!.destructured
            val (language, code) = codes[match.groupValues[2].toInt()]
            val fence = "`".repeat(maxOf(3, (BACKTICKS.findAll(code).maxOfOrNull { it.value.length } ?: 0) + 1))
            val indent = quote + " ".repeat(marker.length)
            val lines = (listOf(fence + language) + code.lines() + fence).map { line -> (indent + line).let { if (line.isEmpty()) it.trimEnd() else it } }
            when {
                // The fence opens the item (or the quote) itself.
                text.isBlank() -> (quote + marker + lines.first().removePrefix(indent)) + lines.drop(1).joinToString("") { "\n$it" }
                // Text came first on the line (an item's lead-in): the fence goes below it.
                else -> (quote + marker + text).trimEnd() + "\n" + indent.trimEnd() + "\n" + lines.joinToString("\n")
            }
        }.trim()
    }

    private const val CODE_PLACEHOLDER = "PBRCODEBLOCK"
    private const val IMAGE_PLACEHOLDER = "PBRIMAGE"
    private const val PLACEHOLDER_END = "Z"
    private val IMAGE_TOKEN = Regex("""$IMAGE_PLACEHOLDER(\d+)$PLACEHOLDER_END""")
    private val PLACEHOLDER_LINE = Regex("""(?m)^(.*?)$CODE_PLACEHOLDER(\d+)[ \t]*$""")
    private val LINE_START = Regex("""^([ \t>]*)((?:[-*+]|\d+[.)])[ \t]+)?(.*)$""")
    private val SPACE_BEFORE_PUNCTUATION = Regex("""(?<=\S)(\*{1,2}) (?=[,.)\]])""")
    private val BACKTICKS = Regex("`+")
    private val TRAILING_SPACES = Regex("""[ \t]+\n""")
    private val EXTRA_BLANK_LINES = Regex("""\n{3,}""")
}
