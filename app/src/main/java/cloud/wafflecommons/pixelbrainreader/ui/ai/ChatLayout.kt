package cloud.wafflecommons.pixelbrainreader.ui.ai

import cloud.wafflecommons.pixelbrainreader.data.work.projectDisplayName

/**
 * Where a bubble sits in a run of bubbles from the same side (the Chat revamp's C3, 12.20): runs
 * tighten the corners between their bubbles and show the persona's header once. Pure.
 */
enum class BubbleRun {
    ALONE, FIRST, MIDDLE, LAST;

    /** A bubble right under another from the same side. */
    val continuesAbove: Boolean get() = this == MIDDLE || this == LAST

    /** A bubble with another from the same side right under it. */
    val continuesBelow: Boolean get() = this == FIRST || this == MIDDLE

    companion object {
        /** Each message's place in its run, from who sent each one (true = you), in order. */
        fun of(fromUser: List<Boolean>): List<BubbleRun> = fromUser.indices.map { i ->
            val above = i > 0 && fromUser[i - 1] == fromUser[i]
            val below = i < fromUser.lastIndex && fromUser[i + 1] == fromUser[i]
            when {
                above && below -> MIDDLE
                above -> LAST
                below -> FIRST
                else -> ALONE
            }
        }
    }
}

/**
 * A source chip's label (C3): the note's title, as its file name says it, and the folder it sits
 * in, as people say it ("README · Garden Planner", "2026-04-02 · Journal"). Pure.
 */
internal object SourceLabel {
    fun of(path: String): String {
        val segments = path.split('/').filter { it.isNotBlank() }
        val file = segments.lastOrNull() ?: return path
        val title = file.removeSuffix(".md.enc").removeSuffix(".md").ifBlank { file }
        val folder = segments.dropLast(1).lastOrNull()?.let(::projectDisplayName)
        return if (folder.isNullOrBlank()) title else "$title · $folder"
    }
}
