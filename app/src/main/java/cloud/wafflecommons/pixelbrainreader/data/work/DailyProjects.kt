package cloud.wafflecommons.pixelbrainreader.data.work

/**
 * Which projects the Daily screen's Projects block shows, one card each: the pinned ones, in the
 * order the Projects tab gives them (pin, unpin and move them there); with none pinned, the most
 * recently active few. Hidden projects never show. Pure, so it is tested without the vault.
 */
object DailyProjects {

    /** At most this many cards: the block is a glance, the Projects tab the whole list. */
    const val MAX_CARDS = 6

    /** Cards shown when nothing is pinned. */
    const val UNPINNED_CARDS = 3

    fun pick(arranged: ArrangedProjects): List<ProjectSummary> =
        arranged.pinned.take(MAX_CARDS).ifEmpty {
            arranged.others
                .filter { it.lastActivity != null }
                .sortedByDescending { it.lastActivity }
                .take(UNPINNED_CARDS)
        }
}
