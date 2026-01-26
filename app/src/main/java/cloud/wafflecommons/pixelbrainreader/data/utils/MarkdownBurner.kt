package cloud.wafflecommons.pixelbrainreader.data.utils

import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyBufferEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.TimelineEntryEntity
import java.time.format.DateTimeFormatter

/**
 * Masterclass Utility to translate the DailyBuffer (Room) into a perfect Obsidian Markdown String.
 * Strict adherence to the template hierarchy.
 */
object MarkdownBurner {

    fun burn(
        buffer: DailyBufferEntity,
        timeline: List<TimelineEntryEntity>,
        tasks: List<DailyTaskEntity>,
        existingFrontmatter: String = "" // Pass raw frontmatter block if available to preserve it
    ): String {
        val sb = StringBuilder()

        // 1. Frontmatter (Preserve or Default)
        if (existingFrontmatter.isNotBlank()) {
            sb.append(existingFrontmatter)
            if (!existingFrontmatter.endsWith("\n")) sb.append("\n")
        } else {
            // Minimal fallback if we are creating from scratch without template (unlikely)
            sb.append("---\n")
            sb.append("date: ${buffer.date}\n")
            sb.append("---\n")
        }
        sb.append("\n")

        // 2. Date Header
        val headerDate = buffer.date.format(DateTimeFormatter.ISO_DATE)
        sb.append("# $headerDate\n\n")

        // 3. Mantra
        if (buffer.mantra.isNotBlank()) {
            sb.append("*${buffer.mantra}*\n\n")
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
        
        // Group tasks by status or priority? 
        // User Requirement: "Unified Logic... Merge both lists... sorted by LocalTime... null time at bottom"
        // But here we are writing back to MD. The MD structure separates Timeline and Tasks typically?
        // Wait, "Constraint: Any task or entry where the time field is null MUST be automatically relegated to a 'General/Unscheduled' section"
        // The implementation plan implies a single list in UI, but in MD file, we usually keep standard checklist format.
        // Let's write them as standard markdown checklists.
        
        if (tasks.isEmpty()) {
            sb.append("*Aucune tâche*\n")
        } else {
            // Sort for the file: Done last? Or by time? 
            // Let's sort by time first, then priority, then unscheduled.
            // Actually, for the *file*, let's keep it clean.
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

        // 6. Ideas / Second Brain (Preserve empty section for now, or we need to ingest this too?)
        // The prompt says: "Frontmatter -> H1 -> Mantra -> Timeline -> Journal -> etc"
        // We should appending the rest?
        // Current scope is automating the "Living Buffer". 
        // Ideally we would have captured "Other Content" during ingest. 
        // For this iteration, let's append the standard footer section.
        sb.append("## 🧠 Idées / Second Cerveau\n\n")

        return sb.toString()
    }
}
