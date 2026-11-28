package cloud.wafflecommons.pixelbrainreader.ui.settings

/**
 * A habit's automatic sources (13.4, S-k), by name: what the habit editor offers and the habits'
 * list shows. The keys are what `HabitConfig.autoSource` stores in the vault: never rename one. Pure.
 */
object HabitSources {

    /** null first (ticked by hand), then each Health Connect source the habits can follow. */
    val KEYS: List<String?> = listOf(
        null,
        "health_connect_steps",
        "health_connect_sleep",
        "health_connect_hydration",
        "health_connect_nutrition",
        "health_connect_active_minutes",
        "health_connect_distance",
        "health_connect_mindfulness",
        "health_connect_weight"
    )

    private val NAMES = mapOf(
        "health_connect_steps" to "Steps",
        "health_connect_sleep" to "Sleep",
        "health_connect_hydration" to "Water",
        "health_connect_nutrition" to "Nutrition",
        "health_connect_active_minutes" to "Active minutes",
        "health_connect_distance" to "Distance",
        "health_connect_mindfulness" to "Mindfulness",
        "health_connect_weight" to "Weight"
    )

    /** "None (by hand)" for no source, "Steps (Health Connect)" for one; a key this version doesn't know, as stored. */
    fun label(key: String?): String {
        if (key.isNullOrBlank()) return "None (by hand)"
        return NAMES[key]?.let { "$it (Health Connect)" } ?: key
    }
}
