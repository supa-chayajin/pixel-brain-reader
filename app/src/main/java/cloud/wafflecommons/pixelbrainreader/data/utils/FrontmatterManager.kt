package cloud.wafflecommons.pixelbrainreader.data.utils

import java.util.Locale

data class DailyLogEntry(
    val time: String,
    val moodScore: Int,
    val moodLabel: String,
    val activities: List<String>,
    val note: String? = null
)

data class DailySummary(
    val dailyEmoji: String?,
    val lastUpdate: String?,
    val allActivities: List<String> = emptyList()
)

/**
 * Robust YAML Frontmatter Manager (The Guardian).
 * Implements the "Strict Double Frontmatter Strategy" using Regex for precise Block B targeting.
 */
object FrontmatterManager {

    private const val BLOCK_B_MARKER = "pixel_brain_log: true"
    
    // Matches a YAML block containing our specific marker
    private val blockBRegex = Regex("^---\n[\\s\\S]*?$BLOCK_B_MARKER[\\s\\S]*?\n---\n?", RegexOption.MULTILINE)
    
    // Surgical Regex for stripping Block B while preserving everything else
    private val blockBStripRegex = Regex("(?m)^---\\s*\\n(?s).*?$BLOCK_B_MARKER.*?\\n---\\s*\\n?")
    
    // Matches standard YAML block at the start of the file
    private val blockARegex = Regex("^---\n[\\s\\S]*?\n---\n?", RegexOption.MULTILINE)

    fun updateDailyLog(content: String, entry: DailyLogEntry): String {
        val matchB = blockBRegex.find(content)
        
        if (matchB != null) {
            // CASE 1: BLOCK B EXISTS -> UPDATE IT
            val existingBlockB = matchB.value.removeSurrounding("---").trim()
            val timeline = parseTimeline(existingBlockB.lines())
            val updatedTimeline = timeline + entry
            
            val reconstructed = reconstructBlockB(updatedTimeline, entry.time)
            val newBlockB = "---\n$reconstructed\n---\n"
            
            return content.replaceRange(matchB.range, newBlockB)
        } else {
            // CASE 2: BLOCK B MISSING -> INSERT IT
            val matchA = blockARegex.find(content)
            val reconstructed = reconstructBlockB(listOf(entry), entry.time)
            val newBlockB = "---\n$reconstructed\n---\n"

            return if (matchA != null) {
                // Insert after Block A
                val insertPos = matchA.range.last + 1
                if (insertPos < content.length) {
                    content.substring(0, insertPos) + "\n" + newBlockB + content.substring(insertPos)
                } else {
                    content.substring(0, insertPos) + "\n" + newBlockB
                }
            } else {
                // No Block A, insert at top
                newBlockB + "\n" + content
            }
        }
    }

    fun getDailySummary(content: String): DailySummary? {
        val match = blockBRegex.find(content) ?: return null
        val yaml = match.value.removeSurrounding("---").trim()
        val lines = yaml.lines()

        val emoji = lines.find { it.startsWith("daily_emoji:") }?.substringAfter(":")?.trim()?.removeSurrounding("\"")
        val lastUpdate = lines.find { it.startsWith("last_update:") }?.substringAfter(":")?.trim()?.removeSurrounding("\"")
        
        val activitiesLine = lines.find { it.startsWith("all_activities:") }
        val activities = activitiesLine?.substringAfter("[")?.substringBefore("]")?.split(",")
            ?.map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
            ?.filter { it.isNotEmpty() } ?: emptyList()
        
        return DailySummary(
            dailyEmoji = emoji,
            lastUpdate = lastUpdate,
            allActivities = activities
        )
    }

    /**
     * Smart Content Cleaning for Display.
     * Selective stripping based on file type.
     */
    fun prepareContentForDisplay(content: String): String {
        return if (content.contains(BLOCK_B_MARKER)) {
            // CASE A: Daily Note -> Strip ONLY Block B (system data)
            // Block A (Standard user tags) remains visible in the body.
            blockBStripRegex.replace(content, "")
        } else {
            // CASE B: Standard Note -> Strip the entire first frontmatter block
            // for a clean reading experience.
            blockARegex.replace(content, "")
        }
    }

    /**
     * Legacy method for stripping PixelBrain metadata.
     */
    fun stripPixelBrainMetadata(content: String): String {
        return blockBStripRegex.replace(content, "")
    }

    private fun reconstructBlockB(timeline: List<DailyLogEntry>, lastUpdateTime: String): String {
        val avgMood = if (timeline.isNotEmpty()) timeline.map { it.moodScore }.average() else 0.0
        val dailyEmoji = calculateDailyEmoji(avgMood)
        val allActivities = timeline.flatMap { it.activities }.distinct().sorted()

        val sb = StringBuilder()
        sb.appendLine(BLOCK_B_MARKER)
        sb.appendLine("average_mood: ${String.format(Locale.US, "%.1f", avgMood)}")
        sb.appendLine("daily_emoji: \"$dailyEmoji\"")
        sb.appendLine("all_activities: [${allActivities.joinToString(", ")}]")
        sb.appendLine("last_update: \"$lastUpdateTime\"")
        
        sb.appendLine("timeline:")
        timeline.forEach { entry ->
            sb.appendLine("  - time: \"${entry.time}\"")
            sb.appendLine("    mood_score: ${entry.moodScore}")
            sb.appendLine("    mood_label: \"${entry.moodLabel}\"")
            sb.appendLine("    activities: [${entry.activities.joinToString(", ")}]")
            if (!entry.note.isNullOrBlank()) {
                val safeNote = entry.note.replace("\"", "\\\"").replace("\n", " ")
                sb.appendLine("    note: \"$safeNote\"")
            }
        }
        return sb.toString().trim()
    }

    private fun calculateDailyEmoji(avg: Double): String {
        return when {
            avg < 1.8 -> "😫"
            avg < 2.6 -> "😞"
            avg < 3.4 -> "😐"
            avg < 4.2 -> "🙂"
            else -> "🤩"
        }
    }

    private fun parseTimeline(lines: List<String>): List<DailyLogEntry> {
        val entries = mutableListOf<DailyLogEntry>()
        var inTimeline = false
        var currentEntry: MutableMap<String, String>? = null
        var currentActivities = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("timeline:")) {
                inTimeline = true
                continue
            }
            if (inTimeline) {
                if (trimmed.startsWith("-")) {
                    currentEntry?.let { entries.add(createEntryFromMap(it, currentActivities)) }
                    currentEntry = mutableMapOf()
                    currentActivities = mutableListOf()
                    val rest = trimmed.substring(1).trim()
                    if (rest.isNotEmpty()) parseLineIntoMap(rest, currentEntry, currentActivities)
                } else if (trimmed.startsWith("  ") || line.startsWith("    ")) {
                    currentEntry?.let { parseLineIntoMap(trimmed, it, currentActivities) }
                } else if (!trimmed.startsWith(" ")) {
                    inTimeline = false
                }
            }
        }
        currentEntry?.let { entries.add(createEntryFromMap(it, currentActivities)) }
        return entries
    }

    private fun parseLineIntoMap(line: String, map: MutableMap<String, String>, activities: MutableList<String>) {
        val parts = line.split(":", limit = 2)
        if (parts.size == 2) {
            val key = parts[0].trim()
            val value = parts[1].trim().removeSurrounding("\"").removeSurrounding("'")
            if (key == "activities") {
                val listStr = value.removePrefix("[").removeSuffix("]")
                if (listStr.isNotEmpty()) {
                    activities.addAll(listStr.split(",").map { it.trim().removeSurrounding("\"").removeSurrounding("'") })
                }
            } else map[key] = value
        }
    }

    private fun createEntryFromMap(map: Map<String, String>, activities: List<String>): DailyLogEntry {
        return DailyLogEntry(
            time = map["time"] ?: "00:00",
            moodScore = map["mood_score"]?.toIntOrNull() ?: 3,
            moodLabel = map["mood_label"] ?: "😐",
            activities = activities.toList(),
            note = map["note"]
        )
    }
}
