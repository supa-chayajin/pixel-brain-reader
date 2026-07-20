package cloud.wafflecommons.pixelbrainreader.data.utils

import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyDashboardEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.TimelineEntryEntity
import java.time.format.DateTimeFormatter

/**
 * Masterclass Utility to translate the DailyBuffer (Room) into a perfect Obsidian Markdown String.
 * Strict adherence to the template hierarchy.
 */
object MarkdownBurner {

    fun burn(
        dashboard: DailyDashboardEntity,
        timeline: List<TimelineEntryEntity>,
        tasks: List<DailyTaskEntity>,
        scratchNotes: List<cloud.wafflecommons.pixelbrainreader.data.local.entity.ScratchNoteEntity> = emptyList(),
        gratitudes: List<cloud.wafflecommons.pixelbrainreader.data.local.entity.GratitudeEntity> = emptyList(),
        existingFrontmatter: String = "" 
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
        if (timeline.isEmpty()) {
            sb.append("*No events*\n")
        } else {
            timeline.sortedBy { it.time }.forEach { entry ->
                val timeStr = entry.time.format(DateTimeFormatter.ofPattern("HH:mm"))
                // Preserve googleEventId across the round-trip so re-parsed Calendar rows
                // stay reclaimable by purgeGoogleTimelineForDate (fixes the triple-dup bug).
                val content = DailyMarkers.appendCalendarMarker(entry.content, entry.googleEventId)
                sb.append("- $timeStr $content\n")
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
                val label = DailyMarkers.appendTaskMarker(task.label, task.googleTaskId)

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
                sb.append("- ${scrap.content}\n")
            }
        }

        // 9. Gratitude Express (RFC-009)
        if (gratitudes.isNotEmpty()) {
            sb.append("\n## 🌟 Gratitude\n\n")
            gratitudes.forEach { entry ->
                sb.append("- [x] ${entry.content}\n")
            }
        }

        return sb.toString()
    }
}
