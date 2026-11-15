package cloud.wafflecommons.pixelbrainreader.data.capture

import kotlin.math.roundToInt

/**
 * Capture anything (13.0, X2, the owner's D2): a photo into an inbox note, the text ML Kit reads
 * on the phone, and the photo itself kept beside it, smaller. The pure side.
 */
object PhotoCapture {

    /** The longest side of a kept photo: a page still reads, a 12 MP photo shrinks ~20 times. */
    const val MAX_SIDE = 1600

    const val JPEG_QUALITY = 85

    /** The size a photo is decoded and kept at: its own when small enough, else its longest side at [max]. */
    fun targetSize(width: Int, height: Int, max: Int = MAX_SIDE): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (width <= 0 || height <= 0 || longest <= max) return width to height
        val scale = max.toDouble() / longest
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }

    /** What was typed, then what the photo said, a blank line between. */
    fun withRead(typed: String, read: String): String =
        listOf(typed.trimEnd(), read.trim()).filter { it.isNotEmpty() }.joinToString("\n\n")

    /** The note's body: the photo first, then the text. */
    fun body(imageName: String, text: String): String = buildString {
        append("![[").append(imageName).append("]]\n")
        if (text.isNotBlank()) append('\n').append(text.trim()).append('\n')
    }

    /**
     * A shared image is read only from another app's content provider: never a `file:` address,
     * and never one of this app's own providers ([ownAuthorities]), which would read its private
     * files on another app's behalf.
     */
    fun isForeign(scheme: String?, authority: String?, ownAuthorities: Set<String>): Boolean =
        scheme == "content" && !authority.isNullOrBlank() && authority !in ownAuthorities
}
