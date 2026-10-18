package cloud.wafflecommons.pixelbrainreader.data.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoEmbedTest {

    @Test
    fun `video pages get their own address, without share tracking`() {
        val watch = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        assertEquals(watch, VideoEmbed.canonical("https://youtu.be/dQw4w9WgXcQ?si=AbCdEf"))
        assertEquals(watch, VideoEmbed.canonical("https://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ&t=42"))
        assertEquals(watch, VideoEmbed.canonical("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("https://vimeo.com/76979871", VideoEmbed.canonical("https://vimeo.com/76979871"))
        assertEquals("https://www.dailymotion.com/video/x8abc12", VideoEmbed.canonical("https://dai.ly/x8abc12"))
        assertNull(VideoEmbed.canonical("https://www.youtube.com/@SomeChannel"))
        assertNull(VideoEmbed.canonical("https://example.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun `the oEmbed answer becomes a card`() {
        val watch = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val oEmbed = """{"title":"Never Gonna Give You Up [4K]","author_name":"Rick Astley","provider_name":"YouTube",
            "thumbnail_url":"https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg","type":"video"}"""

        val clip = VideoEmbed.clip(oEmbed, watch, "The official video.")!!

        assertEquals("Never Gonna Give You Up [4K]", clip.title)
        assertEquals("Rick Astley", clip.author)
        assertEquals("YouTube", clip.siteName)
        assertEquals(ClipQuality.FULL, clip.quality)
        assertEquals(
            "[![Never Gonna Give You Up (4K)](https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg)]($watch)\n\n" +
                "▶ [Voir la vidéo sur YouTube]($watch) — Rick Astley\n\nThe official video.",
            clip.markdown
        )
        assertTrue(VideoEmbed.endpoint(watch)!!.startsWith("https://www.youtube.com/oembed?format=json&url=https%3A%2F%2Fwww.youtube.com"))
        assertNull(VideoEmbed.clip("{}", watch, null))
    }
}
