package cloud.wafflecommons.pixelbrainreader.data.ai

import cloud.wafflecommons.pixelbrainreader.data.local.RoomTypeConverters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The search index reads stored vectors straight from their text (12.19, the Fold's out-of-memory). */
class VectorTextTest {

    private val converter = RoomTypeConverters()

    @Test
    fun `a vector reads back exactly as the converter writes and reads it`() {
        val vector = List(384) { i -> ((i * 37) % 101 - 50) / 97f } + listOf(1.0E-5f, -0.0f, 0.99999994f, -1.0f)
        val text = converter.fromFloatList(vector)

        val parsed = VectorText.parse(text)!!

        assertArrayEquals(vector.toFloatArray(), parsed, 0f)
        assertEquals(converter.toFloatList(text), parsed.toList())
    }

    @Test
    fun `no text, or a value that isn't a number, gives no vector`() {
        assertNull(VectorText.parse(null))
        assertNull(VectorText.parse(""))
        assertNull(VectorText.parse("0.1,abc,0.3"))
        assertNull(VectorText.parse("0.1,,0.3"))
        assertEquals(1, VectorText.parse("0.5")!!.size)
    }
}
