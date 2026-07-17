package cloud.wafflecommons.pixelbrainreader.data.utils

/**
 * Invisible identity markers embedded in the burned daily-note markdown so the
 * burn↔parse round-trip preserves the external (Google) dedup keys.
 *
 * WHY THIS EXISTS: [MarkdownBurner] used to serialize a Google-sourced timeline
 * entry as just `- HH:mm content` and a task as `- [x] label`, dropping
 * `googleEventId` / `googleTaskId` / `source`. [DailyMarkdownParser] then rebuilt
 * those rows with null keys and `source = "Local"`, producing orphans that the
 * source-scoped purges (`purgeGoogleTimelineForDate` / `purgeGoogleTasksForDate`)
 * could never reclaim and the nullable UNIQUE indices could never dedup — so every
 * cold-start rehydrate stacked another copy and the next sync added a fresh keyed
 * one on top (the "items appear in triple" bug).
 *
 * The markers are HTML comments, so they never render in Obsidian / GitHub, yet
 * they survive the round-trip and let the parser restore the keys. Keep the format
 * in sync between [MarkdownBurner] (writer) and [DailyMarkdownParser] (reader).
 */
object DailyMarkers {

    private val CALENDAR = Regex("<!--\\s*gcal:(.+?)\\s*-->")
    private val TASK = Regex("<!--\\s*gtask:(.+?)\\s*-->")

    /** Appends a `<!--gcal:ID-->` marker to [content] when the entry is Calendar-sourced. */
    fun appendCalendarMarker(content: String, googleEventId: String?): String =
        if (googleEventId.isNullOrBlank()) content else "$content <!--gcal:$googleEventId-->"

    /** Appends a `<!--gtask:ID-->` marker to [label] when the task is Google-sourced. */
    fun appendTaskMarker(label: String, googleTaskId: String?): String =
        if (googleTaskId.isNullOrBlank()) label else "$label <!--gtask:$googleTaskId-->"

    /**
     * Strips any calendar marker out of [text] and returns the clean display text plus the
     * extracted `googleEventId` (null when the line carries no marker — i.e. a local entry).
     */
    fun stripCalendarMarker(text: String): Pair<String, String?> {
        val match = CALENDAR.find(text) ?: return text.trim() to null
        val id = match.groupValues[1].trim().ifBlank { null }
        return CALENDAR.replace(text, "").trim() to id
    }

    /**
     * Strips any task marker out of [text] and returns the clean label plus the extracted
     * `googleTaskId` (null when the line carries no marker — i.e. a local task).
     */
    fun stripTaskMarker(text: String): Pair<String, String?> {
        val match = TASK.find(text) ?: return text.trim() to null
        val id = match.groupValues[1].trim().ifBlank { null }
        return TASK.replace(text, "").trim() to id
    }
}
