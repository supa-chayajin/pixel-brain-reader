package cloud.wafflecommons.pixelbrainreader

import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.wafflecommons.pixelbrainreader.data.vault.VaultLint
import cloud.wafflecommons.pixelbrainreader.data.web.ClipQuality
import cloud.wafflecommons.pixelbrainreader.data.web.SharedContent
import cloud.wafflecommons.pixelbrainreader.data.web.WebClipNote
import cloud.wafflecommons.pixelbrainreader.data.web.WebClipper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The web import's patterns (reader mode, bot-check detection, shared-text parsing, note format)
 * run on ANDROID's regex engine (ICU), which the JVM unit tests can't stand in for. Run on the emulator.
 */
@RunWith(AndroidJUnit4::class)
class WebImportDeviceTest {

    private val sentence = "Flows are cold streams of values, collected in coroutines, and they compose with operators like map, " +
        "filter and combine, which makes them a good fit for screen state."

    @Test
    fun clipsAnArticleOnIcu() {
        val html = "<html><head><title>Understanding flows | Example Blog</title><meta property='og:site_name' content='Example Blog'>" +
            "<script type='application/ld+json'>{\"@type\":\"BlogPosting\",\"author\":{\"name\":\"Ada\"},\"datePublished\":\"2024-03-05\"}</script>" +
            "</head><body><nav><a href='/'>Home</a></nav><article><h1>Understanding flows</h1>" +
            (1..8).joinToString("") { "<p>$sentence Part $it, see <b>this</b>.</p>" } +
            "<pre class='language-kotlin'><code>fun main() {}</code></pre><img src='data:,' data-src='/a.png' alt='A'>" +
            "<ol><li>Run it:<pre><code>ls</code></pre></li></ol></article></body></html>"

        val clip = WebClipper.clip(html, "https://blog.example.com/p?utm_source=x")

        assertEquals("Understanding flows", clip.title)
        assertEquals("Ada", clip.author)
        assertEquals(ClipQuality.FULL, clip.quality)
        assertTrue(clip.markdown, clip.markdown.contains("```kotlin\nfun main() {}\n```"))
        assertTrue(clip.markdown, clip.markdown.contains("see **this**."))
        assertTrue(clip.markdown, clip.markdown.contains("![A](https://blog.example.com/a.png)"))
        assertTrue(clip.markdown, clip.markdown.contains("1. Run it:\n\n   ```\n   ls\n   ```"))
        assertEquals("https://blog.example.com/p", clip.url)

        val note = WebClipNote.render(clip, clip.title, "00_Inbox", "- un", "Un passage.", LocalDate.of(2026, 10, 18))
        assertEquals(emptyList<Any>(), VaultLint.check("00_Inbox/Understanding flows.md", note) { true })
        assertEquals("a b", WebClipNote.fileName("a/b"))
    }

    @Test
    fun recognisesBlockPagesOnIcu() {
        assertTrue(WebClipper.looksBlocked("<title>Just a moment...</title><script>window._cf_chl_opt={}</script>"))
        assertTrue(WebClipper.looksBlocked("<title>Acc&egrave;s restreint - Le Monde</title>"))
        assertFalse(WebClipper.looksBlocked("<title>Un instant de grâce - Le Monde</title><p>$sentence</p>"))
    }

    @Test
    fun readsSharedTextOnIcu() {
        val shared = SharedContent.of("“A highlight.”\nhttps://example.com/a#:~:text=A%20highlight", "Page")

        assertEquals("https://example.com/a#:~:text=A%20highlight", shared.url)
        assertEquals("A highlight.", shared.passage)
        assertEquals("https://example.com/a", WebClipper.cleanUrl(shared.url!!))
        assertEquals("Hello **world**\n\n- one", WebClipper.fragment("<p>Hello <b>world</b></p><ul><li>one</li></ul>"))
    }
}
