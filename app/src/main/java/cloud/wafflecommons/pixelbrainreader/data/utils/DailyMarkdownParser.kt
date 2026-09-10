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

    private enum class Section { HEADER, TIMELINE, JOURNAL, IDEAS, NOTES, SCRAPS, GRATITUDE }

    /**
     * Section titles as [MarkdownBurner] writes them — current and legacy spellings (the French
     * "Idées / Second Cerveau" predates the localization), plus the bare names — lower-cased and
     * without their decorative emoji. A `## ` heading is a section header only when its WHOLE title
     * matches: prefix matching turned a user's "## Notes about Monday" inside Ideas into a switch to
     * the Notes section, and every other heading written in Ideas or Notes was dropped on ingest.
     */
    private val sectionTitles: Map<String, Section> = mapOf(
        "timeline" to Section.TIMELINE,
        "journal" to Section.JOURNAL,
        "ideas / second brain" to Section.IDEAS,
        "idées / second cerveau" to Section.IDEAS,
        "ideas" to Section.IDEAS,
        "idées" to Section.IDEAS,
        "notes / self-care" to Section.NOTES,
        "notes" to Section.NOTES,
        "scraps (unprocessed)" to Section.SCRAPS,
        "scraps" to Section.SCRAPS,
        "gratitude" to Section.GRATITUDE
    )

    private val timelineRegex = Regex("^\\s*-\\s+(\\d{1,2}:\\d{2})\\s+(.*)")

    // The burner writes the scheduled time as a LEADING "at HH:mm " prefix, so only match it there.
    // An unanchored match would corrupt a label that merely CONTAINS the text
    // (e.g. "Meet Bob at 10:00" -> "Meet Bob", time 10:00).
    private val taskTimeRegex = Regex("^at (\\d{1,2}:\\d{2})(?:\\s|$)")

    fun parse(date: LocalDate, content: String): ParsedDaily {
        val lines = content.lines()
        val timelineEvents = mutableListOf<TimelineEntryEntity>()
        val tasks = mutableListOf<DailyTaskEntity>()
        val ideas = StringBuilder()
        val notes = StringBuilder()
        val scraps = mutableListOf<String>()
        val gratitude = mutableListOf<String>()

        var section = Section.HEADER

        lines.forEach { line ->
            val trimmed = line.trim()
            sectionOf(trimmed)?.let {
                section = it
                return@forEach
            }
            // Any other heading is the user's own: kept inside the free-text sections, skipped elsewhere.
            if (trimmed.startsWith("##") && section != Section.IDEAS && section != Section.NOTES) return@forEach

            when (section) {
                Section.TIMELINE -> {
                    val match = timelineRegex.find(line)
                    if (match != null) {
                        val (timeStr, text) = match.destructured
                        // Pull the googleEventId back out of its invisible marker (and strip
                        // it from the visible content) so the row stays Calendar-reclaimable.
                        val (cleanContent, googleEventId) = DailyMarkers.stripCalendarMarker(text)
                        try {
                            timelineEvents.add(
                                TimelineEntryEntity(
                                    date = date,
                                    time = LocalTime.parse(timeStr, DateTimeFormatter.ofPattern("H:mm")),
                                    content = cleanContent,
                                    googleEventId = googleEventId
                                )
                            )
                        } catch (e: Exception) {
                        }
                    }
                }
                Section.JOURNAL -> {
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

                        val timeMatch = taskTimeRegex.find(cleanLabel)
                        if (timeMatch != null) {
                            try {
                                scheduledTime = LocalTime.parse(timeMatch.groupValues[1], DateTimeFormatter.ofPattern("H:mm"))
                                finalLabel = cleanLabel.removeRange(timeMatch.range).trim()
                            } catch (e: Exception) {
                            }
                        }

                        tasks.add(
                            DailyTaskEntity(
                                scheduledDate = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                                label = finalLabel,
                                isDone = isDone,
                                priority = priority,
                                scheduledTime = scheduledTime?.format(DateTimeFormatter.ofPattern("HH:mm")),
                                googleTaskId = googleTaskId,
                                source = if (googleTaskId != null) "GoogleTasks" else "Local"
                            )
                        )
                    }
                }
                Section.IDEAS -> { if (line.isNotBlank() || ideas.isNotEmpty()) ideas.append(line).append("\n") }
                Section.NOTES -> { if (line.isNotBlank() || notes.isNotEmpty()) notes.append(line).append("\n") }
                Section.SCRAPS -> {
                    if (trimmed.startsWith("-")) {
                        val s = trimmed.removePrefix("-").trim()
                        if (s.isNotBlank()) scraps.add(s)
                    }
                }
                Section.GRATITUDE -> {
                    if (trimmed.startsWith("-")) {
                        // Burned as "- [x] content" — strip the bullet and checkbox.
                        val g = trimmed.removePrefix("-").trim()
                            .removePrefix("[x]").removePrefix("[X]").removePrefix("[ ]").trim()
                        if (g.isNotBlank()) gratitude.add(g)
                    }
                }
                Section.HEADER -> Unit
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

    /** The section a `## ` line opens, or null when it isn't one of the burner's section headers. */
    private fun sectionOf(trimmedLine: String): Section? {
        if (!trimmedLine.startsWith("## ")) return null
        val title = trimmedLine.removePrefix("## ").trim()
        // Drop the decorative emoji (and its variation selector) in front of the title.
        val start = title.indexOfFirst { it.isLetterOrDigit() }
        if (start < 0) return null
        return sectionTitles[title.substring(start).trim().lowercase()]
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
