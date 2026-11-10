package cloud.wafflecommons.pixelbrainreader.widget.ui

/**
 * How many projects the Projects widget shows at a given height (pure, in dp).
 *
 * A row with its "→ next action" line is ~60 dp, one without it ~44 dp; the surface padding,
 * the header and the gap under it take ~78 dp. While three full rows don't fit, the widget drops
 * the next-action line to keep up to three projects visible; from three full rows up it shows as
 * many full rows as fit, up to [MAX_ROWS].
 */
object ProjectsWidgetLayout {

    const val MAX_ROWS = 10
    const val CHROME_DP = 78f
    const val FULL_ROW_DP = 60f
    const val COMPACT_ROW_DP = 44f
    private const val MIN_FULL_ROWS = 3

    data class Layout(val rows: Int, val withNext: Boolean)

    fun forHeight(heightDp: Float): Layout {
        val room = (heightDp - CHROME_DP).coerceAtLeast(0f)
        val full = (room / FULL_ROW_DP).toInt()
        if (full >= MIN_FULL_ROWS) return Layout(full.coerceAtMost(MAX_ROWS), withNext = true)
        val compact = (room / COMPACT_ROW_DP).toInt()
        return Layout(compact.coerceIn(1, MIN_FULL_ROWS), withNext = false)
    }

    /**
     * The heights at which the layout changes, smallest first — the widget declares one responsive
     * size per height, so Glance renders each layout and the launcher picks the tallest that fits.
     */
    fun breakpointsDp(): List<Float> {
        val compact = (1..MIN_FULL_ROWS).map { CHROME_DP + it * COMPACT_ROW_DP }
        val full = (MIN_FULL_ROWS..MAX_ROWS).map { CHROME_DP + it * FULL_ROW_DP }
        return (compact + full).distinct().sorted()
    }
}
