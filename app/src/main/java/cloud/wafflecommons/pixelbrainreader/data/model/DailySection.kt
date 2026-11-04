package cloud.wafflecommons.pixelbrainreader.data.model

/**
 * The optional blocks of the Daily screen. "Shown" ones are always on screen; the others sit in
 * the "More today" fold, one tap away. Banners (guard, sync digest, back from leave) are not
 * here: they appear only when they have something to say.
 */
enum class DailySection(val key: String, val label: String, val description: String) {
    WEATHER("weather", "Weather", "Today's forecast"),
    VITALS("vitals", "Mood & health", "Today's mood, tags and health figures"),
    TASKS("tasks", "Tasks", "Today's to-dos"),
    TIMELINE("timeline", "Timeline", "What happened today, hour by hour"),
    GRATITUDE("gratitude", "Gratitude", "Three good things"),
    COUNTDOWNS("countdowns", "Countdowns", "Key dates coming up"),
    MANTRA("mantra", "Mantra", "The line of the day"),
    ECHOES("echoes", "Memory echoes", "Older notes worth a second look"),
    SECOND_BRAIN("second_brain", "Ideas & notes", "The day's free-form ideas and notes"),
    SCRATCHPAD("scratchpad", "Scratchpad", "Quick scraps waiting to be filed");

    companion object {
        /** Shown by default: what a busy day needs at a glance. Everything else is in the fold. */
        val DEFAULT_SHOWN: Set<DailySection> = setOf(WEATHER, VITALS, TASKS, TIMELINE)

        fun fromKeys(keys: Set<String>?): Set<DailySection> =
            keys?.mapNotNull { k -> entries.firstOrNull { it.key == k } }?.toSet() ?: DEFAULT_SHOWN
    }
}
