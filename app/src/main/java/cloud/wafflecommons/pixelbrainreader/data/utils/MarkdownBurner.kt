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
        existingFrontmatter: String = "" 
    ): String {
        val sb = StringBuilder()

        // 1. Frontmatter
        if (existingFrontmatter.isNotBlank()) {
            sb.append(existingFrontmatter)
            if (!existingFrontmatter.endsWith("\n")) sb.append("\n")
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
            sb.append("*Aucun événement*\n")
        } else {
            timeline.sortedBy { it.time }.forEach { entry ->
                val timeStr = entry.time.format(DateTimeFormatter.ofPattern("HH:mm"))
                sb.append("- $timeStr ${entry.content}\n")
            }
        }
        sb.append("\n")

        // 5. Journal / Tasks Section
        sb.append("## 📝 Journal\n\n")
        if (tasks.isEmpty()) {
            sb.append("*Aucune tâche*\n")
        } else {
            tasks.sortedWith(compareBy<DailyTaskEntity> { it.isDone }
                .thenBy { it.scheduledTime == null } // Nulls last
                .thenBy { it.scheduledTime }
                .thenByDescending { it.priority }
            ).forEach { task ->
                val checkbox = if (task.isDone) "[x]" else "[ ]"
                val timePrefix = if (task.scheduledTime != null) "at ${task.scheduledTime} " else ""
                val priorityMark = if (task.priority > 1) "‼️ " else ""
                
                sb.append("- $checkbox $priorityMark$timePrefix${task.label}\n")
            }
        }
        sb.append("\n")

        // 6. Ideas / Second Brain
        sb.append("## 🧠 Idées / Second Cerveau\n\n")
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

        return sb.toString()
    }
}
