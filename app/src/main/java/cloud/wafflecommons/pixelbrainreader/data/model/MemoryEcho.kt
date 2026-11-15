package cloud.wafflecommons.pixelbrainreader.data.model

/**
 * Represents a dormant or decaying note from the user's second brain
 * surfaced for spaced-repetition and architectural memory retention.
 */
data class MemoryEcho(
    val path: String,
    val title: String,
    val categoryBadge: String,
    val daysSinceModified: Long,
    val snippet: String,
    val urgencyLabel: String,
    /** A spaced echo (K6): answered Again, Good or Easy rather than marked reviewed. */
    val spaced: Boolean = false
)
