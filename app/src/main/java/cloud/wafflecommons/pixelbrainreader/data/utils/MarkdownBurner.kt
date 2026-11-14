package cloud.wafflecommons.pixelbrainreader.data.utils

import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyDashboardEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.TimelineEntryEntity
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * A Timeline line the burn derives from data outside the board (12.17: a game's play of the day).
 * [text] is what follows the time, its invisible marker included; the parser leaves such lines out.
 */
data class TimelineLine(val time: LocalTime, val text: String)

/**
 * Masterclass Utility to translate the DailyBuffer (Room) into a perfect Obsidian Markdown String.
 * Strict adherence to the template hierarchy.
 */
object MarkdownBurner {

    /**
     * Every timeline entry, task, scrap and gratitude is burned as ONE list line, and
     * [DailyMarkdownParser] reads back only that line: text after a newline would be dropped on
     * the next re-parse (cold-start rehydrate, past-day view) and erased by the burn after it.
     * Line breaks are therefore folded into " · " — idempotent, so burn → parse → burn is stable.
     * Writers normalize with this too, so Room holds exactly what the file holds.
     */
    fun singleLine(text: String): String =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" · ")

    fun burn(
        dashboard: DailyDashboardEntity,
        timeline: List<TimelineEntryEntity>,
        tasks: List<DailyTaskEntity>,
        scratchNotes: List<cloud.wafflecommons.pixelbrainreader.data.local.entity.ScratchNoteEntity> = emptyList(),
        gratitudes: List<cloud.wafflecommons.pixelbrainreader.data.local.entity.GratitudeEntity> = emptyList(),
        existingFrontmatter: String = "",
        health: List<String> = emptyList(),
        derivedTimeline: List<TimelineLine> = emptyList()
    ): String {
        val sb = StringBuilder()

        // 1. Frontmatter. FrontmatterManager.extractFrontmatterRaw returns the YAML
        // WITHOUT its --- fences, so the burner must always re-fence — appending it
        // verbatim made the 2nd burn of a day emit unfenced YAML and the 3rd burn
        // (finding no fenced block) reset it, silently dropping user-added keys.
        if (existingFrontmatter.isNotBlank()) {
            val fm = existingFrontmatter.trimEnd('\n')
            if (fm.startsWith("---")) {
                // Already a fenced block — pass through untouched.
                sb.append(fm).append("\n")
            } else {
                sb.append("---\n").append(fm).append("\n---\n")
            }
        } else {
            sb.append("---\n")
            sb.append("date: ${dashboard.date}\n")
            sb.append("---\n")
        }
        sb.append("\n")

        // 2. Date Header
        val headerDate = dashboard.date.format(DateTimeFormatter.ISO_DATE)
        sb.append("# $headerDate\n\n")

        // 3. Mantra
        if (dashboard.dailyMantra.isNotBlank()) {
            sb.append("*${dashboard.dailyMantra}*\n\n")
        }

        // 4. Timeline Section
        sb.append("## 🗓️ Timeline\n\n")
        if (timeline.isEmpty() && derivedTimeline.isEmpty()) {
            sb.append("*No events*\n")
        } else {
            // Preserve googleEventId across the round-trip so re-parsed Calendar rows
            // stay reclaimable by purgeGoogleTimelineForDate (fixes the triple-dup bug).
            val board = timeline.map { entry ->
                TimelineLine(entry.time, DailyMarkers.appendCalendarMarker(singleLine(entry.content), entry.googleEventId))
            }
            // A stable sort: at the same minute the board's entries come before the derived lines.
            (board + derivedTimeline).sortedBy { it.time }.forEach { line ->
                sb.append("- ${line.time.format(DateTimeFormatter.ofPattern("HH:mm"))} ${line.text}\n")
            }
        }
        sb.append("\n")

        // 5. Journal / Tasks Section
        sb.append("## 📝 Journal\n\n")
        if (tasks.isEmpty()) {
            sb.append("*No tasks*\n")
        } else {
            tasks.sortedWith(compareBy<DailyTaskEntity> { it.isDone }
                .thenBy { it.scheduledTime == null } // Nulls last
                .thenBy { it.scheduledTime }
                .thenByDescending { it.priority }
            ).forEach { task ->
                val checkbox = if (task.isDone) "[x]" else "[ ]"
                val timePrefix = if (task.scheduledTime != null) "at ${task.scheduledTime} " else ""
                val priorityMark = if (task.priority > 1) "‼️ " else ""
                // Preserve googleTaskId across the round-trip so re-parsed Google tasks stay
                // reclaimable by purgeGoogleTasksForDate (fixes the triple-dup bug).
                val label = DailyMarkers.appendTaskMarker(singleLine(task.label), task.googleTaskId)

                sb.append("- $checkbox $priorityMark$timePrefix$label\n")
            }
        }
        sb.append("\n")

        // 6. Ideas / Second Brain
        sb.append("## 🧠 Ideas / Second Brain\n\n")
        if (dashboard.ideasContent.isNotBlank()) {
            sb.append(dashboard.ideasContent)
            if (!dashboard.ideasContent.endsWith("\n")) sb.append("\n")
        }
        sb.append("\n")
        
        // 7. Notes / Self-Care
        sb.append("## 📑 Notes / Self-care\n\n")
        if (dashboard.notesContent.isNotBlank()) {
            sb.append(dashboard.notesContent)
            if (!dashboard.notesContent.endsWith("\n")) sb.append("\n")
        }

        // 8. Unprocessed Scraps (Optional)
        if (scratchNotes.isNotEmpty()) {
            sb.append("\n## 💡 Scraps (Unprocessed)\n\n")
            scratchNotes.forEach { scrap ->
                sb.append("- ${singleLine(scrap.content)}\n")
            }
        }

        // 9. Gratitude Express (RFC-009)
        if (gratitudes.isNotEmpty()) {
            sb.append("\n## 🌟 Gratitude\n\n")
            gratitudes.forEach { entry ->
                sb.append("- [x] ${singleLine(entry.content)}\n")
            }
        }

        // 10. Health (12.11.0): derived from the day's health file, skipped by the parser.
        if (health.isNotEmpty()) {
            sb.append("\n${DailyHealthSection.HEADING}\n\n")
            health.forEach { sb.append(it).append("\n") }
        }

        return sb.toString()
    }
}
