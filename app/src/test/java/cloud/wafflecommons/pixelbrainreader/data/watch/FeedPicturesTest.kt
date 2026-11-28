package cloud.wafflecommons.pixelbrainreader.data.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 13.4 (F7): which feed picture addresses the loader may use, how a candidate becomes one, and redirects. */
class FeedPicturesTest {

    private val link = "https://news.example/posts/1"

    @Test
    fun `only https on a public host passes, any host`() {
        assertTrue(FeedPictures.allowed("https://cdn.example.org/a.png"))
        assertTrue(FeedPictures.allowed("https://news.example/img/1.jpg?w=200"))
        assertTrue(FeedPictures.allowed("HTTPS://Pictures.Example.com/a.webp"))
        assertTrue(FeedPictures.allowed("https://cdn.example.org:443/a.png"))

        assertFalse(FeedPictures.allowed("http://cdn.example.org/a.png"))
        assertFalse(FeedPictures.allowed("ftp://cdn.example.org/a.png"))
        assertFalse(FeedPictures.allowed("file:///sdcard/a.png"))
        assertFalse(FeedPictures.allowed("data:image/png;base64,iVBORw0KGgo="))
        assertFalse(FeedPictures.allowed("/img/a.png"))
        assertFalse(FeedPictures.allowed(null))
        assertFalse(FeedPictures.allowed(""))
        assertFalse(FeedPictures.allowed("   "))
        assertFalse(FeedPictures.allowed("not an address"))
        assertFalse(FeedPictures.allowed("https://cdn.example.org/" + "a".repeat(3000)))
    }

    @Test
    fun `a user name, another port, a private or loopback literal is refused`() {
        listOf(
            "https://user@cdn.example.org/a.png",
            "https://user:secret@cdn.example.org/a.png",
            "https://cdn.example.org@pictures.example.com/a.png",
            "https://cdn.example.org:8443/a.png",
            "https://cdn.example.org:80/a.png",
            "https://10.0.0.1/a.png",
            "https://192.168.1.20/a.png",
            "https://127.0.0.1/a.png",
            "https://[::1]/a.png",
            "https://169.254.169.254/latest/meta-data/",
            "https://localhost/a.png",
            "https://printer.local/a.png",
            "https://2130706433/a.png"
        ).forEach { assertFalse(it, FeedPictures.allowed(it)) }
    }

    @Test
    fun `a protocol-relative address becomes https, a relative one resolves against the link`() {
        assertEquals("https://cdn.example.org/a.png", FeedPictures.thumbnailOf(listOf("//cdn.example.org/a.png"), link))
        // Under an http link too: the picture's own address asks for nothing less than https.
        assertEquals("https://cdn.example.org/a.png", FeedPictures.thumbnailOf(listOf("//cdn.example.org/a.png"), "http://news.example/posts/1"))
        assertEquals("https://news.example/img/a.png", FeedPictures.thumbnailOf(listOf("/img/a.png"), link))
        assertEquals("https://news.example/posts/img/a.png", FeedPictures.thumbnailOf(listOf("img/a.png"), link))
        assertEquals("https://cdn.example.org/a.png", FeedPictures.thumbnailOf(listOf("  https://cdn.example.org/a.png  "), null))
        // Without a link, a relative path has nothing to resolve against.
        assertNull(FeedPictures.thumbnailOf(listOf("/img/a.png"), null))
    }

    @Test
    fun `an http picture is never upgraded`() {
        assertNull(FeedPictures.thumbnailOf(listOf("http://cdn.example.org/a.png"), link))
        // A relative picture of an http article stays http: no picture.
        assertNull(FeedPictures.thumbnailOf(listOf("/img/a.png"), "http://news.example/posts/1"))
    }

    @Test
    fun `the first allowed candidate wins`() {
        val candidates = listOf(
            "",
            "http://cdn.example.org/1.png",
            "data:image/png;base64,AAAA",
            "javascript:alert(1)",
            "https://10.0.0.1/x.png",
            "https://cdn.example.org/2.png",
            "https://cdn.example.org/3.png"
        )
        assertEquals("https://cdn.example.org/2.png", FeedPictures.thumbnailOf(candidates, link))
        assertNull(FeedPictures.thumbnailOf(emptyList(), link))
    }

    @Test
    fun `a redirect to http or to a private literal is refused, a relative one is followed`() {
        val from = "https://cdn.example.org/a/1.png"
        assertEquals("https://cdn.example.org/b/1.png", FeedPictures.redirectTarget(from, "/b/1.png"))
        assertEquals("https://cdn.example.org/a/2.png", FeedPictures.redirectTarget(from, "2.png"))
        // Any public https host: a picture may move to another CDN.
        assertEquals("https://pictures.example.com/1.png", FeedPictures.redirectTarget(from, "https://pictures.example.com/1.png"))
        assertEquals("https://pictures.example.com/1.png", FeedPictures.redirectTarget(from, "//pictures.example.com/1.png"))

        assertNull(FeedPictures.redirectTarget(from, "http://cdn.example.org/a/1.png"))
        assertNull(FeedPictures.redirectTarget(from, "https://10.0.0.1/1.png"))
        assertNull(FeedPictures.redirectTarget(from, "https://[::1]/1.png"))
        assertNull(FeedPictures.redirectTarget(from, "https://user@cdn.example.org/1.png"))
        assertNull(FeedPictures.redirectTarget(from, "ftp://cdn.example.org/1.png"))
        assertNull(FeedPictures.redirectTarget(from, null))
        assertNull(FeedPictures.redirectTarget(from, "  "))
        assertEquals(3, FeedPictures.MAX_REDIRECTS)
    }
}
