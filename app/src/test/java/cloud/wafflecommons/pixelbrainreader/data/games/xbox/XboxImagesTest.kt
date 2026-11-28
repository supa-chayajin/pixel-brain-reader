package cloud.wafflecommons.pixelbrainreader.data.games.xbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 13.4: titlehub's picture addresses asked over https on Microsoft's two image hosts, never http. */
class XboxImagesTest {

    @Test
    fun `store-images goes https`() {
        assertEquals(
            "https://store-images.s-microsoft.com/image/apps.12345.abc?q=1&w=2",
            XboxImages.https("http://store-images.s-microsoft.com/image/apps.12345.abc?q=1&w=2")
        )
        // The scheme and host ignoring case, the rest byte for byte, the ends trimmed.
        assertEquals("https://store-images.s-microsoft.com/image/X%2Fy", XboxImages.https("  HTTP://Store-Images.S-Microsoft.com/image/X%2Fy  "))
        assertEquals("https://store-images.s-microsoft.com/image/apps.1", XboxImages.https("https://store-images.s-microsoft.com/image/apps.1"))
    }

    @Test
    fun `images-eds goes to images-eds-ssl`() {
        assertEquals(
            "https://images-eds-ssl.xboxlive.com/image?url=AbC%2F&format=png",
            XboxImages.https("http://images-eds.xboxlive.com/image?url=AbC%2F&format=png")
        )
        assertEquals("https://images-eds-ssl.xboxlive.com/image?url=x", XboxImages.https("https://images-eds.xboxlive.com/image?url=x"))
        assertEquals("https://images-eds-ssl.xboxlive.com/image?url=x", XboxImages.https("https://images-eds-ssl.xboxlive.com/image?url=x"))
    }

    @Test
    fun `https stays, other http is dropped, blank is null`() {
        // Any other https address stays: the picture loader's host list decides.
        assertEquals("https://example.com/icon.png", XboxImages.https("https://example.com/icon.png"))
        assertNull(XboxImages.https("http://example.com/icon.png"))
        // The host whole, never a longer name that starts like it.
        assertNull(XboxImages.https("http://store-images.s-microsoft.com.attacker.example/x"))
        assertNull(XboxImages.https("http://images-eds.xboxlive.com.attacker.example/x"))
        assertNull(XboxImages.https("store-images.s-microsoft.com/image/x"))
        assertNull(XboxImages.https("ftp://store-images.s-microsoft.com/x"))
        assertNull(XboxImages.https(""))
        assertNull(XboxImages.https("   "))
        assertNull(XboxImages.https(null))
    }
}
