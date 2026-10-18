package cloud.wafflecommons.pixelbrainreader.data.web

/**
 * What another app shared, read from its intent's extras. Browsers send the page's address (and
 * its title as the subject); other apps "Title https://…", a sentence around the link, or a
 * highlighted passage in quotes followed by a link to it. [passage]: the shared text that isn't
 * the link or the title, kept in the note as a quote.
 */
data class SharedContent(val url: String?, val titleHint: String?, val passage: String?) {

    companion object {
        private val URL = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)
        private const val TRAILING = ".,;:!?»”’'\""
        private val CLOSING = mapOf(')' to '(', ']' to '[', '}' to '{')
        private val QUOTES = mapOf('"' to '"', '“' to '”', '«' to '»', '„' to '“', '‘' to '’', '\'' to '\'')
        private const val MAX_TITLE = 150
        private const val MAX_TEXT_TITLE = 80

        fun of(text: String?, subject: String?): SharedContent {
            val body = text?.trim().orEmpty()
            val match = URL.find(body)
            val url = match?.value?.let(::trimTrailing)
            val rest = (if (match != null && url != null) body.removeRange(match.range.first, match.range.first + url.length) else body)
                .trim().trim('-', '–', '—', ':', '|', '·').trim()
            val subjectHint = subject?.trim()?.takeIf { it.isNotEmpty() && !URL.matches(it) }?.take(MAX_TITLE)
            if (url == null) {
                val firstLine = rest.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.trimStart('#', ' ')
                return SharedContent(null, subjectHint ?: firstLine?.take(MAX_TEXT_TITLE), rest.ifEmpty { null })
            }
            val quoted = unquote(rest)
            return when {
                rest.isEmpty() -> SharedContent(url, subjectHint, null)
                quoted != null -> SharedContent(url, subjectHint, quoted)
                subjectHint != null -> SharedContent(url, subjectHint, rest.takeIf { it != subjectHint })
                '\n' !in rest && rest.length <= MAX_TITLE -> SharedContent(url, rest, null)
                else -> SharedContent(url, null, rest)
            }
        }

        /**
         * The link without the punctuation that ends the sentence around it. A closing bracket
         * goes only when unbalanced: "(see https://a.example/b)" but ".../wiki/Kotlin_(langage)".
         */
        private fun trimTrailing(url: String): String {
            var end = url.length
            while (end > 0) {
                val last = url[end - 1]
                val open = CLOSING[last]
                val strays = if (open != null) url.take(end).count { it == last } > url.take(end).count { it == open } else last in TRAILING
                if (!strays) break
                end--
            }
            return url.take(end)
        }

        /** The text inside matching quotes ("…", “…”, «…»), or null when it isn't quoted. */
        private fun unquote(text: String): String? {
            if (text.length < 3) return null
            val close = QUOTES[text.first()] ?: return null
            return if (text.last() == close) text.substring(1, text.length - 1).trim().ifEmpty { null } else null
        }
    }
}
