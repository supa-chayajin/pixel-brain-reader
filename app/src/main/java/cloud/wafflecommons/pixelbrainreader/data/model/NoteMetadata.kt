package cloud.wafflecommons.pixelbrainreader.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Structured representation of Obsidian Note Frontmatter.
 * Uses Kaml for decoding.
 */
@Serializable
data class NoteMetadata(
    // Standard Obsidian/Common properties
    val tags: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
    
    // PixelBrain custom properties
    @SerialName("mood_score") val moodScore: Int? = null,
    @SerialName("energy_level") val energyLevel: Int? = null,
    @SerialName("weather_desc") val weatherDesc: String? = null,
    @SerialName("location_lat") val locationLat: Double? = null,
    @SerialName("location_lon") val locationLon: Double? = null,
    
    // Capture remaining unknown keys to preserve them (Deep Merge)
    // Note: Kaml supports @SerialName but redundant map capture is manual in cleanup logic usually,
    // but here we define known keys. Unknown keys management will be handled by the Manager.
    // For now, these are the typed keys we care about.
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)
