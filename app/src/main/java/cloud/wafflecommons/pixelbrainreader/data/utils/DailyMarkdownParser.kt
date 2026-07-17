package cloud.wafflecommons.pixelbrainreader.data.utils

import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.TimelineEntryEntity
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Parses a burned daily-note markdown file (as produced by [MarkdownBurner]) back into its
 * component sections. Pure and stateless — the inverse of [MarkdownBurner.burn] — so it can
 * be reused by both the normal past-day ingest and the cold-start recovery path, and unit
 * tested without Room or a device.
 */
object DailyMarkdownParser {

    /** Sections parsed out of a burned daily markdown file. */
    data class ParsedDaily(
        val timeline: List<TimelineEntryEntity>,
        val tasks: List<DailyTaskEntity>,
        val ideas: String,
        val notes: String,
        val scraps: List<String>,
        val gratitude: List<String>
    )

    fun parse(date: LocalDate, content: String): ParsedDaily {
        val lines = content.lines()
        val timelineEvents = mutableListOf<TimelineEntryEntity>()
        val tasks = mutableListOf<DailyTaskEntity>()
        val ideas = StringBuilder()
        val notes = StringBuilder()
        val scraps = mutableListOf<String>()
        val gratitude = mutableListOf<String>()

        var section = "HEADER"
        val timelineRegex = Regex("^\\s*-\\s+(\\d{1,2}:\\d{2})\\s+(.*)")

        lines.forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("## 🗓️ Timeline") || trimmed.startsWith("## Timeline")) { section = "TIMELINE"; return@forEach }
            if (trimmed.startsWith("## 📝 Journal") || trimmed.startsWith("## Journal")) { section = "JOURNAL"; return@forEach }
            // Accept the English header (current burner output) AND the legacy French header
            // ("Idées / Second Cerveau") so daily notes written before the localization still parse.
            if (trimmed.startsWith("## 🧠 Ideas") || trimmed.startsWith("## Ideas") ||
                trimmed.startsWith("## 🧠 Idées") || trimmed.startsWith("## Idées")) { section = "IDEAS"; return@forEach }
            if (trimmed.startsWith("## 📑 Notes") || trimmed.startsWith("## Notes")) { section = "NOTES"; return@forEach }
            if (trimmed.startsWith("## 💡 Scraps") || trimmed.startsWith("## Scraps")) { section = "SCRAPS"; return@forEach }
            if (trimmed.startsWith("## 🌟 Gratitude") || trimmed.startsWith("## Gratitude")) { section = "GRATITUDE"; return@forEach }
            if (trimmed.startsWith("##")) return@forEach // Skip other headers

            when (section) {
                "TIMELINE" -> {
                     val match = timelineRegex.find(line)
                     if (match != null) {
                         val (timeStr, text) = match.destructured
                         // Pull the googleEventId back out of its invisible marker (and strip
                         // it from the visible content) so the row stays Calendar-reclaimable.
                         val (cleanContent, googleEventId) = DailyMarkers.stripCalendarMarker(text)
                         try {
                              timelineEvents.add(TimelineEntryEntity(
                                  date = date,
                                  time = LocalTime.parse(timeStr, DateTimeFormatter.ofPattern("H:mm")),
                                  content = cleanContent,
                                  googleEventId = googleEventId
                              ))
                         } catch (e: Exception) {}
                     }
                }
                "JOURNAL" -> {
                    if (trimmed.startsWith("- [")) {
                        val isDone = trimmed.startsWith("- [x]")
                        val afterCheckbox = trimmed.substringAfter("] ").trim()
                        // Pull the googleTaskId back out of its invisible marker (and strip it
                        // from the visible label) so the row stays Google-reclaimable.
                        val (rawLabel, googleTaskId) = DailyMarkers.stripTaskMarker(afterCheckbox)
                        val priority = if (rawLabel.contains("‼️")) 2 else 1
                        val cleanLabel = rawLabel.replace("‼️", "").trim()
                        var scheduledTime: LocalTime? = null
                        var finalLabel = cleanLabel

                        // The burner writes the scheduled time as a LEADING "at HH:mm " prefix, so
                        // only match it there. An unanchored match would corrupt a label that merely
                        // CONTAINS the text (e.g. "Meet Bob at 10:00" -> "Meet Bob", time 10:00).
                        val timeMatch = Regex("^at (\\d{1,2}:\\d{2})(?:\\s|$)").find(cleanLabel)
                        if (timeMatch != null) {
                            try {
                                scheduledTime = LocalTime.parse(timeMatch.groupValues[1], DateTimeFormatter.ofPattern("H:mm"))
                                finalLabel = cleanLabel.removeRange(timeMatch.range).trim()
                            } catch (e: Exception) {}
                        }

                        tasks.add(DailyTaskEntity(
                            scheduledDate = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                            label = finalLabel,
                            isDone = isDone,
                            priority = priority,
                            scheduledTime = scheduledTime?.format(DateTimeFormatter.ofPattern("HH:mm")),
                            googleTaskId = googleTaskId,
                            source = if (googleTaskId != null) "GoogleTasks" else "Local"
                        ))
                    }
                }
                "IDEAS" -> { if (line.isNotBlank() || ideas.isNotEmpty()) ideas.append(line).append("\n") }
                "NOTES" -> { if (line.isNotBlank() || notes.isNotEmpty()) notes.append(line).append("\n") }
                "SCRAPS" -> {
                    if (trimmed.startsWith("-")) {
                        val s = trimmed.removePrefix("-").trim()
                        if (s.isNotBlank()) scraps.add(s)
                    }
                }
                "GRATITUDE" -> {
                    if (trimmed.startsWith("-")) {
                        // Burned as "- [x] content" — strip the bullet and checkbox.
                        val g = trimmed.removePrefix("-").trim()
                            .removePrefix("[x]").removePrefix("[X]").removePrefix("[ ]").trim()
                        if (g.isNotBlank()) gratitude.add(g)
                    }
                }
            }
        }

        return ParsedDaily(
            timeline = dedupeTimeline(timelineEvents),
            tasks = dedupeTasks(tasks),
            ideas = ideas.toString().trim(),
            notes = notes.toString().trim(),
            scraps = scraps,
            gratitude = gratitude
        )
    }

    /**
     * Heals journals already polluted by the pre-marker round-trip WITHOUT ever merging two
     * genuinely-distinct Calendar events:
     *  - every distinct `googleEventId` is kept (two real events sharing a start-minute and
     *    rendered content must survive as two rows);
     *  - a stripped orphan (null key) that duplicates a keyed row's (time, content) is dropped,
     *    since the keyed twin already represents it;
     *  - identical orphans with no keyed twin collapse to one.
     *
     * LIMITATION: a stripped-Google orphan is byte-identical to a genuinely-local entry, so a
     * local entry that happens to match a Calendar event's (time, content) is treated as an
     * orphan and dropped. That collision is rare and low-impact (the Calendar copy still shows).
     */
    private fun dedupeTimeline(items: List<TimelineEntryEntity>): List<TimelineEntryEntity> {
        val keyedContent = items.filter { it.googleEventId != null }
            .mapTo(HashSet()) { it.time to it.content }
        val seenEventIds = HashSet<String>()
        val seenOrphanKeys = HashSet<Pair<LocalTime, String>>()
        val out = ArrayList<TimelineEntryEntity>()
        for (entry in items) {
            val eventId = entry.googleEventId
            if (eventId != null) {
                if (seenEventIds.add(eventId)) out.add(entry)
            } else {
                val key = entry.time to entry.content
                if (key !in keyedContent && seenOrphanKeys.add(key)) out.add(entry)
            }
        }
        return out
    }

    /**
     * Task counterpart of [dedupeTimeline], keyed on (date, label, scheduledTime): every distinct
     * `googleTaskId` survives; orphans duplicating a Google-keyed twin are dropped; identical
     * orphans collapse. Same rare local-vs-Google label-collision limitation applies.
     */
    private fun dedupeTasks(items: List<DailyTaskEntity>): List<DailyTaskEntity> {
        val keyedKeys = items.filter { it.googleTaskId != null }
            .mapTo(HashSet()) { Triple(it.scheduledDate, it.label, it.scheduledTime) }
        val seenTaskIds = HashSet<String>()
        val seenOrphanKeys = HashSet<Triple<String, String, String?>>()
        val out = ArrayList<DailyTaskEntity>()
        for (task in items) {
            val taskId = task.googleTaskId
            if (taskId != null) {
                if (seenTaskIds.add(taskId)) out.add(task)
            } else {
                val key = Triple(task.scheduledDate, task.label, task.scheduledTime)
                if (key !in keyedKeys && seenOrphanKeys.add(key)) out.add(task)
            }
        }
        return out
    }
}
