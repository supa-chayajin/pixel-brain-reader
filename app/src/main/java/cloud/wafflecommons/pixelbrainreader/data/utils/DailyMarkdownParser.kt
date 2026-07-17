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
            if (trimmed.startsWith("## 🧠 Idées") || trimmed.startsWith("## Idées")) { section = "IDEAS"; return@forEach }
            if (trimmed.startsWith("## 📑 Notes") || trimmed.startsWith("## Notes")) { section = "NOTES"; return@forEach }
            if (trimmed.startsWith("## 💡 Scraps") || trimmed.startsWith("## Scraps")) { section = "SCRAPS"; return@forEach }
            if (trimmed.startsWith("## 🌟 Gratitude") || trimmed.startsWith("## Gratitude")) { section = "GRATITUDE"; return@forEach }
            if (trimmed.startsWith("##")) return@forEach // Skip other headers

            when (section) {
                "TIMELINE" -> {
                     val match = timelineRegex.find(line)
                     if (match != null) {
                         val (timeStr, text) = match.destructured
                         try {
                              timelineEvents.add(TimelineEntryEntity(
                                  date = date,
                                  time = LocalTime.parse(timeStr, DateTimeFormatter.ofPattern("H:mm")),
                                  content = text.trim()
                              ))
                         } catch (e: Exception) {}
                     }
                }
                "JOURNAL" -> {
                    if (trimmed.startsWith("- [")) {
                        val isDone = trimmed.startsWith("- [x]")
                        val rawLabel = trimmed.substringAfter("] ").trim()
                        val priority = if (rawLabel.contains("‼️")) 2 else 1
                        val cleanLabel = rawLabel.replace("‼️", "").trim()
                        var scheduledTime: LocalTime? = null
                        var finalLabel = cleanLabel

                        val timeMatch = Regex("at (\\d{1,2}:\\d{2})").find(cleanLabel)
                        if (timeMatch != null) {
                            try {
                                scheduledTime = LocalTime.parse(timeMatch.groupValues[1], DateTimeFormatter.ofPattern("H:mm"))
                                finalLabel = cleanLabel.replace(timeMatch.value, "").trim()
                            } catch (e: Exception) {}
                        }

                        tasks.add(DailyTaskEntity(
                            scheduledDate = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                            label = finalLabel,
                            isDone = isDone,
                            priority = priority,
                            scheduledTime = scheduledTime?.format(DateTimeFormatter.ofPattern("HH:mm"))
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
            timeline = timelineEvents,
            tasks = tasks,
            ideas = ideas.toString().trim(),
            notes = notes.toString().trim(),
            scraps = scraps,
            gratitude = gratitude
        )
    }
}
