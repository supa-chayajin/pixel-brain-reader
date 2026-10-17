package cloud.wafflecommons.pixelbrainreader.data.sync

import cloud.wafflecommons.pixelbrainreader.data.utils.parseJohnnyDecimal

object SemanticCommitMessageGenerator {

    private val GENERIC_MESSAGES = setOf(
        "auto-sync",
        "auto-save: local changes",
        "sync changes",
        "save file",
        "sync"
    )

    fun isGenericMessage(message: String?): Boolean {
        if (message.isNullOrBlank()) return true
        return GENERIC_MESSAGES.contains(message.trim().lowercase())
    }

    /**
     * Analyzes the set of modified file paths and generates a conventional, semantic commit message.
     * E.g.
     * - "10_Journal/2026-10-17.md" -> "journal(2026-10-17): update daily note"
     * - "20_Projects/21_Waffle/App.kt" -> "projects(21_Waffle): update 1 file"
     * - "30_Areas/Finances.md" -> "areas(Finances): update note"
     * - Mixed -> "vault: update journal, projects (3 files)"
     */
    fun generate(modifiedPaths: Collection<String>, fallback: String = "Auto-save: local changes"): String {
        val cleanPaths = modifiedPaths.filter { !it.startsWith(".git") && it.isNotBlank() }
        if (cleanPaths.isEmpty()) return fallback

        // If only 1 file modified
        if (cleanPaths.size == 1) {
            val path = cleanPaths.first()
            val fileName = path.substringAfterLast("/")
            val baseName = fileName.removeSuffix(".md")
            val topFolder = path.substringBefore("/")
            val category = parseJohnnyDecimal(topFolder)?.label?.lowercase()

            return when {
                path.startsWith("10_Journal") -> "journal($baseName): update daily note"
                category != null -> {
                    val subFolder = path.split("/").getOrNull(1)
                    if (subFolder != null && subFolder != fileName) {
                        "$category($subFolder): update $baseName"
                    } else {
                        "$category: update $baseName"
                    }
                }
                else -> "note($baseName): update content"
            }
        }

        // Multiple files modified: group by top-level folder
        val groups = cleanPaths.groupBy { it.substringBefore("/") }
        if (groups.size == 1) {
            val topFolder = groups.keys.first()
            val category = parseJohnnyDecimal(topFolder)?.label?.lowercase() ?: topFolder.lowercase()
            return "$category: update ${cleanPaths.size} files"
        }

        val categoryNames = groups.keys.map { topFolder ->
            parseJohnnyDecimal(topFolder)?.label?.lowercase() ?: topFolder.lowercase()
        }.distinct()

        return "vault: update ${categoryNames.joinToString(", ")} (${cleanPaths.size} files)"
    }
}
