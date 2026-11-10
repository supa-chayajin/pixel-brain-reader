package cloud.wafflecommons.pixelbrainreader.data.model

/**
 * The optional blocks of the Daily screen. "Shown" ones are always on screen; the others sit in
 * the "More today" fold, one tap away. Both groups follow the user's order (Settings → Daily
 * screen); the declaration order below is only the default. Banners (guard, sync digest, back
 * from leave) are not here: they appear only when they have something to say.
 */
enum class DailySection(val key: String, val label: String, val description: String) {
    WEATHER("weather", "Weather", "Today's forecast"),
    VITALS("vitals", "Mood & health", "Today's mood, tags and health figures"),
    TASKS("tasks", "Tasks", "Today's to-dos"),
    TIMELINE("timeline", "Timeline", "What happened today, hour by hour"),
    PROJECTS("projects", "Projects", "A card per pinned project: its next action, Claude's queue, the last 14 days"),
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

        /**
         * The user's order from the stored comma-separated keys. Unknown keys are dropped, a
         * repeated one counts once, and blocks the list doesn't name (never ordered, or added by
         * an update) follow in their default order — so the order stays valid without a migration.
         */
        fun orderFromKeys(stored: String?): List<DailySection> {
            val saved = stored?.split(",")
                ?.mapNotNull { k -> entries.firstOrNull { it.key == k.trim() } }
                ?.distinct()
                .orEmpty()
            return saved + entries.filter { it !in saved }
        }

        fun toKeys(order: List<DailySection>): String = order.distinct().joinToString(",") { it.key }

        /**
         * [order] with [section] swapped with its nearest neighbour in the same group (both on
         * screen, or both in "More today"), one step [up] or down. Each group is shown in this
         * order, so a move always changes what the Daily screen shows; at a group's edge, or for
         * a block not in [order], nothing moves.
         */
        fun moved(
            order: List<DailySection>,
            shown: Set<DailySection>,
            section: DailySection,
            up: Boolean
        ): List<DailySection> {
            val from = order.indexOf(section)
            if (from < 0) return order
            val sameGroup = { s: DailySection -> (s in shown) == (section in shown) }
            val steps = if (up) (from - 1 downTo 0) else (from + 1..order.lastIndex)
            val to = steps.firstOrNull { sameGroup(order[it]) } ?: return order
            return order.toMutableList().also {
                it[from] = order[to]
                it[to] = section
            }
        }
    }
}
