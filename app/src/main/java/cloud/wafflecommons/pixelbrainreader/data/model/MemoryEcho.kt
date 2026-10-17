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
    val urgencyLabel: String
)
