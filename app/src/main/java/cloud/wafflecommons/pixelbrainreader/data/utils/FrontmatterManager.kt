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
    
    // Matches a YAML block containing our specific marker. Non-greedy to avoid swallowing previous blocks.
    private val blockBRegex = Regex("^---\\n(?:(?!---)[\\s\\S])*$BLOCK_B_MARKER(?:(?!---)[\\s\\S])*?\\n---\\n?", RegexOption.MULTILINE)
    
    // Surgical Regex for stripping Block B while preserving everything else
    private val blockBStripRegex = Regex("(?m)^---\\s*\\n(?s)(?:(?!---).)*?$BLOCK_B_MARKER(?:(?!---).)*?\\n---\\s*\\n?")
    
    // Matches standard YAML block at the start of the file
    private val blockARegex = Regex("^---\\n(?:(?!---)[\\s\\S])*?\\n---\\n?", RegexOption.MULTILINE)

    fun updateDailyLog(content: String, entry: DailyLogEntry): String {
        val matchB = blockBRegex.find(content)
        
        if (matchB != null) {
            val existingBlockB = matchB.value
            val lines = existingBlockB.removeSurrounding("---").trim().lines()
            val timeline = parseTimeline(lines)
            
            val newBlockB = reconstructPixelBrainBlock(existingBlockB, entry, timeline)
            return content.replaceRange(matchB.range, newBlockB + "\n")
        } else {
            val matchA = blockARegex.find(content)
            val newBlockB = reconstructPixelBrainBlock(null, entry, emptyList())

            return if (matchA != null) {
                val insertPos = matchA.range.last + 1
                val prefix = content.substring(0, insertPos)
                val suffix = content.substring(insertPos)
                val separator = if (prefix.endsWith("\n")) "" else "\n"
                prefix + separator + newBlockB + "\n" + suffix
            } else {
                newBlockB + "\n\n" + content
            }
        }
    }

    fun getDailySummary(content: String): DailySummary? {
        val match = blockBRegex.find(content) ?: return null
        val yaml = match.value.removeSurrounding("---").trim()
        val lines = yaml.lines()

        val emoji = lines.find { it.startsWith("daily_emoji:") }?.substringAfter(":")?.trim()?.removeSurrounding("\"")
        val lastUpdate = lines.find { it.startsWith("last_update:") }?.substringAfter(":")?.trim()?.removeSurrounding("\"")
        
        // Extract all activities from the timeline for the summary
        val timeline = parseTimeline(lines)
        val allActivities = timeline.flatMap { it.activities }.distinct().sorted()
        
        return DailySummary(
            dailyEmoji = emoji,
            lastUpdate = lastUpdate,
            allActivities = allActivities
        )
    }

    /**
     * Smart Content Cleaning for Display.
     * Selective stripping based on file type.
     */
    fun prepareContentForDisplay(content: String): String {
        // 1. Check if it is a Daily Note (Has PixelBrain Log)
        return if (content.contains("pixel_brain_log: true")) {
            // DAILY NOTE LOGIC:
            // Remove ONLY the PixelBrain block. Keep the Standard block visible.
            val pixelBrainPattern = "(?m)^---\\s*\\n(?s).*?pixel_brain_log: true.*?\\n---\\s*\\n?"
            content.replace(Regex(pixelBrainPattern), "")
        } else {
            // STANDARD NOTE LOGIC:
            // Remove THE ENTIRE first YAML block found at the top.
            val standardYamlPattern = "(?m)^---\\s*\\n(?s).*?\\n---\\s*\\n?"
            content.replaceFirst(Regex(standardYamlPattern), "")
        }
    }

    /**
     * Surgical stripping of PixelBrain metadata block.
     */
    fun stripPixelBrainMetadata(content: String): String {
        val pattern = "(?m)^---\\s*\\n(?s).*?pixel_brain_log: true.*?\\n---\\s*\\n?"
        return content.replace(Regex(pattern), "")
    }

    private fun reconstructPixelBrainBlock(
        originalBlock: String?, 
        newEntry: DailyLogEntry,
        timeline: List<DailyLogEntry>
    ): String {
        // 1. Merge & CLEAN
        // CRITICAL: Filter out any existing entries that look like "Ghost Entries"
        val cleanExistingTimeline = timeline.filter { 
            it.time != "00:00" && it.moodLabel.isNotBlank() 
        }
        
        val fullTimeline = cleanExistingTimeline + newEntry
        
        val (emoji, avg) = computeStats(fullTimeline)
        val lastUpdate = newEntry.time
        val allActivities = fullTimeline.flatMap { it.activities }.distinct()

        // 2. Build String (STRICT ORDER)
        return buildString {
            append("---\n")
            append("pixel_brain_log: true\n")
            append("daily_emoji: \"$emoji\"\n")
            append("last_update: \"$lastUpdate\"\n")
            append("average_mood: $avg\n")
            
            // Serialize activities array properly: ["A", "B"]
            val activitiesJson = allActivities.joinToString(", ", "[", "]") { "\"$it\"" }
            append("all_activities: $activitiesJson\n")
            
            append("timeline:\n")
            
            // 3. Serialize Timeline List
            // ONLY HERE do we write the entries. NOWHERE ELSE.
            fullTimeline.forEach { entry ->
                append("  - time: \"${entry.time}\"\n")
                append("    mood_score: ${entry.moodScore}\n")
                append("    mood_label: \"${entry.moodLabel}\"\n")
                
                val entryActivities = entry.activities.joinToString(", ", "[", "]") { "\"$it\"" }
                append("    activities: $entryActivities\n")
                
                if (!entry.note.isNullOrBlank()) {
                    append("    note: \"${entry.note}\"\n")
                }
            }
            
            append("---\n")
        }
    }

    private fun computeStats(timeline: List<DailyLogEntry>): Pair<String, Double> {
        val avg = if (timeline.isEmpty()) 0.0 else timeline.map { it.moodScore }.average()
        return Pair(calculateDailyEmoji(avg), avg)
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
