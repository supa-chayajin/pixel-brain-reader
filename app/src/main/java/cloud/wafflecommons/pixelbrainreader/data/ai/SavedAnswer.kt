package cloud.wafflecommons.pixelbrainreader.data.ai

import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema

/** A chat answer saved to the inbox keeps the notes the model saw, as links (V12 E13). */
object SavedAnswer {

    /**
     * The `## Sources` section for [sources] (vault paths, best first), or "" when there is none.
     * Links are path-style without `.md` (`[[20_Projects/x/README]]`): exact in Obsidian, keyed by
     * name in the app's links index, so each source lists the saved answer in its Connections. A
     * private note is never named: the saved answer is a clear note that git pushes.
     */
    fun sourcesSection(sources: List<String>): String {
        val links = sources
            .filter { it.isNotBlank() && !isPrivate(it) }
            .map { it.removeSuffix(".md") }
            .distinct()
        if (links.isEmpty()) return ""
        return "\n## Sources\n\n" + links.joinToString("\n") { "- [[$it]]" } + "\n"
    }

    private fun isPrivate(path: String): Boolean =
        VaultSchema.typeFor(path) == VaultSchema.TYPE_PRIVATE || path.endsWith(".md.enc", ignoreCase = true)
}
