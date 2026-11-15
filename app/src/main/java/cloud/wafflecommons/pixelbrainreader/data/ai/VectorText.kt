package cloud.wafflecommons.pixelbrainreader.data.ai

/**
 * A stored vector's text, as `RoomTypeConverters.fromFloatList` writes it (the floats joined by
 * commas), read straight into a [FloatArray]. The search index used to load every vector through
 * the converter as a `List<Float>`: 384 boxed floats per passage, the whole table at once, which
 * ran the Fold out of its 256 MB heap during Measure retrieval (2026-11-15). Each value goes
 * through the same `toFloatOrNull` as the converter, so the floats are exactly the ones it read.
 */
internal object VectorText {

    /** The vector, or null when [text] is empty or holds a value that isn't a number. */
    fun parse(text: String?): FloatArray? {
        if (text.isNullOrEmpty()) return null
        var count = 1
        for (c in text) if (c == ',') count++
        val vector = FloatArray(count)
        var start = 0
        for (i in 0 until count) {
            val end = text.indexOf(',', start).let { if (it < 0) text.length else it }
            vector[i] = text.substring(start, end).toFloatOrNull() ?: return null
            start = end + 1
        }
        return vector
    }
}
