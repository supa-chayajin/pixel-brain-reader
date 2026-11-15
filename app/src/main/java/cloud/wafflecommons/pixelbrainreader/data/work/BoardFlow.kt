package cloud.wafflecommons.pixelbrainreader.data.work

/**
 * Board swipes and WIP limits (E6, 12.21). A swipe moves a card one column along the board's
 * flow (To do → In progress → Paused → Done; Archived only from the card's sheet); a column may
 * have a work-in-progress limit, kept per board in `boards.json`, and shows a warning past it. Pure.
 */
object BoardFlow {

    /** The columns a swipe walks through, in order. */
    val FLOW = listOf(StatusColumn.TODO, StatusColumn.WIP, StatusColumn.PAUSED, StatusColumn.DONE)

    /** Where a swipe to the right takes a card; a card without a status starts the flow. */
    fun next(column: StatusColumn?): StatusColumn? = when (column) {
        null -> FLOW.first()
        else -> FLOW.getOrNull(FLOW.indexOf(column) + 1).takeIf { column in FLOW }
    }

    /** Where a swipe to the left takes a card. */
    fun previous(column: StatusColumn?): StatusColumn? = when (column) {
        null -> null
        else -> FLOW.getOrNull(FLOW.indexOf(column) - 1).takeIf { column in FLOW }
    }

    /** [config] with [column]'s limit on [board] set to [limit]; 0 or less removes it. */
    fun withLimit(config: BoardsConfig, board: String, column: StatusColumn, limit: Int): BoardsConfig {
        val current = config.wip[board].orEmpty()
        val updated = if (limit > 0) current + (column.name to limit) else current - column.name
        return config.copy(wip = if (updated.isEmpty()) config.wip - board else config.wip + (board to updated))
    }

    fun limitOf(config: BoardsConfig, board: String, column: StatusColumn): Int? = config.wip[board]?.get(column.name)?.takeIf { it > 0 }

    /** True when [count] cards are more than [column]'s limit on [board]. */
    fun over(config: BoardsConfig, board: String, column: StatusColumn, count: Int): Boolean =
        limitOf(config, board, column)?.let { count > it } == true
}
