package cloud.wafflecommons.pixelbrainreader.data.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebClipperTest {

    private val url = "https://blog.example.com/posts/kotlin-flows?utm_source=rss&id=7"
    private val sentence = "Flows are cold streams of values, collected in coroutines, and they compose with operators like map, " +
        "filter and combine, which makes them a good fit for screen state."

    private fun paragraphs(count: Int = 8) = (1..count).joinToString("") { "<p>$sentence Part $it.</p>" }

    private fun page(body: String, head: String = "") =
        "<!doctype html><html><head><title>Understanding flows | Example Blog</title>" +
            "<meta property=\"og:site_name\" content=\"Example Blog\">$head</head><body>$body</body></html>"

    @Test
    fun `keeps the article and drops the chrome around it`() {
        val clip = WebClipper.clip(
            page(
                "<header><nav><a href='/'>Home</a> | <a href='/about'>About</a></nav></header>" +
                    "<div class='layout'><article><h1>Understanding flows</h1>${paragraphs()}</article>" +
                    "<aside class='sidebar'><h3>Related posts</h3><ul><li><a href='/a'>A post</a></li></ul></aside></div>" +
                    "<div id='comments' class='comments'><p>Leave a comment, it helps a lot, really, thanks.</p></div>" +
                    "<footer>© Example Blog</footer>"
            ),
            url
        )

        assertEquals("Understanding flows", clip.title)
        assertEquals("Example Blog", clip.siteName)
        assertTrue(clip.markdown.contains("Part 1.") && clip.markdown.contains("Part 8."))
        listOf("Related posts", "Leave a comment", "About", "©").forEach { assertFalse(it, clip.markdown.contains(it)) }
        assertFalse("the article's H1 gives way to the note's", clip.markdown.contains("# Understanding flows"))
        assertEquals(ClipQuality.FULL, clip.quality)
    }

    @Test
    fun `reads the metadata from JSON-LD and cleans the canonical address`() {
        val ld = """{"@context":"https://schema.org","@graph":[{"@type":"WebSite","name":"Example"},
            {"@type":"BlogPosting","headline":"Understanding flows","author":[{"@type":"Person","name":"Ada"},{"name":"Linus"}],
            "datePublished":"2024-03-05T10:00:00Z","image":{"url":"https://cdn.example.com/hero.png?utm_medium=x"}}]}"""
        val clip = WebClipper.clip(
            page(
                "<article>${paragraphs()}</article>",
                head = "<script type='application/ld+json'>$ld</script>" +
                    "<link rel='canonical' href='https://blog.example.com/posts/kotlin-flows?utm_campaign=x&id=7'>"
            ),
            url
        )

        assertEquals("Ada, Linus", clip.author)
        assertEquals("2024-03-05", clip.published)
        assertEquals("https://blog.example.com/posts/kotlin-flows?id=7", clip.url)
        assertEquals("https://cdn.example.com/hero.png", clip.heroImage)
    }

    @Test
    fun `a layout wrapper named sidebar doesn't take the article with it`() {
        val clip = WebClipper.clip(
            page("<div class='with-sidebar'><main><h1>Understanding flows</h1>${paragraphs()}</main><div class='sidebar'><a href='/x'>X</a></div></div>"),
            url
        )

        assertTrue(clip.markdown.contains("Part 8."))
    }

    @Test
    fun `code blocks keep their language, their spaces and a fence longer than their backticks`() {
        val clip = WebClipper.clip(
            page(
                "<article>${paragraphs(4)}" +
                    "<div class='highlight mw-highlight-lang-kotlin'><pre><span class='k'>fun</span><span class='w'> </span><span class='nf'>main</span>() {}</pre></div>" +
                    "<pre data-language='jsx'><code><span>const a = 1;</span>\n<span>const b = 2;</span></code></pre>" +
                    "<pre class='language-markdown'><code>```kotlin\nval x = 1\n```</code></pre>" +
                    "<pre><code><div class='line'>first()</div><div class='line'>second()</div></code></pre>" +
                    "${paragraphs(2)}</article>"
            ),
            url
        )

        assertTrue(clip.markdown, clip.markdown.contains("```kotlin\nfun main() {}\n```"))
        assertTrue(clip.markdown, clip.markdown.contains("```jsx\nconst a = 1;\nconst b = 2;\n```"))
        assertTrue(clip.markdown, clip.markdown.contains("````markdown\n```kotlin\nval x = 1\n```\n````"))
        assertTrue(clip.markdown, clip.markdown.contains("```\nfirst()\nsecond()\n```"))
    }

    @Test
    fun `a code block in a list item stays in the item`() {
        val clip = WebClipper.clip(
            page("<article>${paragraphs(4)}<ol><li>List the files:<pre><code class='language-bash'>ls -la\ncd /tmp</code></pre></li></ol></article>"),
            url
        )

        val fence = clip.markdown.lines().dropWhile { !it.contains("```bash") }.take(4)
        assertEquals(4, fence.size)
        val indent = fence[0].substringBefore("```")
        assertTrue("indented under the item: '$indent'", indent.isNotEmpty() && indent.isBlank())
        assertEquals(listOf("${indent}ls -la", "${indent}cd /tmp", "$indent```"), fence.drop(1))
    }

    @Test
    fun `links and lazy images become absolute`() {
        val clip = WebClipper.clip(
            page(
                "<article>${paragraphs(4)}<p>See <a href='/docs/flow?fbclid=abc'>the docs</a> for more, they explain everything.</p>" +
                    "<img src='data:image/gif;base64,R0lGOD' data-src='/img/flow.png' alt='Flow diagram'>" +
                    "<p>Our cat <img src='/img/cat.png' alt='The [office] cat'> approves of this approach completely.</p>${paragraphs(2)}</article>"
            ),
            url
        )

        assertTrue(clip.markdown, clip.markdown.contains("[the docs](https://blog.example.com/docs/flow)"))
        assertTrue(clip.markdown, clip.markdown.contains("![Flow diagram](https://blog.example.com/img/flow.png)"))
        // The converter would write an image named like an emoji as ":cat:".
        assertTrue(clip.markdown, clip.markdown.contains("Our cat ![The (office) cat](https://blog.example.com/img/cat.png) approves"))
    }

    @Test
    fun `other H1s become H2s and linked headings carry their link inside`() {
        val clip = WebClipper.clip(
            page("<article><h1>Understanding flows</h1>${paragraphs(4)}<h1>Appendix</h1>${paragraphs(2)}<a href='/next'><h3>Next post</h3></a></article>"),
            url
        )

        assertTrue(clip.markdown, clip.markdown.contains("## Appendix"))
        assertTrue(clip.markdown, clip.markdown.contains("### [Next post](https://blog.example.com/next)"))
    }

    @Test
    fun `a Stack Exchange thread keeps the question and its best answers, accepted first`() {
        fun post(body: String, code: String = "") = "<div class='s-prose js-post-body'><p>$body</p>$code</div>"
        val clip = WebClipper.clip(
            page(
                "<div id='question' class='question'>${post("How do I read a file line by line in Java, without loading it whole?")}</div>" +
                    "<div id='answers'>" +
                    "<div class='answer' data-score='10'>${post("Use Files.lines, it streams the file lazily and closes it for you.")}</div>" +
                    "<div class='answer accepted-answer' data-score='5'>" +
                    post("A BufferedReader does it:", "<pre class='lang-java s-code-block'><code>try (var r = Files.newBufferedReader(p)) { r.lines().forEach(System.out::println); }</code></pre>") +
                    "</div>" +
                    "<div class='answer' data-score='-2'>${post("Read it all with readAllBytes.")}</div>" +
                    "<div class='answer' data-score='1'>${post("Scanner works too.")}</div>" +
                    "</div><div class='sidebar'>${paragraphs(6)}</div>"
            ),
            "https://stackoverflow.com/questions/1/read-a-file"
        )

        val md = clip.markdown
        val accepted = md.indexOf("## Réponse acceptée (score 5)")
        val top = md.indexOf("## Réponse (score 10)")
        assertTrue(md, md.startsWith("How do I read a file line by line"))
        assertTrue(md, accepted in 1 until top)
        assertTrue(md, md.contains("```java\ntry (var r = Files.newBufferedReader(p))"))
        assertTrue(md, md.contains("Scanner works too."))
        assertFalse("three answers at most", md.contains("readAllBytes"))
        assertFalse("not the sidebar", md.contains("Part 1."))
        assertEquals("a recognised thread is whole, however short", ClipQuality.FULL, clip.quality)
    }

    @Test
    fun `quality follows the words read`() {
        assertEquals(ClipQuality.EMPTY, WebClipper.clip(page("<div id='app'></div>"), url).quality)
        assertEquals(ClipQuality.PARTIAL, WebClipper.clip(page("<article>${paragraphs(2)}</article>"), url).quality)
    }

    @Test
    fun `bot checks and block pages are recognised, real pages are not`() {
        assertTrue(WebClipper.looksBlocked("<html><head><title>Just a moment...</title></head><body><script>window._cf_chl_opt={}</script></body></html>"))
        assertTrue(WebClipper.looksBlocked("<title>Attention Required! | Cloudflare</title>"))
        assertTrue(WebClipper.looksBlocked("<title>Acc&egrave;s restreint - Le Monde</title><p>Votre trafic a été identifié comme automatisé.</p>"))
        assertTrue(WebClipper.looksBlocked("<title>Example</title><script src='https://geo.captcha-delivery.com/captcha/'></script>"))

        // Protected sites inject their scripts into every real page.
        assertFalse(WebClipper.looksBlocked(page(paragraphs(), head = "<script src='/cdn-cgi/challenge-platform/scripts/jsd/main.js'></script>")))
        assertFalse(WebClipper.looksBlocked("<title>Un instant de grâce - Le Monde</title><p>$sentence</p>"))
        assertFalse(WebClipper.looksBlocked("<title>How a captcha works</title><p>A captcha asks you to verify you are human.</p>"))
    }

    @Test
    fun `clean addresses lose trackers and highlight directives only`() {
        assertEquals("https://a.example/p?id=7", WebClipper.cleanUrl("https://a.example/p?utm_source=x&id=7&fbclid=y"))
        assertEquals("https://a.example/p", WebClipper.cleanUrl("https://a.example/p?gclid=1"))
        assertEquals("https://a.example/p#part-2", WebClipper.cleanUrl("https://a.example/p#part-2"))
        assertEquals("https://a.example/p", WebClipper.cleanUrl("https://a.example/p#:~:text=some%20words"))
        assertEquals("https://a.example/p#intro", WebClipper.cleanUrl("https://a.example/p#intro:~:text=x"))
        assertEquals("https://a.example/", WebClipper.cleanUrl("https://a.example/"))
    }

    @Test
    fun `a shared selection converts whole`() {
        val markdown = WebClipper.fragment("<p>Hello <b>world</b>, <a href='/x'>linked</a>.</p><ul><li>one</li><li>two</li></ul>", "https://a.example/page")

        assertEquals("Hello **world**, [linked](https://a.example/x).\n\n- one\n- two", markdown)
    }

    @Test
    fun `a title's suffix goes when it names the site`() {
        fun titled(title: String, site: String? = null, at: String = "https://fr.wikipedia.org/wiki/Kotlin") = WebClipper.clip(
            "<html><head><meta property='og:title' content='$title'>${site?.let { "<meta property='og:site_name' content='$it'>" }.orEmpty()}" +
                "</head><body><article>${paragraphs()}</article></body></html>",
            at
        )

        titled("Kotlin (langage) — Wikipédia").let {
            assertEquals("Kotlin (langage)", it.title)
            assertEquals("Wikipédia", it.siteName)
        }
        assertEquals("404 Not Found - HTTP", titled("404 Not Found - HTTP | MDN", "MDN Web Docs", "https://developer.mozilla.org/x").title)
        assertEquals("Mon article - partie 2", titled("Mon article - partie 2", at = "https://blog.example.com/a").title)
    }

    @Test
    fun `a page without metadata falls back to its title tag`() {
        val clip = WebClipper.clip("<html><head><title>Plain page</title></head><body><article>${paragraphs()}</article></body></html>", "https://plain.example/a")

        assertEquals("Plain page", clip.title)
        assertNull(clip.siteName)
        assertNull(clip.author)
    }
}
