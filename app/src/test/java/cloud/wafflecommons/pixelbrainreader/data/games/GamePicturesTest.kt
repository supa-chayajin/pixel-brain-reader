package cloud.wafflecommons.pixelbrainreader.data.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 13.4 (GP2): which console picture addresses the loader may use, the size hint, and redirects. */
class GamePicturesTest {

    private val psn = "https://image.api.playstation.com/vulcan/ap/rnd/202611/0101/tower.png"
    private val store = "https://store-images.s-microsoft.com/image/apps.1234.tower"
    private val eds = "https://images-eds-ssl.xboxlive.com/image?url=abc_DEF.ghi&format=png"

    @Test
    fun `only the three hosts over https are allowed`() {
        assertTrue(GamePictures.allowed(psn))
        assertTrue(GamePictures.allowed(store))
        assertTrue(GamePictures.allowed(eds))
        assertEquals(setOf("image.api.playstation.com", "store-images.s-microsoft.com", "images-eds-ssl.xboxlive.com"), GamePictures.HOSTS)
        // Case doesn't make another host.
        assertTrue(GamePictures.allowed("https://IMAGE.API.PLAYSTATION.COM/a.png"))
        assertTrue(GamePictures.allowed("HTTPS://image.api.playstation.com/a.png"))

        assertFalse(GamePictures.allowed("http://image.api.playstation.com/a.png"))
        assertFalse(GamePictures.allowed("http://store-images.s-microsoft.com/image/apps.1"))
        // images-eds is reached as images-eds-ssl only.
        assertFalse(GamePictures.allowed("https://images-eds.xboxlive.com/image?url=abc"))
        assertFalse(GamePictures.allowed("https://images.example.com/a.png"))
        assertFalse(GamePictures.allowed("https://image.api.playstation.com.example.com/a.png"))
        assertFalse(GamePictures.allowed("https://evilimage.api.playstation.com/a.png"))
        assertFalse(GamePictures.allowed("https://cdn.image.api.playstation.com/a.png"))
        assertFalse(GamePictures.allowed("https://image.api.playstation.com./a.png"))
        assertFalse(GamePictures.allowed("ftp://image.api.playstation.com/a.png"))
        assertFalse(GamePictures.allowed("file:///sdcard/a.png"))
        assertFalse(GamePictures.allowed(null))
        assertFalse(GamePictures.allowed(""))
        assertFalse(GamePictures.allowed("   "))
        assertFalse(GamePictures.allowed("not an address"))
        assertFalse(GamePictures.allowed("https://image.api.playstation.com/" + "a".repeat(3000)))
    }

    @Test
    fun `a user name, a port or a private literal is refused`() {
        assertFalse(GamePictures.allowed("https://user@image.api.playstation.com/a.png"))
        assertFalse(GamePictures.allowed("https://user:secret@image.api.playstation.com/a.png"))
        assertFalse(GamePictures.allowed("https://image.api.playstation.com@images.example.com/a.png"))
        assertFalse(GamePictures.allowed("https://image.api.playstation.com:8443/a.png"))
        assertFalse(GamePictures.allowed("https://image.api.playstation.com:80/a.png"))
        assertTrue(GamePictures.allowed("https://image.api.playstation.com:443/a.png"))
        assertFalse(GamePictures.allowed("https://10.0.0.1/a.png"))
        assertFalse(GamePictures.allowed("https://127.0.0.1/a.png"))
        assertFalse(GamePictures.allowed("https://[::1]/a.png"))
        assertFalse(GamePictures.allowed("https://169.254.169.254/latest/meta-data/"))
        assertFalse(GamePictures.allowed("https://localhost/a.png"))
    }

    @Test
    fun `the size hint goes where each host takes it, once`() {
        assertEquals("$psn?w=512", GamePictures.sized(psn))
        assertEquals("https://image.api.playstation.com/a.png?x=1&w=512", GamePictures.sized("https://image.api.playstation.com/a.png?x=1"))
        assertEquals("$store?w=512&h=512", GamePictures.sized(store))
        assertEquals("$eds&w=512&h=512", GamePictures.sized(eds))
        assertEquals("https://image.api.playstation.com/a.png?w=128", GamePictures.sized("https://image.api.playstation.com/a.png", 128))
        // A size already asked stays as it is, and a second pass adds nothing.
        assertEquals("https://image.api.playstation.com/a.png?w=256", GamePictures.sized("https://image.api.playstation.com/a.png?w=256"))
        assertEquals(GamePictures.sized(store), GamePictures.sized(GamePictures.sized(store)))
        assertEquals(GamePictures.sized(eds), GamePictures.sized(GamePictures.sized(eds)))
        // An h= alone isn't asked twice.
        assertEquals("$store?h=300&w=512", GamePictures.sized("$store?h=300"))
        // A fragment stays last; a query that ends open is filled.
        assertEquals("https://image.api.playstation.com/a.png?w=512#top", GamePictures.sized("https://image.api.playstation.com/a.png#top"))
        assertEquals("https://image.api.playstation.com/a.png?w=512", GamePictures.sized("https://image.api.playstation.com/a.png?"))
        // Any other host as is.
        assertEquals("https://images.example.com/a.png", GamePictures.sized("https://images.example.com/a.png"))
        assertEquals(512, GamePictures.SIZE_HINT)
    }

    @Test
    fun `an Xbox snapshot's http address is read through the rewrite`() {
        assertEquals(store, GamePictures.consoleUrl("http://store-images.s-microsoft.com/image/apps.1234.tower", GameConsole.XBOX))
        assertEquals(
            "https://images-eds-ssl.xboxlive.com/image?url=abc&format=png",
            GamePictures.consoleUrl("http://images-eds.xboxlive.com/image?url=abc&format=png", GameConsole.XBOX)
        )
        assertEquals(
            "https://images-eds-ssl.xboxlive.com/image?url=abc",
            GamePictures.consoleUrl("https://images-eds.xboxlive.com/image?url=abc", GameConsole.XBOX)
        )
        assertEquals(store, GamePictures.consoleUrl(" $store ", GameConsole.XBOX))
        assertNull(GamePictures.consoleUrl("http://images.example.com/a.png", GameConsole.XBOX))
        assertNull(GamePictures.consoleUrl("https://images.example.com/a.png", GameConsole.XBOX))
        // PlayStation's addresses aren't rewritten: an http one is no picture.
        assertNull(GamePictures.consoleUrl("http://image.api.playstation.com/a.png", GameConsole.PSN))
        assertEquals(psn, GamePictures.consoleUrl(" $psn ", GameConsole.PSN))
        assertNull(GamePictures.consoleUrl(null, GameConsole.PSN))
        assertNull(GamePictures.consoleUrl("  ", GameConsole.XBOX))
    }

    @Test
    fun `a redirect to another host or to http is refused, a relative one on the same host is followed`() {
        val from = "https://image.api.playstation.com/vulcan/a/tower.png"
        assertEquals("https://image.api.playstation.com/vulcan/b/tower.png", GamePictures.redirectTarget(from, "/vulcan/b/tower.png"))
        assertEquals("https://image.api.playstation.com/vulcan/a/other.png", GamePictures.redirectTarget(from, "other.png"))
        assertEquals("https://image.api.playstation.com/vulcan/a/tower.png?w=512", GamePictures.redirectTarget(from, "?w=512"))
        // Another of the three hosts is still one of them.
        assertEquals(store, GamePictures.redirectTarget(from, store))

        assertNull(GamePictures.redirectTarget(from, "https://images.example.com/tower.png"))
        assertNull(GamePictures.redirectTarget(from, "//images.example.com/tower.png"))
        assertNull(GamePictures.redirectTarget(from, "http://image.api.playstation.com/vulcan/a/tower.png"))
        assertNull(GamePictures.redirectTarget(from, "https://10.0.0.1/tower.png"))
        assertNull(GamePictures.redirectTarget(from, "https://user@image.api.playstation.com/tower.png"))
        assertNull(GamePictures.redirectTarget(from, "ftp://image.api.playstation.com/tower.png"))
        assertNull(GamePictures.redirectTarget(from, null))
        assertNull(GamePictures.redirectTarget(from, "  "))
        assertEquals(3, GamePictures.MAX_REDIRECTS)
    }
}
